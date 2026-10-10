#!/usr/bin/env python3
"""Aggregate results/per_utt/*.csv, rtf.jsonl, mixed_lang.jsonl, vad.json into
results/accuracy.csv, results/accuracy_subset25_aac24.csv, results/rtf.csv and results/summary.md."""
import csv
import json
import sys
from collections import defaultdict
from pathlib import Path

import jiwer

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bench import norm_en, norm_ja  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "results"
DATASETS = ["fleurs_ja", "reazon_ja", "fleurs_en"]
CONDS = ["raw", "aac32", "aac24", "noisy_aac24"]
ORDER = ["sensevoice_small_int8", "zipformer_reazon_int8", "parakeet_tdt_ctc_0.6b_ja_int8",
         "dolphin_base_ctc_int8", "whisper_base_int8", "whisper_small_int8", "qwen3_asr_0.6b_int8"]
SUBSET_N = 25


def load_rows():
    rows = []
    for p in sorted((RES / "per_utt").glob("*.csv")):
        with open(p, encoding="utf-8") as fp:
            rows += list(csv.DictReader(fp))
    # drop malformed rows (e.g. a run killed mid-write) and duplicates from resumed runs
    seen, out = set(), []
    for r in rows:
        k = (r.get("model"), r.get("cond"), r.get("id"))
        if None in r or not str(r.get("errors", "")).isdigit() or k in seen:
            continue
        seen.add(k)
        out.append(r)
    return out


def manifest_subset():
    by = defaultdict(list)
    with open(ROOT / "data" / "manifest.jsonl", encoding="utf-8") as fp:
        for line in fp:
            u = json.loads(line)
            by[u["dataset"]].append(u["id"])
    return {i for v in by.values() for i in v[:SUBSET_N]}


def sd_errors(r):
    """substitutions + deletions only (ignores insertions) for ja rows. Diagnostic for
    ReazonSpeech, whose subtitle-derived references often omit speech at clip edges."""
    if r["lang"] != "ja":
        ref, hyp = norm_en(r["ref"]), norm_en(r["hyp"])
        if not ref or not hyp:
            return int(r["errors"])
        o = jiwer.process_words(ref, hyp)
        return o.substitutions + o.deletions
    ref, hyp = norm_ja(r["ref"]), norm_ja(r["hyp"])
    if not ref:
        return 0
    if not hyp:
        return len(ref)
    o = jiwer.process_characters(ref, hyp)
    return o.substitutions + o.deletions


def agg(rows):
    g = defaultdict(lambda: [0, 0, 0.0, 0.0, 0, 0])
    for r in rows:
        k = (r["model"], r["dataset"], r["cond"])
        a = g[k]
        a[0] += int(r["errors"])
        a[1] += int(r["ref_len"])
        a[2] += float(r["decode_sec"])
        a[3] += float(r["duration"])
        a[4] += 1
        a[5] += sd_errors(r)
    return g


def pct(x):
    return f"{100 * x:.1f}"


def main():
    rows = load_rows()
    full = agg(rows)
    with open(RES / "accuracy.csv", "w", newline="", encoding="utf-8") as fp:
        w = csv.writer(fp)
        w.writerow(["model", "dataset", "cond", "n_utts", "metric", "error_rate_pct",
                    "error_rate_no_insertions_pct", "audio_min", "rtf_during_run_contended"])
        for (m, ds, c), (e, n, t, d, k, sd) in sorted(full.items(), key=lambda kv: (ORDER.index(kv[0][0]), DATASETS.index(kv[0][1]), CONDS.index(kv[0][2]))):
            w.writerow([m, ds, c, k, "WER" if ds.endswith("_en") else "CER", pct(e / n), pct(sd / n),
                        round(d / 60, 1), round(t / d, 4)])

    sub_ids = manifest_subset()
    sub = agg([r for r in rows if r["id"] in sub_ids and r["cond"] == "aac24"])
    with open(RES / "accuracy_subset25_aac24.csv", "w", newline="", encoding="utf-8") as fp:
        w = csv.writer(fp)
        w.writerow(["model", "dataset", "n_utts", "metric", "error_rate_pct", "error_rate_no_insertions_pct",
                    "rtf_during_run_contended"])
        for (m, ds, c), (e, n, t, d, k, sd) in sorted(sub.items(), key=lambda kv: (ORDER.index(kv[0][0]), DATASETS.index(kv[0][1]))):
            w.writerow([m, ds, k, "WER" if ds.endswith("_en") else "CER", pct(e / n), pct(sd / n), round(t / d, 4)])

    rtf = []
    if (RES / "rtf.jsonl").exists():
        rtf = [json.loads(line) for line in open(RES / "rtf.jsonl", encoding="utf-8")]
    with open(RES / "rtf.csv", "w", newline="", encoding="utf-8") as fp:
        w = csv.writer(fp)
        w.writerow(["model", "threads", "rtf", "rtf_fleurs_ja", "rtf_reazon_ja", "rtf_fleurs_en", "load_sec",
                    "peak_rss_mb", "size_mb", "token_timestamps", "segment_timestamps", "loadavg_1m"])
        for r in rtf:
            pd = r["per_dataset"]
            w.writerow([r["model"], r["threads"], r["rtf"]] +
                       [pd.get(ds, {}).get("rtf", "") for ds in DATASETS] +
                       [r["load_sec"], r["peak_rss_mb"], r["size_mb"], r["token_timestamps"],
                        r["segment_timestamps"], r.get("loadavg_1m", "")])

    # ---------------------------------------------------------------- markdown
    L = ["# ASR desktop benchmark summary", "",
         "Error rate in % (ja = CER after NFKC + punctuation/space removal, en = WER). "
         "Generated by `scripts/summarize.py`.", ""]
    L += ["## Full sets (all utterances)", "",
          "Value = error rate [substitutions+deletions only] (n = utterances).", "",
          "| model | dataset | " + " | ".join(CONDS) + " |", "|---|---|" + "---|" * len(CONDS)]
    for m in ORDER:
        for ds in DATASETS:
            cells = []
            for c in CONDS:
                v = full.get((m, ds, c))
                cells.append(f"{pct(v[0] / v[1])} [{pct(v[5] / v[1])}] (n={v[4]})" if v else "-")
            if any(x != "-" for x in cells):
                L.append(f"| {m} | {ds} | " + " | ".join(cells) + " |")
    L += ["", f"## Common subset: first {SUBSET_N} utts per dataset, AAC 24 kbps", "",
          "Value = error rate (in parentheses: substitutions+deletions only, i.e. insertions ignored).", "",
          "| model | " + " | ".join(DATASETS) + " | RTF during run (contended, informational) |", "|---|" + "---|" * (len(DATASETS) + 1)]
    for m in ORDER:
        cells, e_t, d_t = [], 0.0, 0.0
        for ds in DATASETS:
            v = sub.get((m, ds, "aac24"))
            cells.append(f"{pct(v[0] / v[1])} ({pct(v[5] / v[1])})" if v else "-")
            if v:
                e_t += v[2]
                d_t += v[3]
        if any(x != "-" for x in cells):
            L.append(f"| {m} | " + " | ".join(cells) + f" | {e_t / d_t:.3f} |")
    if rtf:
        L += ["", "## Speed / memory (AAC24 subset, 3 utts per dataset, warm)", "",
              "| model | threads | RTF | load s | peak RSS MB | size MB | token ts | segment ts | loadavg |",
              "|---|---|---|---|---|---|---|---|---|"]
        for r in sorted(rtf, key=lambda r: (ORDER.index(r["model"]), r["threads"])):
            L.append(f"| {r['model']} | {r['threads']} | {r['rtf']:.3f} | {r['load_sec']} | {r['peak_rss_mb']} | "
                     f"{r['size_mb']} | {r['token_timestamps']} | {r['segment_timestamps']} | {r.get('loadavg_1m', '')} |")
    mp = RES / "mixed_lang.jsonl"
    if mp.exists():
        L += ["", "## Mixed-language / auto-detect check (raw audio, 0.5 s gap between utterances)", "",
              "| model | case | detected lang | hypothesis |", "|---|---|---|---|"]
        for line in open(mp, encoding="utf-8"):
            o = json.loads(line)
            L.append(f"| {o['model']} | {o['case']} | {o['det_lang']} | {o['hyp'].replace('|', '/')} |")
    for name in ("vad_default.json", "vad_tuned.json"):
        vp = RES / name
        if vp.exists():
            L += ["", f"## Silero VAD on ~10 min concatenated file (AAC24) — {name}", "", "```json",
                  vp.read_text(encoding="utf-8").strip(), "```"]
    (RES / "summary.md").write_text("\n".join(L) + "\n", encoding="utf-8")
    print("\n".join(L))


if __name__ == "__main__":
    main()

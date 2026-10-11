#!/usr/bin/env python3
"""sherpa-onnx offline ASR benchmark (accuracy / RTF / memory / mixed-language).

Run each invocation in its own process so that peak RSS is per model.

  .venv/bin/python scripts/bench.py accuracy --model sensevoice_small_int8 --threads 4
  .venv/bin/python scripts/bench.py rtf      --model sensevoice_small_int8 --threads 1
  .venv/bin/python scripts/bench.py mixed    --model sensevoice_small_int8
"""
import argparse
import csv
import json
import resource
import sys
import time
import unicodedata
from pathlib import Path

import numpy as np
import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parent))
from models import MODELS, ROOT  # noqa: E402

import jiwer  # noqa: E402

RES = ROOT / "results"
DATA = ROOT / "data"
CONDS = ["raw", "aac32", "aac24", "noisy_aac24"]


# ---------------------------------------------------------------- normalisation
def norm_ja(s: str) -> str:
    """NFKC, lowercase, drop punctuation / symbols / whitespace / control chars.
    Kanji vs kana vs digit spelling differences are NOT normalised (count as errors)."""
    s = unicodedata.normalize("NFKC", s).lower()
    return "".join(c for c in s if unicodedata.category(c)[0] not in "PZSC")


def norm_en(s: str) -> str:
    s = unicodedata.normalize("NFKC", s).lower().replace("-", " ")
    s = "".join(c if (c.isalnum() or c.isspace()) else " " for c in s)
    return " ".join(s.split())


def score(lang, ref, hyp):
    """Return (errors, ref_len) — chars for ja (CER), words for en (WER)."""
    if lang == "ja":
        r, h = norm_ja(ref), norm_ja(hyp)
        if not r:
            return 0, 0
        o = jiwer.process_characters(r, h) if h else None
        if o is None:
            return len(r), len(r)
        return o.substitutions + o.deletions + o.insertions, len(r)
    r, h = norm_en(ref), norm_en(hyp)
    if not r:
        return 0, 0
    if not h:
        n = len(r.split())
        return n, n
    o = jiwer.process_words(r, h)
    return o.substitutions + o.deletions + o.insertions, len(r.split())


# ---------------------------------------------------------------- helpers
def load_manifest():
    with open(DATA / "manifest.jsonl", encoding="utf-8") as fp:
        return [json.loads(line) for line in fp]


def read_wav(path):
    x, sr = sf.read(path, dtype="float32")
    assert sr == 16000, path
    return x


def decode(rec, x):
    s = rec.create_stream()
    s.accept_waveform(16000, x)
    rec.decode_stream(s)
    return s.result


def peak_rss_mb():
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0


def model_size_mb(files):
    return sum(Path(f).stat().st_size for f in files) / 1e6


# ---------------------------------------------------------------- commands
def cmd_accuracy(a):
    factory, langs, _ = MODELS[a.model]
    rec, files = factory(a.threads)
    utts = [u for u in load_manifest() if u["lang"] in langs]
    if a.limit:
        by = {}
        for u in utts:
            by.setdefault(u["dataset"], []).append(u)
        utts = [u for v in by.values() for u in v[: a.limit]]
    out = RES / "per_utt"
    out.mkdir(parents=True, exist_ok=True)
    conds = a.conds.split(",")
    path = out / f"{a.model}.csv"
    done = set()
    if path.exists():
        with open(path, encoding="utf-8") as fp:
            done = {(r["cond"], r["id"]) for r in csv.DictReader(fp)}
    new = not path.exists()
    with open(path, "a", encoding="utf-8", newline="") as fp:
        w = csv.writer(fp)
        if new:
            w.writerow(["model", "cond", "dataset", "lang", "id", "duration", "decode_sec",
                        "errors", "ref_len", "det_lang", "ref", "hyp"])
        for cond in conds:
            tot_e = tot_n = 0
            tot_t = tot_d = 0.0
            for u in utts:
                if (cond, u["id"]) in done:
                    continue
                x = read_wav(ROOT / u["path"].format(cond=cond))
                t0 = time.perf_counter()
                r = decode(rec, x)
                dt = time.perf_counter() - t0
                hyp = r.text.strip()
                e, n = score(u["lang"], u["ref"], hyp)
                tot_e += e
                tot_n += n
                tot_t += dt
                tot_d += u["duration"]
                w.writerow([a.model, cond, u["dataset"], u["lang"], u["id"], u["duration"],
                            f"{dt:.4f}", e, n, getattr(r, "lang", ""), u["ref"], hyp])
            fp.flush()
            if tot_n:
                print(f"{a.model} {cond}: err={tot_e / tot_n:.4f} rtf={tot_t / tot_d:.4f}", flush=True)
    print(f"peak_rss_mb={peak_rss_mb():.0f} size_mb={model_size_mb(files):.0f}")


def rtf_subset(langs, n_per_ds):
    by = {}
    for u in load_manifest():
        if u["lang"] in langs:
            by.setdefault(u["dataset"], []).append(u)
    return [u for v in by.values() for u in v[:n_per_ds]]


def cmd_rtf(a):
    factory, langs, desc = MODELS[a.model]
    t0 = time.perf_counter()
    rec, files = factory(a.threads)
    load_s = time.perf_counter() - t0
    utts = rtf_subset(langs, a.n)
    wavs = [read_wav(ROOT / u["path"].format(cond="aac24")) for u in utts]
    decode(rec, wavs[0])  # warm-up
    res = {}
    for ds in sorted({u["dataset"] for u in utts}):
        dur = dt = 0.0
        for u, x in zip(utts, wavs):
            if u["dataset"] != ds:
                continue
            t = time.perf_counter()
            r = decode(rec, x)
            dt += time.perf_counter() - t
            dur += len(x) / 16000
        res[ds] = dict(audio_sec=round(dur, 1), decode_sec=round(dt, 2), rtf=round(dt / dur, 4))
    # timestamps check on one utterance
    r = decode(rec, wavs[0])
    ts = list(getattr(r, "timestamps", []) or [])
    seg_ts = list(getattr(r, "segment_timestamps", []) or []) if hasattr(r, "segment_timestamps") else []
    tot_dur = sum(v["audio_sec"] for v in res.values())
    tot_dt = sum(v["decode_sec"] for v in res.values())
    row = dict(model=a.model, threads=a.threads, load_sec=round(load_s, 2),
               rtf=round(tot_dt / tot_dur, 4), per_dataset=res,
               peak_rss_mb=round(peak_rss_mb()), size_mb=round(model_size_mb(files)),
               token_timestamps=len(ts) > 0, segment_timestamps=len(seg_ts) > 0,
               n_utts=len(utts), audio_sec=round(tot_dur, 1),
               loadavg_1m=round(__import__('os').getloadavg()[0], 2))
    RES.mkdir(exist_ok=True)
    with open(RES / "rtf.jsonl", "a", encoding="utf-8") as fp:
        fp.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(json.dumps(row, ensure_ascii=False))


def cmd_mixed(a):
    """Qualitative language auto-detect / code-switch check."""
    factory, langs, _ = MODELS[a.model]
    rec, _ = factory(a.threads)
    man = load_manifest()
    ja = [u for u in man if u["dataset"] == "reazon_ja"][:2]
    en = [u for u in man if u["dataset"] == "fleurs_en"][:2]
    gap = np.zeros(int(0.5 * 16000), dtype=np.float32)
    cases = {
        "ja_only": [ja[0]],
        "en_only": [en[0]],
        "ja+en": [ja[1], en[1]],
        "en+ja": [en[0], ja[0]],
    }
    out = []
    for name, us in cases.items():
        x = np.concatenate(sum([[read_wav(ROOT / u["path"].format(cond="raw")), gap] for u in us], []))
        r = decode(rec, x)
        out.append(dict(model=a.model, case=name, ref=" / ".join(u["ref"] for u in us),
                        hyp=r.text.strip(), det_lang=getattr(r, "lang", "")))
    with open(RES / "mixed_lang.jsonl", "a", encoding="utf-8") as fp:
        for o in out:
            fp.write(json.dumps(o, ensure_ascii=False) + "\n")
            print(json.dumps(o, ensure_ascii=False))


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("accuracy")
    p.add_argument("--model", required=True, choices=MODELS)
    p.add_argument("--threads", type=int, default=4)
    p.add_argument("--conds", default=",".join(CONDS))
    p.add_argument("--limit", type=int, default=0, help="max utts per dataset (0 = all)")
    p.set_defaults(fn=cmd_accuracy)
    p = sub.add_parser("rtf")
    p.add_argument("--model", required=True, choices=MODELS)
    p.add_argument("--threads", type=int, default=1)
    p.add_argument("--n", type=int, default=10, help="utts per dataset")
    p.set_defaults(fn=cmd_rtf)
    p = sub.add_parser("mixed")
    p.add_argument("--model", required=True, choices=MODELS)
    p.add_argument("--threads", type=int, default=4)
    p.set_defaults(fn=cmd_mixed)
    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    main()

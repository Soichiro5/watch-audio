#!/usr/bin/env python3
"""Silero VAD (sherpa-onnx) on a ~10 min synthetic "all-day" recording.

Builds a long file by concatenating utterances (ja + en) separated by random
silence gaps (low-level noise floor, ~35% speech), round-trips it through
AAC-LC 24 kbps, then compares:
  A) VAD -> ASR on detected segments only
  B) no VAD: ASR on fixed 20 s chunks of the whole file
for processing time and corpus CER/WER.

  .venv/bin/python scripts/vad_bench.py --model sensevoice_small_int8
"""
import argparse
import json
import random
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parent))
import sherpa_onnx  # noqa: E402
from bench import load_manifest, read_wav, decode, norm_ja, score  # noqa: E402
from models import MODELS, ROOT  # noqa: E402
from prepare_data import aac_roundtrip  # noqa: E402

SR = 16000


def speech_mask(x, frame=512, rel_db=30.0):
    """Energy-based reference speech mask for a clean utterance: frames within rel_db of the
    loudest frame. FLEURS clips contain long leading/trailing silence, so the whole clip
    duration would overstate the true speech ratio."""
    n = len(x) // frame
    e = 10 * np.log10(np.mean(x[: n * frame].reshape(n, frame) ** 2, axis=1) + 1e-12)
    m = np.repeat(e > e.max() - rel_db, frame)
    return np.pad(m, (0, len(x) - len(m)))


def build_long(target_min, speech_ratio, seed):
    rng = random.Random(seed)
    man = [u for u in load_manifest() if u["dataset"] in ("reazon_ja", "fleurs_ja", "fleurs_en")]
    rng.shuffle(man)
    target = target_min * 60 * SR
    noise_floor = 10 ** (-60 / 20)  # ~ -60 dBFS white noise floor everywhere
    nprng = np.random.default_rng(seed)
    pieces, masks, truth, used = [], [], [], []
    pos = 0
    for u in man:
        x = read_wav(ROOT / u["path"].format(cond="raw"))
        # level-normalise each utterance (some FLEURS clips are at -54 dBFS) like a recorder AGC:
        # loudest 0.5 s window -> -20 dBFS RMS, random +-6 dB talker/distance variation
        w = SR // 2
        loud = max(np.sqrt(np.mean(x[i: i + w] ** 2)) for i in range(0, max(len(x) - w, 1), w // 2))
        x = x * (10 ** ((-20 + rng.uniform(-6, 6)) / 20) / (loud + 1e-9))
        x = np.clip(x, -0.99, 0.99).astype(np.float32)
        # gap length so that speech / total ~= speech_ratio on average
        gap_s = len(x) / SR * (1 / speech_ratio - 1) * rng.uniform(0.3, 1.7)
        g = int(gap_s * SR)
        pieces.append(nprng.normal(0, noise_floor, g).astype(np.float32))
        masks.append(np.zeros(g, bool))
        pos += g
        pieces.append(x + nprng.normal(0, noise_floor, len(x)).astype(np.float32))
        masks.append(speech_mask(x))
        truth.append((pos / SR, (pos + len(x)) / SR))
        used.append(u)
        pos += len(x)
        if pos >= target:
            break
    tail = nprng.normal(0, noise_floor, 3 * SR).astype(np.float32)
    y = np.concatenate(pieces + [tail])
    mask = np.concatenate(masks + [np.zeros(len(tail), bool)])
    return y, mask, truth, used


def run_vad(x, threads, threshold=0.5, pad=0.0):
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = str(ROOT / "models" / "silero_vad.onnx")
    cfg.silero_vad.threshold = threshold
    cfg.silero_vad.min_silence_duration = 0.5
    cfg.silero_vad.min_speech_duration = 0.25
    cfg.silero_vad.max_speech_duration = 20
    cfg.sample_rate = SR
    cfg.num_threads = threads
    vad = sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=60)
    win = cfg.silero_vad.window_size
    segs = []
    t0 = time.perf_counter()
    for i in range(0, len(x), win):
        vad.accept_waveform(x[i: i + win])
        while not vad.empty():
            segs.append((vad.front.start, np.array(vad.front.samples, dtype=np.float32)))
            vad.pop()
    vad.flush()
    while not vad.empty():
        segs.append((vad.front.start, np.array(vad.front.samples, dtype=np.float32)))
        vad.pop()
    if pad > 0:  # widen each segment by `pad` seconds on both sides (re-cut from the input)
        p = int(pad * SR)
        segs = [(max(st - p, 0), x[max(st - p, 0): st + len(s) + p]) for st, s in segs]
    return segs, time.perf_counter() - t0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="sensevoice_small_int8")
    ap.add_argument("--minutes", type=float, default=10)
    ap.add_argument("--speech-ratio", type=float, default=0.35)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--vad-threshold", type=float, default=0.5)
    ap.add_argument("--pad", type=float, default=0.0, help="seconds of context added around VAD segments")
    ap.add_argument("--out", default="vad.json")
    a = ap.parse_args()

    x, mask_t, truth, used = build_long(a.minutes, a.speech_ratio, a.seed)
    x = aac_roundtrip(x, 24)
    total = len(x) / SR
    speech_true = mask_t.sum() / SR
    utt_time = sum(e - s for s, e in truth)
    sf.write(ROOT / "data" / "vad_long_aac24.wav", x, SR, subtype="PCM_16")

    segs, vad_t = run_vad(x, 1, a.vad_threshold, a.pad)
    speech_det = sum(len(s) for _, s in segs) / SR
    # overlap of detected segments with ground truth speech
    mask_d = np.zeros(len(x), bool)
    for st, s in segs:
        mask_d[st: st + len(s)] = True
    recall = (mask_t & mask_d).sum() / mask_t.sum()
    utt_hit = sum(mask_d[int(s * SR): int(e * SR)].any() for s, e in truth)
    precision = (mask_t & mask_d).sum() / max(mask_d.sum(), 1)

    factory, _, _ = MODELS[a.model]
    rec, _ = factory(a.threads)
    decode(rec, x[: SR * 5])  # warm-up

    # A) VAD segments
    t0 = time.perf_counter()
    hyp_a = [decode(rec, s).text for _, s in segs]
    asr_a = time.perf_counter() - t0
    # B) fixed 20 s chunks, no VAD
    t0 = time.perf_counter()
    hyp_b = [decode(rec, x[i: i + 20 * SR]).text for i in range(0, len(x), 20 * SR)]
    asr_b = time.perf_counter() - t0

    ja_ref = "".join(u["ref"] for u in used if u["lang"] == "ja")
    # whole-file CER on Japanese-normalised text (English words are kept as letters)
    ref_all = "".join(norm_ja(u["ref"]) for u in used)
    e_a, n = score("ja", ref_all, "".join(norm_ja(h) for h in hyp_a))
    e_b, _ = score("ja", ref_all, "".join(norm_ja(h) for h in hyp_b))
    res = dict(
        model=a.model, vad_threshold=a.vad_threshold, pad_sec=a.pad, file_min=round(total / 60, 2), n_utts=len(used),
        n_ja=sum(u["lang"] == "ja" for u in used), n_en=sum(u["lang"] == "en" for u in used),
        utterance_time_ratio=round(utt_time / total, 3),
        speech_ratio_ref_energy=round(speech_true / total, 3), speech_ratio_vad=round(speech_det / total, 3),
        utts_detected=f"{utt_hit}/{len(truth)}",
        vad_frame_recall=round(float(recall), 3), vad_frame_precision=round(float(precision), 3),
        vad_segments=len(segs), vad_sec=round(vad_t, 2), vad_rtf=round(vad_t / total, 5),
        asr_with_vad_sec=round(asr_a, 1), asr_no_vad_20s_chunks_sec=round(asr_b, 1),
        total_with_vad_sec=round(asr_a + vad_t, 1),
        time_reduction=round(1 - (asr_a + vad_t) / asr_b, 3),
        char_err_mixed_with_vad=round(e_a / n, 4), char_err_mixed_no_vad=round(e_b / n, 4),
        ja_ref_chars=len(norm_ja(ja_ref)),
    )
    (ROOT / "results").mkdir(exist_ok=True)
    with open(ROOT / "results" / a.out, "w", encoding="utf-8") as fp:
        json.dump(res, fp, ensure_ascii=False, indent=2)
    print(json.dumps(res, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

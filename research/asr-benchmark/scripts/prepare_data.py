#!/usr/bin/env python3
"""Download evaluation sets and build the watch-pipeline conditions.

Datasets (public, no login):
  - fleurs_ja : Google FLEURS ja_jp test (read speech, Wikipedia sentences)
  - reazon_ja : ReazonSpeech held-out test (Japanese TV broadcast speech;
                HF mirror japanese-asr/ja_asr.reazonspeech_test, shard 0 only)
  - fleurs_en : Google FLEURS en_us test

Conditions (all 16 kHz mono PCM wav after decoding):
  - raw      : source resampled to 16 kHz mono
  - aac32    : raw -> AAC-LC 32 kbps (.m4a, 16 kHz mono) -> decoded wav
  - aac24    : raw -> AAC-LC 24 kbps -> decoded wav
  - noisy_aac24 : raw + 5-talker babble at SNR 10 dB -> AAC-LC 24 kbps -> wav

Usage (from research/asr-benchmark/):
  .venv/bin/python scripts/prepare_data.py --n-fleurs-ja 120 --n-reazon 150 --n-fleurs-en 120
"""
import argparse
import csv
import io
import json
import random
import shutil
import subprocess
import tarfile
import urllib.request
from pathlib import Path

import numpy as np
import soundfile as sf

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
DL = DATA / "download"
SR = 16000

FLEURS = "https://huggingface.co/datasets/google/fleurs/resolve/main/data/{lang}/{f}"
REAZON = ("https://huggingface.co/datasets/japanese-asr/ja_asr.reazonspeech_test/"
          "resolve/main/data/test-00000-of-00002.parquet")

CONDITIONS = ["raw", "aac32", "aac24", "noisy_aac24"]


def ffmpeg_bin():
    exe = shutil.which("ffmpeg")
    if exe:
        return exe
    import imageio_ffmpeg  # pip install imageio-ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


FFMPEG = ffmpeg_bin()


def download(url, dst: Path):
    if dst.exists():
        return dst
    dst.parent.mkdir(parents=True, exist_ok=True)
    print(f"download {url}")
    tmp = dst.with_suffix(dst.suffix + ".part")
    subprocess.run(["curl", "-fsSL", url, "-o", str(tmp)], check=True)
    tmp.rename(dst)
    return dst


def to_16k_mono(samples: np.ndarray, sr: int) -> np.ndarray:
    if samples.ndim > 1:
        samples = samples.mean(axis=1)
    samples = samples.astype(np.float32)
    if sr == SR:
        return samples
    # resample with ffmpeg (soxr not guaranteed); pipe float32
    p = subprocess.run(
        [FFMPEG, "-v", "error", "-f", "f32le", "-ar", str(sr), "-ac", "1", "-i", "pipe:0",
         "-ar", str(SR), "-f", "f32le", "pipe:1"],
        input=samples.tobytes(), capture_output=True, check=True)
    return np.frombuffer(p.stdout, dtype=np.float32).copy()


def fleurs(lang: str, n: int, seed: int):
    tsv = download(FLEURS.format(lang=lang, f="test.tsv"), DL / f"fleurs_{lang}_test.tsv")
    rows = []
    with open(tsv, encoding="utf-8") as fp:
        for r in csv.reader(fp, delimiter="\t", quoting=csv.QUOTE_NONE):
            rows.append(dict(sid=r[0], file=r[1], raw=r[2], norm=r[3]))
    # one utterance per sentence id (FLEURS repeats sentences across speakers)
    rng = random.Random(seed)
    by_sid = {}
    for r in rows:
        by_sid.setdefault(r["sid"], []).append(r)
    sids = sorted(by_sid)
    rng.shuffle(sids)
    chosen = [rng.choice(by_sid[s]) for s in sids[:n]]
    want = {c["file"]: c for c in chosen}
    tar_path = download(FLEURS.format(lang=lang, f="audio/test.tar.gz"),
                        DL / f"fleurs_{lang}_test.tar.gz")
    out = []
    with tarfile.open(tar_path, "r:gz") as tf:
        for m in tf:
            name = Path(m.name).name
            if not m.isfile() or name not in want:
                continue
            data = tf.extractfile(m).read()
            wav, sr = sf.read(io.BytesIO(data), dtype="float32")
            c = want[name]
            out.append(dict(id=f"{lang}_{Path(name).stem}", ref=c["raw"], audio=to_16k_mono(wav, sr)))
    return out


def reazon(n: int, seed: int):
    import pyarrow.parquet as pq
    pqf = download(REAZON, DL / "reazon_test_00000.parquet")
    t = pq.read_table(pqf)
    rng = random.Random(seed)
    idx = list(range(t.num_rows))
    rng.shuffle(idx)
    out = []
    audio_col = t.column("audio")
    text_col = t.column("transcription")
    for i in idx:
        a = audio_col[i].as_py()
        ref = text_col[i].as_py()
        wav, sr = sf.read(io.BytesIO(a["bytes"]), dtype="float32")
        dur = len(wav) / sr
        # skip extremely short clips (< 1.5 s) which are mostly fillers
        if dur < 1.5 or not ref.strip():
            continue
        out.append(dict(id=f"reazon_{i:05d}", ref=ref, audio=to_16k_mono(wav, sr)))
        if len(out) >= n:
            break
    return out


def aac_roundtrip(x: np.ndarray, kbps: int) -> np.ndarray:
    """PCM -> AAC-LC (m4a, 16 kHz mono, CBR-ish) -> PCM. Uses ffmpeg native AAC encoder."""
    tmp = DATA / "tmp_aac.m4a"
    subprocess.run(
        [FFMPEG, "-v", "error", "-y", "-f", "f32le", "-ar", str(SR), "-ac", "1", "-i", "pipe:0",
         "-c:a", "aac", "-profile:a", "aac_low", "-b:a", f"{kbps}k", "-ar", str(SR), "-ac", "1",
         str(tmp)], input=x.astype(np.float32).tobytes(), check=True)
    p = subprocess.run(
        [FFMPEG, "-v", "error", "-i", str(tmp), "-f", "f32le", "-ar", str(SR), "-ac", "1", "pipe:1"],
        capture_output=True, check=True)
    y = np.frombuffer(p.stdout, dtype=np.float32).copy()
    # AAC adds encoder priming delay; ffmpeg trims it via edit list. Pad/trim to original length.
    if len(y) < len(x):
        y = np.pad(y, (0, len(x) - len(y)))
    return y[: len(x)]


def add_babble(x, pool, rng, snr_db=10.0, talkers=5):
    noise = np.zeros_like(x)
    for _ in range(talkers):
        s = pool[rng.randrange(len(pool))]
        if len(s) < len(x):
            s = np.tile(s, int(np.ceil(len(x) / len(s))))
        off = rng.randrange(0, len(s) - len(x) + 1)
        seg = s[off: off + len(x)]
        noise += seg / (np.sqrt(np.mean(seg ** 2)) + 1e-9)
    px = np.mean(x ** 2) + 1e-12
    pn = np.mean(noise ** 2) + 1e-12
    noise *= np.sqrt(px / (pn * 10 ** (snr_db / 10)))
    y = x + noise
    peak = np.max(np.abs(y))
    if peak > 0.99:
        y = y * (0.99 / peak)
    return y.astype(np.float32)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n-fleurs-ja", type=int, default=120)
    ap.add_argument("--n-reazon", type=int, default=150)
    ap.add_argument("--n-fleurs-en", type=int, default=120)
    ap.add_argument("--seed", type=int, default=20261010)
    args = ap.parse_args()

    sets = {
        "fleurs_ja": ("ja", fleurs("ja_jp", args.n_fleurs_ja, args.seed)),
        "reazon_ja": ("ja", reazon(args.n_reazon, args.seed)),
        "fleurs_en": ("en", fleurs("en_us", args.n_fleurs_en, args.seed)),
    }
    # babble pool: all selected utterances (mixed ja/en talkers)
    pool = [u["audio"] for _, (_, us) in sets.items() for u in us]
    rng = random.Random(args.seed)

    manifest = []
    for ds, (lang, utts) in sets.items():
        for u in utts:
            x = u["audio"]
            # normalise peak level a bit so babble/AAC are comparable
            variants = {
                "raw": x,
                "aac32": aac_roundtrip(x, 32),
                "aac24": aac_roundtrip(x, 24),
                "noisy_aac24": aac_roundtrip(add_babble(x, pool, rng), 24),
            }
            for cond, y in variants.items():
                p = DATA / "cond" / cond / ds / f"{u['id']}.wav"
                p.parent.mkdir(parents=True, exist_ok=True)
                sf.write(p, y, SR, subtype="PCM_16")
            manifest.append(dict(id=u["id"], dataset=ds, lang=lang, ref=u["ref"],
                                 duration=round(len(x) / SR, 3),
                                 path=f"data/cond/{{cond}}/{ds}/{u['id']}.wav"))
        print(f"{ds}: {len(utts)} utts, {sum(len(u['audio']) for u in utts) / SR / 60:.1f} min")
    (DATA / "tmp_aac.m4a").unlink(missing_ok=True)
    with open(DATA / "manifest.jsonl", "w", encoding="utf-8") as fp:
        for m in manifest:
            fp.write(json.dumps(m, ensure_ascii=False) + "\n")
    print(f"wrote {len(manifest)} utterances x {len(CONDITIONS)} conditions")


if __name__ == "__main__":
    main()

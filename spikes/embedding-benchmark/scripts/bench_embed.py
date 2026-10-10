"""Embedding throughput benchmark on typical transcript segments (20-60 Japanese chars).

Usage: python scripts/bench_embed.py [model ...] [--variants fp32,int8] [--n 96] [--reps 3]
Writes results/speed_embed.json (merged with existing entries).
Timing = best of `reps` runs (the container is shared with other jobs; min is the most
robust estimate of the uncontended speed). Tokenization time is included.
"""
import argparse
import json
import os
import random
import time
from pathlib import Path

import numpy as np

from embedder import Embedder
from models import MODELS

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "results" / "speed_embed.json"


def segments(n):
    conv = json.loads((ROOT / "data" / "conv_eval.json").read_text())
    ja = [d["text"] for d in conv["docs"]
          if 20 <= len(d["text"]) <= 60 and not d["text"].isascii()]
    return random.Random(0).sample(ja, n)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("models", nargs="*", default=list(MODELS))
    ap.add_argument("--variants", default="fp32,int8")
    ap.add_argument("--n", type=int, default=96)
    ap.add_argument("--reps", type=int, default=3)
    a = ap.parse_args()
    segs = segments(a.n)
    res = json.loads(OUT.read_text()) if OUT.exists() else {"runs": []}
    res["segments"] = {"n": len(segs), "avg_chars": float(np.mean([len(s) for s in segs]))}
    for name in a.models:
        for v in a.variants.split(","):
            if v not in MODELS[name]["variants"]:
                continue
            for th in (1, 4):
                emb = Embedder(name, v, threads=th)
                ntok = float(np.mean(emb.n_tokens(segs)))
                for bs in (1, 16):
                    emb.encode(segs[:16], batch_size=bs)  # warm-up
                    best = 1e9
                    for _ in range(a.reps):
                        t = time.perf_counter()
                        emb.encode(segs, batch_size=bs, sort_by_len=False)
                        best = min(best, time.perf_counter() - t)
                    row = {"model": name, "variant": v, "threads": th, "batch": bs, "n_segs": len(segs), "avg_tokens": round(ntok, 1),
                           "ms_per_seg": round(1000 * best / len(segs), 2), "segs_per_s": round(len(segs) / best, 1),
                           "day_3000_s": round(3000 * best / len(segs), 1), "loadavg": os.getloadavg()[0]}
                    print(row, flush=True)
                    res["runs"] = [r for r in res["runs"] if not (r["model"] == name and r["variant"] == v
                                                                  and r["threads"] == th and r["batch"] == bs)]
                    res["runs"].append(row)
                OUT.write_text(json.dumps(res, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()

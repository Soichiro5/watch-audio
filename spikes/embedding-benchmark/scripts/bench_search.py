"""Brute-force int8 vector search benchmark (numpy).

Stores N x D int8 vectors + per-vector float32 scale (as in segment_embedding BLOB + scale),
scores = (q_int8 . d_int8 accumulated in int32) * scale_d, then top-k.

Usage: python scripts/bench_search.py [--sizes 100000,500000,1000000] [--dim 384] [--k 50]
Writes results/search_numpy.json
"""
import argparse
import json
import os

# keep BLAS single-threaded; parallelism is controlled explicitly via the thread pool
for _v in ("OPENBLAS_NUM_THREADS", "OMP_NUM_THREADS", "MKL_NUM_THREADS"):
    os.environ.setdefault(_v, "1")
import platform
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
CHUNK = 32768


def make_db(n, dim, seed=0):
    rng = np.random.default_rng(seed)
    # "random-ish": 256 cluster centers + noise, L2-normalized, then per-vector int8 quantization
    centers = rng.standard_normal((256, dim), dtype=np.float32)
    db = np.empty((n, dim), dtype=np.int8)
    scale = np.empty(n, dtype=np.float32)
    for s in range(0, n, 100_000):
        e = min(n, s + 100_000)
        x = centers[rng.integers(0, 256, e - s)] + 0.8 * rng.standard_normal((e - s, dim), dtype=np.float32)
        x /= np.linalg.norm(x, axis=1, keepdims=True)
        sc = np.abs(x).max(1) / 127.0
        db[s:e] = np.round(x / sc[:, None]).astype(np.int8)
        scale[s:e] = sc
    return db, scale


def topk(scores, k):
    idx = np.argpartition(-scores, k)[:k]
    return idx[np.argsort(-scores[idx])]


def scan_chunk_int32(db, scale, q32, s, e):
    return (db[s:e].astype(np.int32) @ q32).astype(np.float32) * scale[s:e]


def scan_chunk_f32(db, scale, qf, s, e):
    # int8 -> float32 per chunk, then BLAS sgemv (exact: products and sums fit in float32 mantissa)
    return (db[s:e].astype(np.float32) @ qf) * scale[s:e]


def search(db, scale, q, k, threads, mode):
    n = db.shape[0]
    fn = scan_chunk_int32 if mode == "int32" else scan_chunk_f32
    qv = q.astype(np.int32) if mode == "int32" else q.astype(np.float32)
    ranges = [(s, min(n, s + CHUNK)) for s in range(0, n, CHUNK)]
    out = np.empty(n, dtype=np.float32)
    if threads == 1:
        for s, e in ranges:
            out[s:e] = fn(db, scale, qv, s, e)
    else:
        with ThreadPoolExecutor(threads) as ex:
            for (s, e), r in zip(ranges, ex.map(lambda r: fn(db, scale, qv, *r), ranges)):
                out[s:e] = r
    return topk(out, k)


def timeit(f, reps):
    f()  # warm-up
    ts = []
    for _ in range(reps):
        t = time.perf_counter()
        f()
        ts.append((time.perf_counter() - t) * 1000)
    return {"min_ms": round(min(ts), 2), "median_ms": round(float(np.median(ts)), 2)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sizes", default="100000,500000,1000000")
    ap.add_argument("--dim", type=int, default=384)
    ap.add_argument("--k", type=int, default=50)
    ap.add_argument("--reps", type=int, default=7)
    a = ap.parse_args()
    results = {"env": {"cpu": platform.processor() or platform.machine(), "cpus": os.cpu_count(),
                       "numpy": np.__version__, "loadavg_start": os.getloadavg()},
               "dim": a.dim, "k": a.k, "runs": []}
    for n in map(int, a.sizes.split(",")):
        db, scale = make_db(n, a.dim)
        q = db[12345].copy()
        # correctness: exact int32 path == float path ranking
        assert search(db, scale, q, a.k, 1, "int32")[0] == search(db, scale, q, a.k, 1, "f32")[0]
        for mode in ("int32", "f32"):
            for th in (1, 4):
                r = timeit(lambda: search(db, scale, q, a.k, th, mode), a.reps)
                row = {"n": n, "mode": mode, "threads": th, **r}
                print(row, flush=True)
                results["runs"].append(row)
        results.setdefault("storage_mb", {})[n] = round((db.nbytes + scale.nbytes) / 1e6, 1)
        del db
    results["env"]["loadavg_end"] = os.getloadavg()
    (ROOT / "results" / "search_numpy.json").write_text(json.dumps(results, indent=1))


if __name__ == "__main__":
    main()

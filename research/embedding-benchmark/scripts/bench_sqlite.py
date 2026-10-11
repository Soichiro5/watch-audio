"""How long does it take to read all int8 vector BLOBs out of SQLite (no in-memory cache)?

Creates segment_embedding(segment_id INTEGER PRIMARY KEY, model_id, vector BLOB, scale REAL)
with N rows of 384-byte vectors in cache/vec.db, then measures a full-table read
(the naive "SELECT vector FROM segment_embedding" per query) vs. a flat mmap'd file scan.
Usage: python scripts/bench_sqlite.py [--n 500000]
Writes results/sqlite_read.json
"""
import argparse
import json
import mmap
import os
import sqlite3
import time
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=500_000)
    ap.add_argument("--dim", type=int, default=384)
    a = ap.parse_args()
    (ROOT / "cache").mkdir(exist_ok=True)
    db_path = ROOT / "cache" / "vec.db"
    if db_path.exists():
        db_path.unlink()
    rng = np.random.default_rng(0)
    con = sqlite3.connect(db_path)
    con.execute("CREATE TABLE segment_embedding(segment_id INTEGER PRIMARY KEY, model_id TEXT, vector BLOB, scale REAL)")
    for s in range(0, a.n, 50_000):
        e = min(a.n, s + 50_000)
        v = rng.integers(-127, 128, (e - s, a.dim), dtype=np.int8)
        con.executemany("INSERT INTO segment_embedding VALUES (?,?,?,?)",
                        ((s + i, "m", v[i].tobytes(), 0.01) for i in range(e - s)))
    con.commit()
    db_mb = os.path.getsize(db_path) / 1e6

    def read_all():
        rows = con.execute("SELECT segment_id, vector, scale FROM segment_embedding").fetchall()
        return np.frombuffer(b"".join(r[1] for r in rows), dtype=np.int8).reshape(len(rows), a.dim)

    read_all()
    t = time.perf_counter()
    m = read_all()
    sqlite_ms = (time.perf_counter() - t) * 1000

    flat = ROOT / "cache" / "vec.bin"
    m.tofile(flat)
    with open(flat, "rb") as f:
        mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
        t = time.perf_counter()
        arr = np.frombuffer(mm, dtype=np.int8).reshape(-1, a.dim)
        s = int(arr[:, 0].astype(np.int32).sum())  # touch
        mmap_ms = (time.perf_counter() - t) * 1000
        del arr
        mm.close()
    res = {"n": a.n, "dim": a.dim, "sqlite_db_mb": round(db_mb, 1),
           "sqlite_full_read_ms": round(sqlite_ms, 1), "mmap_open_touch_ms": round(mmap_ms, 1),
           "bytes_per_row_sqlite": round(db_mb * 1e6 / a.n, 1), "sqlite_version": sqlite3.sqlite_version}
    print(res)
    (ROOT / "results" / "sqlite_read.json").write_text(json.dumps(res, indent=1))
    con.close()


if __name__ == "__main__":
    main()

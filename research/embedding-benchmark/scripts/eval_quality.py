"""Retrieval quality eval: models x {fp32,int8 ONNX} x {fp32, int8 scalar-quantized vectors}.

Usage: python scripts/eval_quality.py [model ...] [--variants fp32,int8] [--skip-gov] [--skip-jsts]
Writes results/quality_<model>_<variant>.json (one file per run, merged by make_report.py).
"""
import argparse
import json
import random
import time
from pathlib import Path

import numpy as np

from embedder import Embedder
from models import MODELS

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "results"
CACHE = ROOT / "cache"


def quantize_int8(x: np.ndarray):
    """Symmetric per-vector scalar quantization: q = round(x / s), s = max|x| / 127."""
    s = np.abs(x).max(axis=1, keepdims=True) / 127.0
    q = np.clip(np.round(x / s), -127, 127).astype(np.int8)
    return q, s.astype(np.float32)


def scores_fp32(q, d):
    return q @ d.T


def scores_int8(q, d):
    qq, qs = quantize_int8(q)
    dq, ds = quantize_int8(d)
    dot = qq.astype(np.int32) @ dq.astype(np.int32).T  # int32 accumulate, like on-device
    return dot.astype(np.float32) * qs * ds.T


def retrieval_metrics(S, doc_ids, queries, ks=(1, 5, 10)):
    top = np.argsort(-S, axis=1)[:, :max(ks)]
    out = {f"R@{k}": [] for k in ks}
    out["MRR@10"] = []
    hits_at10 = []
    for qi, q in enumerate(queries):
        rel = set(q["relevant"])
        ranked = [doc_ids[j] for j in top[qi]]
        for k in ks:
            out[f"R@{k}"].append(len(rel & set(ranked[:k])) / len(rel))
        rr = 0.0
        for r, did in enumerate(ranked[:10]):
            if did in rel:
                rr = 1.0 / (r + 1)
                break
        out["MRR@10"].append(rr)
        hits_at10.append(ranked[:3])
    return {k: float(np.mean(v)) for k, v in out.items()}, out, hits_at10


def rankdata(a):
    a = np.asarray(a)
    order = np.argsort(a, kind="mergesort")
    ranks = np.empty(len(a))
    sa = a[order]
    i = 0
    while i < len(a):
        j = i
        while j + 1 < len(a) and sa[j + 1] == sa[i]:
            j += 1
        ranks[order[i:j + 1]] = (i + j) / 2.0
        i = j + 1
    return ranks


def spearman(x, y):
    return float(np.corrcoef(rankdata(x), rankdata(y))[0, 1])


def embed_cached(emb: Embedder, key: str, texts, is_query):
    CACHE.mkdir(exist_ok=True)
    p = CACHE / f"{emb.name}_{emb.variant}_{key}.npy"
    if p.exists():
        return np.load(p)
    v = emb.encode(texts, is_query=is_query, batch_size=16)
    np.save(p, v)
    return v


def run(name, variant, skip_gov, skip_jsts):
    emb = Embedder(name, variant, threads=4, max_len=512)
    res = {"model": name, "variant": variant}
    t0 = time.time()

    conv = json.loads((ROOT / "data" / "conv_eval.json").read_text())
    D = embed_cached(emb, "conv_docs", [d["text"] for d in conv["docs"]], False)
    Q = embed_cached(emb, "conv_q", [q["text"] for q in conv["queries"]], True)
    ids = [d["id"] for d in conv["docs"]]
    res["dim"] = int(D.shape[1])
    for vq, fn in (("fp32vec", scores_fp32), ("int8vec", scores_int8)):
        m, per, top3 = retrieval_metrics(fn(Q, D), ids, conv["queries"])
        res[f"conv_{vq}"] = m
        # breakdown by query type
        types = sorted({q["type"] for q in conv["queries"]})
        res[f"conv_{vq}_by_type"] = {
            t: {k: float(np.mean([per[k][i] for i, q in enumerate(conv["queries"]) if q["type"] == t]))
                for k in per} for t in types}
        if vq == "fp32vec":
            text = {d["id"]: d["text"] for d in conv["docs"]}
            res["conv_misses"] = [
                {"q": q["text"], "top3": [text[x] for x in top3[i]]}
                for i, q in enumerate(conv["queries"]) if per["R@5"][i] < 1.0]

    if not skip_gov:
        gov = json.loads((ROOT / "data" / "jagovfaqs_subset.json").read_text())
        D = embed_cached(emb, "gov_docs", [d["text"] for d in gov["docs"]], False)
        Q = embed_cached(emb, "gov_q", [q["text"] for q in gov["queries"]], True)
        ids = [d["id"] for d in gov["docs"]]
        for vq, fn in (("fp32vec", scores_fp32), ("int8vec", scores_int8)):
            res[f"gov_{vq}"] = retrieval_metrics(fn(Q, D), ids, gov["queries"])[0]

    if not skip_jsts:
        jsts = json.loads((ROOT / "data" / "jsts_val.json").read_text())
        jsts = random.Random(0).sample(jsts, 500)
        # STS: symmetric, use the query prefix on both sides (as e5/ruri recommend for STS)
        A = embed_cached(emb, "jsts_a", [p["sentence1"] for p in jsts], True)
        B = embed_cached(emb, "jsts_b", [p["sentence2"] for p in jsts], True)
        res["jsts_spearman"] = spearman((A * B).sum(1), [p["score"] for p in jsts])

    res["eval_seconds"] = round(time.time() - t0, 1)
    RES.mkdir(exist_ok=True)
    (RES / f"quality_{name}_{variant}.json").write_text(json.dumps(res, ensure_ascii=False, indent=1))
    print(json.dumps({k: v for k, v in res.items() if k != "conv_misses" and "by_type" not in k}, ensure_ascii=False),
          flush=True)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("models", nargs="*", default=list(MODELS))
    ap.add_argument("--variants", default="fp32,int8")
    ap.add_argument("--skip-gov", action="store_true")
    ap.add_argument("--skip-jsts", action="store_true")
    a = ap.parse_args()
    for n in a.models:
        for v in a.variants.split(","):
            if v in MODELS[n]["variants"]:
                run(n, v, a.skip_gov, a.skip_jsts)

"""Build public Japanese eval subsets -> data/jagovfaqs_subset.json, data/jsts_val.json.

- JaGovFAQs-22k (from sbintuitions/JMTEB, CC-BY-4.0 origin): 150 random test queries,
  corpus = their relevant docs + random other docs up to 1,000 docs. Docs truncated to 200 chars
  (our segments are short; keeps the CPU eval fast).
- JSTS validation (mteb/JSTS, CC-BY-SA-4.0): 1,457 sentence pairs with 0-5 similarity score.
"""
import json
import random
from pathlib import Path

import pyarrow.parquet as pq
from huggingface_hub import hf_hub_download

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / "data" / "raw"


def main():
    rng = random.Random(0)
    corpus = pq.read_table(hf_hub_download("sbintuitions/JMTEB", "data/jagovfaqs_22k-corpus/corpus.parquet",
                                           repo_type="dataset", local_dir=RAW)).to_pylist()
    qs = pq.read_table(hf_hub_download("sbintuitions/JMTEB", "data/jagovfaqs_22k-query/test.parquet",
                                       repo_type="dataset", local_dir=RAW)).to_pylist()
    qs = rng.sample(qs, 150)
    by_id = {d["docid"]: d["text"] for d in corpus}
    need = {i for q in qs for i in q["relevant_docs"]}
    others = [i for i in by_id if i not in need]
    pick = sorted(need | set(rng.sample(others, 1000 - len(need))))
    docs = [{"id": str(i), "text": by_id[i][:200]} for i in pick]
    queries = [{"id": f"g{k:03d}", "text": q["query"], "relevant": [str(i) for i in q["relevant_docs"]]}
               for k, q in enumerate(qs)]
    (ROOT / "data" / "jagovfaqs_subset.json").write_text(
        json.dumps({"queries": queries, "docs": docs}, ensure_ascii=False))
    print("jagovfaqs:", len(queries), "queries", len(docs), "docs")

    jsts = pq.read_table(hf_hub_download("mteb/JSTS", "data/validation-00000-of-00001.parquet",
                                         repo_type="dataset", local_dir=RAW)).to_pylist()
    (ROOT / "data" / "jsts_val.json").write_text(json.dumps(jsts, ensure_ascii=False))
    print("jsts:", len(jsts), "pairs")


if __name__ == "__main__":
    main()

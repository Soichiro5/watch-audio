# M0-6 embedding benchmark (desktop)

Issue #12 "[M0-6] [Spike] 多言語埋め込みモデルの KMP 実行" のデスクトップ側スパイク。
結果のまとめは [`docs/research/m0-6-embedding-desktop.md`](../../docs/research/m0-6-embedding-desktop.md)。

Compares small multilingual embedding models (ONNX, fp32 vs int8) on a Japanese/English
conversational retrieval set, public Japanese sets, embedding speed, and brute-force int8 vector
search speed (numpy + plain Java). Only scripts and `results/` are committed; models, caches and
raw datasets are re-downloaded.

## Layout

| Path | What |
|---|---|
| `scripts/models.py` | Candidate registry (HF repos, ONNX files, pooling, prefixes) |
| `scripts/download_models.py` | Downloads ONNX + tokenizer files into `models/` (~5 GB incl. bge-m3 / embeddinggemma) |
| `scripts/build_conv_eval.py` | Builds `data/conv_eval.json` (hand-made ja/en conversational eval, 50 queries / 2,136 segments) |
| `scripts/build_public_eval.py` | Builds JaGovFAQs-22k subset (150 q / 1,000 docs) and JSTS val from HF datasets |
| `scripts/embedder.py` | Minimal tokenizer.json + onnxruntime embedder (what the device must replicate) |
| `scripts/eval_quality.py` | Recall@k / MRR, fp32 vs int8 model, fp32 vs int8 stored vectors, JSTS Spearman |
| `scripts/bench_embed.py` | Embedding throughput (1/4 threads, batch 1/16) on 20-60 char Japanese segments |
| `scripts/bench_search.py` | numpy brute-force int8 search over 100k / 500k / 1M x 384 |
| `java/SearchBench.java` | Plain Java brute-force scan (scalar loop, unrolled, `jdk.incubator.vector`) |
| `scripts/check_tokenizer.py` | How far simplified tokenizers (NFKC / no normalizer / sentencepiece lib) drift from tokenizer.json |
| `scripts/bench_sqlite.py` | Full read of 500k int8 BLOBs from SQLite vs mmap'd flat file |
| `scripts/make_report.py` | Renders `results/summary.md` from the JSON results |
| `scripts/make_tokenizer_fixture.py` | #24: golden token ids for the Kotlin `UnigramTokenizer` (`shared/pipeline/testdata/embedding/`) |
| `scripts/make_embedding_reference.py` | #24: reference ruri-v3 vectors for the Kotlin embedder (`tools/harness/testdata/ruri-reference.json`) |
| `results/` | Raw JSON results + `summary.md` |

## Reproduce

```bash
cd research/embedding-benchmark
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt

# 1. models (~5 GB) and eval data
.venv/bin/python scripts/download_models.py            # or: ... download_models.py e5-small ruri-v3-30m
.venv/bin/python scripts/build_conv_eval.py
.venv/bin/python scripts/build_public_eval.py

# 2. quality (embeddings cached in cache/)
.venv/bin/python scripts/eval_quality.py e5-small mMiniLM-L12 ruri-v3-30m
.venv/bin/python scripts/eval_quality.py embeddinggemma-300m --variants fp32
.venv/bin/python scripts/eval_quality.py bge-m3 --variants fp32 --skip-gov --skip-jsts   # slow; gov/jsts skipped in the recorded run
# optional (not in the recorded results, ~15 min under load):
# .venv/bin/python scripts/eval_quality.py embeddinggemma-300m --variants int8 --skip-gov --skip-jsts

# 3. speed
.venv/bin/python scripts/bench_embed.py e5-small mMiniLM-L12 ruri-v3-30m
.venv/bin/python scripts/bench_embed.py embeddinggemma-300m --variants fp32,int8 --n 48 --reps 2
.venv/bin/python scripts/bench_search.py --sizes 100000,500000,1000000
.venv/bin/python scripts/bench_sqlite.py --n 500000
.venv/bin/python scripts/check_tokenizer.py

cd java
javac --add-modules jdk.incubator.vector -d out SearchBench.java
java --add-modules jdk.incubator.vector -Xmx3g -cp out SearchBench 100000 384 50 ../results/search_java_100k.json
java --add-modules jdk.incubator.vector -Xmx3g -cp out SearchBench 500000 384 50 ../results/search_java_500k.json
java --add-modules jdk.incubator.vector -Xmx3g -cp out SearchBench 1000000 384 50 ../results/search_java_1m.json
cd ..

# 4. summary tables
.venv/bin/python scripts/make_report.py
```

Notes
- e5 uses `query: ` / `passage: ` prefixes, ruri-v3 `検索クエリ: ` / `検索文書: `, embeddinggemma
  `task: search result | query: ` / `title: none | text: ` (see `scripts/models.py`).
- "int8 model" = the `onnx/model_quantized.onnx` dynamic-quantized export from the HF repos
  (Xenova / onnx-community). "int8vec" = per-vector symmetric scalar quantization of the
  L2-normalized embedding (`q = round(v / (max|v|/127))`, score = int32 dot × scale).
- Speed numbers are best-of-N because the container was shared with other CPU-heavy jobs
  (Gradle build, ASR benchmark) during the run.

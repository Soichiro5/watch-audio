"""Candidate model registry shared by all benchmark scripts."""
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODELS_DIR = ROOT / "models"

# Each model: ONNX repo + files, tokenizer source, pooling, prefixes.
# "variants" maps a quantization label to the ONNX file inside the repo
# (external-data files *.onnx_data are fetched automatically when listed).
MODELS = {
    "e5-small": dict(
        params="118M (うち語彙埋め込み 96M)",
        dim=384,
        tokenizer="SentencePiece Unigram (XLM-R, 250k vocab, Precompiled NFKC normalizer)",
        hf_id="intfloat/multilingual-e5-small",
        onnx_repo="Xenova/multilingual-e5-small",
        variants={"fp32": ["onnx/model.onnx"], "int8": ["onnx/model_quantized.onnx"]},
        tok_repo="Xenova/multilingual-e5-small",
        tok_files=["tokenizer.json", "sentencepiece.bpe.model"],
        pooling="mean",
        q_prefix="query: ",
        d_prefix="passage: ",
        max_len=512,
        license="MIT",
    ),
    "mMiniLM-L12": dict(
        params="118M (うち語彙埋め込み 96M)",
        dim=384,
        tokenizer="SentencePiece Unigram (XLM-R, 250k vocab, Precompiled NFKC normalizer)",
        hf_id="sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
        onnx_repo="Xenova/paraphrase-multilingual-MiniLM-L12-v2",
        variants={"fp32": ["onnx/model.onnx"], "int8": ["onnx/model_quantized.onnx"]},
        tok_repo="sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
        tok_files=["tokenizer.json", "sentencepiece.bpe.model"],
        pooling="mean",
        q_prefix="",
        d_prefix="",
        max_len=128,  # sentence-transformers max_seq_length
        license="Apache-2.0",
    ),
    "ruri-v3-30m": dict(
        params="37M (うち語彙埋め込み 26M)",
        dim=256,
        tokenizer="SentencePiece Unigram (102k vocab, normalizer なし)",
        hf_id="cl-nagoya/ruri-v3-30m",
        onnx_repo="onnx-community/ruri-v3-30m-ONNX",
        variants={"fp32": ["onnx/model.onnx"], "int8": ["onnx/model_quantized.onnx"]},
        tok_repo="cl-nagoya/ruri-v3-30m",
        tok_files=["tokenizer.json", "tokenizer.model"],
        pooling="mean",
        q_prefix="検索クエリ: ",
        d_prefix="検索文書: ",
        max_len=8192,
        license="Apache-2.0",
    ),
    "embeddinggemma-300m": dict(
        params="308M (うち語彙埋め込み ~200M)",
        dim=768,
        tokenizer="SentencePiece BPE (Gemma3, 262k vocab, byte fallback)",
        hf_id="google/embeddinggemma-300m",
        onnx_repo="onnx-community/embeddinggemma-300m-ONNX",
        variants={
            "fp32": ["onnx/model.onnx", "onnx/model.onnx_data"],
            "int8": ["onnx/model_quantized.onnx", "onnx/model_quantized.onnx_data"],
        },
        tok_repo="onnx-community/embeddinggemma-300m-ONNX",
        tok_files=["tokenizer.json", "tokenizer.model"],
        pooling="sentence_embedding",  # graph already contains pooling + dense
        q_prefix="task: search result | query: ",
        d_prefix="title: none | text: ",
        max_len=2048,
        license="Gemma Terms of Use",
    ),
    "bge-m3": dict(
        params="568M",
        dim=1024,
        tokenizer="SentencePiece Unigram (XLM-R, 250k vocab)",
        hf_id="BAAI/bge-m3",
        onnx_repo="Xenova/bge-m3",
        variants={"fp32": ["onnx/model.onnx", "onnx/model.onnx_data"]},
        tok_repo="Xenova/bge-m3",
        tok_files=["tokenizer.json"],
        pooling="cls",
        q_prefix="",
        d_prefix="",
        max_len=8192,
        license="MIT",
    ),
}


def model_dir(name: str) -> Path:
    return MODELS_DIR / name

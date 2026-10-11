"""How hard is it to re-implement each tokenizer on-device?

For each candidate, compares the reference HF tokenizer.json output against simplified pipelines
that a pure Kotlin/Swift implementation would realistically use:
  - "nfkc"   : replace the tokenizer's normalizer with plain Unicode NFKC (available in
               java.text.Normalizer / Foundation) and keep Unigram/BPE model as is.
  - "nfkc_ws": NFKC + map \\s to space + collapse runs of spaces (approximates XLM-R's precompiled map).
  - "nonorm" : no normalizer at all.
  - "spm"    : the official sentencepiece .model file via the sentencepiece library
               (= what onnxruntime-extensions' SentencepieceTokenizer op / sentencepiece C++ do).
Reports the fraction of texts whose token ids differ. Writes results/tokenizer_check.json.
"""
import json
from pathlib import Path

import sentencepiece as spm
from tokenizers import Regex, Tokenizer, normalizers

from models import MODELS, model_dir

ROOT = Path(__file__).resolve().parent.parent


def texts():
    out = []
    for f in ("conv_eval.json", "jagovfaqs_subset.json"):
        d = json.loads((ROOT / "data" / f).read_text())
        out += [x["text"] for x in d["docs"]] + [x["text"] for x in d["queries"]]
    # a few normalization-sensitive strings (full-width, half-width kana, symbols)
    out += ["ＡＢＣ１２３　テスト", "ｶﾀｶﾅ と ㈱ と ① と ㍻", "Wi‑Fi “quote” — dash…", "  先頭と末尾の空白  "]
    return out


def main():
    T = texts()
    res = {}
    for name, cfg in MODELS.items():
        d = model_dir(name) / "tokenizer"
        if not (d / "tokenizer.json").exists():
            continue
        ref = Tokenizer.from_file(str(d / "tokenizer.json"))
        ref.no_padding()
        ref.no_truncation()
        ref_ids = [e.ids for e in ref.encode_batch(T, add_special_tokens=False)]
        r = {"n_texts": len(T), "normalizer": json.loads(ref.to_str()).get("normalizer", None) and
             json.loads(ref.to_str())["normalizer"]["type"]}
        for label, norm in (("nfkc", normalizers.NFKC()),
                            ("nfkc_ws", normalizers.Sequence([normalizers.NFKC(), normalizers.Replace(Regex(r"\s"), " "), normalizers.Replace(Regex(" {2,}"), " ")]) ),
                            ("nonorm", None)):
            t = Tokenizer.from_file(str(d / "tokenizer.json"))
            t.no_padding()
            t.no_truncation()
            t.normalizer = norm if norm is not None else normalizers.Sequence([])
            ids = [e.ids for e in t.encode_batch(T, add_special_tokens=False)]
            r[f"mismatch_{label}"] = round(sum(a != b for a, b in zip(ids, ref_ids)) / len(T), 4)
        spm_files = [p for p in d.glob("*.model")] + [p for p in d.glob("*.bpe.model")]
        if spm_files:
            sp = spm.SentencePieceProcessor(model_file=str(spm_files[0]))
            # HF XLM-R ids = spm ids + 1 (fairseq offset) except <unk>; compare pieces instead of ids
            ref_pieces = [e.tokens for e in ref.encode_batch(T, add_special_tokens=False)]
            sp_pieces = [sp.encode(t, out_type=str) for t in T]
            r["mismatch_spm_pieces"] = round(sum(a != b for a, b in zip(sp_pieces, ref_pieces)) / len(T), 4)
            r["spm_file"] = spm_files[0].name
        res[name] = r
        print(name, r, flush=True)
    (ROOT / "results" / "tokenizer_check.json").write_text(json.dumps(res, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()

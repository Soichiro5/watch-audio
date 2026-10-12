"""Golden tokenizer fixture for the Kotlin UnigramTokenizer (#24).

Tokenizes a fixed text set with the Hugging Face `tokenizers` library and the real ruri-v3-30m
tokenizer.json, and writes two files into shared/pipeline/testdata/embedding/:

- ruri-tokenizer-subset.json: a valid tokenizer.json with the same configuration but only the
  vocabulary entries that can match the texts (every piece that is a substring of a text after the
  Metaspace step), the byte-fallback pieces, the special tokens and one never-matching piece that
  carries the lowest score (the unknown-character penalty depends on it). Ids are renumbered.
  Viterbi only ever looks at pieces that are substrings of the input, so tokenizing these texts with
  the subset gives the same segmentation as with the full vocabulary (~6.7 MB, not committed).
- ruri-tokenizer-golden.json: per text, the ids from the full tokenizer (`ids`) and the same ids
  renumbered into the subset (`subset_ids`).

The Kotlin tests compare against `subset_ids` always and against `ids` when the full tokenizer.json
is available locally.

Usage: python make_tokenizer_fixture.py <tokenizer.json> <repo root>
"""
import json
import sys
from pathlib import Path

from tokenizers import Tokenizer

EDGE_CASES = [
    "",
    " ",
    "  二つの空白  と末尾の空白 ",
    "hello world  a<s>b",
    "<|user|>こんにちは<|assistant|>はい</s>",
    "🍣 寿司 ｶﾀｶﾅ ｱｲｳ",
    "👨‍👩‍👧 家族の絵文字と 😀😀😀 連続",
    "全角ＡＢＣ１２３と半角ABC123",
    "改行を\n含む\tタブも",
    "検索クエリ: 先週話した旅行の件",
    "検索文書: えーと、来月の沖縄だけどさ、飛行機は那覇行きの朝の便でいいかな",
    "Ｗｉ－Ｆｉ と Wi-Fi と ｗｉｆｉ",
    "〜ｰ―‐ 記号いろいろ ※★☆♪",
    "𠮷野家と𩸽（サロゲートペア）",
    "の" * 300,
    "<0x41>リテラルのバイト表記",
    "ＴＯＥＩＣ八百点、iPhone 17 Pro、COVID-19",
    "我々は中文也可以吗？한국어도 됩니다",
]


def corpus_lines(root: Path) -> list[str]:
    lines = []
    for path in sorted((root / "tools/test-audio/corpus").glob("*.txt")):
        for raw in path.read_text(encoding="utf-8").splitlines():
            if raw.startswith("#") or ":" not in raw:
                continue
            text = raw.split(":", 1)[1].strip()
            # Keep the ground truth of {text|reading}.
            out, i = [], 0
            while i < len(text):
                if text[i] == "{" and "|" in text[i:] and "}" in text[i:]:
                    end = text.index("}", i)
                    out.append(text[i + 1:end].split("|", 1)[0])
                    i = end + 1
                else:
                    out.append(text[i])
                    i += 1
            lines.append("".join(out))
    return lines


def main() -> None:
    tokenizer_path, root = Path(sys.argv[1]), Path(sys.argv[2])
    conv = json.loads((root / "research/embedding-benchmark/data/conv_eval.json").read_text(encoding="utf-8"))
    texts = EDGE_CASES + corpus_lines(root)
    texts += [q["text"] for q in conv["queries"]]
    texts += ["検索クエリ: " + q["text"] for q in conv["queries"]]
    texts += [d["text"] for d in conv["docs"] if d["kind"] != "filler"]
    texts += [d["text"] for d in conv["docs"] if d["kind"] == "filler"][:100]
    # Joined passages, as the app embeds them (several short segments in one text).
    lines = corpus_lines(root)
    texts += ["検索文書: " + " ".join(lines[i:i + 5]) for i in range(0, len(lines), 5)]

    tok = Tokenizer.from_file(str(tokenizer_path))
    full = json.loads(tokenizer_path.read_text(encoding="utf-8"))
    vocab = full["model"]["vocab"]
    min_score = min(score for _, score in vocab)

    replaced = [t.replace(" ", "▁") for t in texts]
    longest = max(len(piece) for piece, _ in vocab)
    substrings = {t[i:j] for t in replaced for i in range(len(t)) for j in range(i + 1, min(len(t), i + longest) + 1)}
    added = {a["content"] for a in full["added_tokens"]}
    keep = set()
    for i, (piece, _) in enumerate(vocab):
        if (piece.startswith("<0x") and len(piece) == 6) or piece in added or piece in substrings:
            keep.add(i)
    old_ids = sorted(keep)
    new_id = {old: new for new, old in enumerate(old_ids)}
    subset_vocab = [vocab[i] for i in old_ids]
    subset_vocab.append(["\u0000never-matches", min_score])

    subset = dict(full)
    subset["model"] = dict(full["model"], vocab=subset_vocab, unk_id=new_id[full["model"]["unk_id"]])
    subset["added_tokens"] = [dict(a, id=new_id[a["id"]]) for a in full["added_tokens"]]
    post = json.loads(json.dumps(full["post_processor"]))
    for name, special in post["special_tokens"].items():
        special["ids"] = [new_id[i] for i in special["ids"]]
    subset["post_processor"] = post

    golden = []
    for text in texts:
        ids = tok.encode(text).ids
        golden.append({"text": text, "ids": ids, "subset_ids": [new_id[i] for i in ids]})

    out = root / "shared/pipeline/testdata/embedding"
    out.mkdir(parents=True, exist_ok=True)
    (out / "ruri-tokenizer-subset.json").write_text(json.dumps(subset, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    rows = ",\n".join(json.dumps(g, ensure_ascii=False, separators=(",", ":")) for g in golden)
    (out / "ruri-tokenizer-golden.json").write_text(
        '{"tokenizer":"cl-nagoya/ruri-v3-30m tokenizer.json","texts":[\n' + rows + "\n]}\n", encoding="utf-8"
    )
    print(f"{len(texts)} texts, subset vocab {len(subset_vocab)} of {len(vocab)}")


if __name__ == "__main__":
    main()

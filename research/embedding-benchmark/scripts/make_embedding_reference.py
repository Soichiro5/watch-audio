"""Reference embeddings for the Kotlin embedder parity test (#24, tools/harness EmbedderParityTest).

Embeds a few queries and passages with the Python stack of the M0-6 spike (HF `tokenizers` +
onnxruntime, mean pooling, L2 normalization) and the app's ruri-v3-30m int8 files, and writes
tools/harness/testdata/ruri-reference.json (rounded to 6 decimals).

Usage: python make_embedding_reference.py <model dir with model_quantized.onnx + tokenizer.json> <repo root>
"""
import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

QUERY = "検索クエリ: "
DOC = "検索文書: "
TEXTS = [
    (QUERY, "先週話した旅行の件"),
    (QUERY, "歯医者の予約いつだっけ"),
    (QUERY, "会議の結論どうなった"),
    (QUERY, "what did we decide about the release"),
    (DOC, "来月の沖縄だけどさ、飛行機は那覇行きの朝の便でいいかな"),
    (DOC, "えーと、あのー 来週の木曜 午後三時に 歯医者さん予約しといたからね"),
    (DOC, "じゃあ今回はリリースを二週間延ばすってことで決定にしましょう はい 了解です"),
    (DOC, "ねえ、今週末どうする？ 土曜日は特に予定ないよ。 じゃあ、実家に行かない？お母さんが、久しぶりに顔を見せてって。"),
    (DOC, "うん"),
    (DOC, "🍣 寿司 ｶﾀｶﾅ と Wi-Fi"),
]


def main() -> None:
    model_dir, root = Path(sys.argv[1]), Path(sys.argv[2])
    tok = Tokenizer.from_file(str(model_dir / "tokenizer.json"))
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    sess = ort.InferenceSession(str(model_dir / "model_quantized.onnx"), so, providers=["CPUExecutionProvider"])
    rows = []
    for prefix, text in TEXTS:
        enc = tok.encode(prefix + text)
        ids = np.array([enc.ids], dtype=np.int64)
        mask = np.ones_like(ids)
        hidden = sess.run(["last_hidden_state"], {"input_ids": ids, "attention_mask": mask})[0][0]
        vector = hidden.mean(axis=0)
        vector = vector / np.linalg.norm(vector)
        rows.append({"query": prefix == QUERY, "text": text, "vector": [round(float(v), 6) for v in vector]})
    out = root / "tools/harness/testdata/ruri-reference.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    body = ",\n".join(json.dumps(r, ensure_ascii=False, separators=(",", ":")) for r in rows)
    out.write_text('{"model":"ruri-v3-30m int8 (onnx-community 1b41a27)","onnxruntime":"' + ort.__version__ + '","texts":[\n' + body + "\n]}\n", encoding="utf-8")
    print(f"wrote {len(rows)} vectors to {out}")


if __name__ == "__main__":
    main()

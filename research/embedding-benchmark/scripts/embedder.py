"""Minimal ONNX Runtime embedder (tokenizer.json via HF `tokenizers` + onnxruntime CPU).

This mirrors what the on-device implementation must do:
  tokenize -> input_ids/attention_mask(/token_type_ids) -> ONNX -> pooling -> L2 normalize.
"""
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

from models import MODELS, model_dir


class Embedder:
    def __init__(self, name: str, variant: str = "int8", threads: int = 4, max_len: int = 512):
        self.name, self.variant = name, variant
        self.cfg = MODELS[name]
        d = model_dir(name)
        onnx_path = d / self.cfg["variants"][variant][0]
        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        so.inter_op_num_threads = 1
        so.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self.sess = ort.InferenceSession(str(onnx_path), so, providers=["CPUExecutionProvider"])
        self.input_names = {i.name for i in self.sess.get_inputs()}
        self.tok = Tokenizer.from_file(str(d / "tokenizer" / "tokenizer.json"))
        self.max_len = min(max_len, self.cfg["max_len"])
        self.tok.enable_truncation(self.max_len)
        pad_id = self.tok.token_to_id("<pad>")
        self.tok.enable_padding(pad_id=pad_id if pad_id is not None else 0)
        self.dim = None

    def prefix(self, texts, is_query):
        p = self.cfg["q_prefix"] if is_query else self.cfg["d_prefix"]
        return [p + t for t in texts]

    def _run(self, texts):
        enc = self.tok.encode_batch(texts)
        ids = np.array([e.ids for e in enc], dtype=np.int64)
        mask = np.array([e.attention_mask for e in enc], dtype=np.int64)
        feeds = {"input_ids": ids, "attention_mask": mask}
        if "token_type_ids" in self.input_names:
            feeds["token_type_ids"] = np.zeros_like(ids)
        pooling = self.cfg["pooling"]
        if pooling == "sentence_embedding":
            out = self.sess.run(["sentence_embedding"], feeds)[0]
        else:
            h = self.sess.run(["last_hidden_state"], feeds)[0]
            if pooling == "cls":
                out = h[:, 0]
            else:  # mean
                m = mask[..., None].astype(np.float32)
                out = (h * m).sum(1) / np.clip(m.sum(1), 1e-9, None)
        out = out.astype(np.float32)
        return out / np.linalg.norm(out, axis=1, keepdims=True)

    def encode(self, texts, is_query=False, batch_size=16, sort_by_len=True):
        texts = self.prefix(list(texts), is_query)
        order = np.argsort([len(t) for t in texts]) if sort_by_len else np.arange(len(texts))
        res = [None] * len(texts)
        for s in range(0, len(texts), batch_size):
            idx = order[s:s + batch_size]
            vecs = self._run([texts[i] for i in idx])
            for i, v in zip(idx, vecs):
                res[i] = v
        return np.stack(res)

    def n_tokens(self, texts, is_query=False):
        return [sum(e.attention_mask) for e in self.tok.encode_batch(self.prefix(texts, is_query))]

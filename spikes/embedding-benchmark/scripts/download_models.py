"""Download ONNX exports + tokenizers of all candidates into ./models/<name>/."""
import sys
from huggingface_hub import hf_hub_download
from models import MODELS, model_dir

names = sys.argv[1:] or list(MODELS)
for name in names:
    m = MODELS[name]
    d = model_dir(name)
    for files in m["variants"].values():
        for f in files:
            p = hf_hub_download(m["onnx_repo"], f, local_dir=d)
            print(name, p)
    for f in m["tok_files"]:
        p = hf_hub_download(m["tok_repo"], f, local_dir=d / "tokenizer")
        print(name, p)

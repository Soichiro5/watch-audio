"""Candidate model registry for the sherpa-onnx desktop pre-selection benchmark."""
from pathlib import Path

import sherpa_onnx

ROOT = Path(__file__).resolve().parent.parent
M = ROOT / "models"


def _one(d: Path, pattern: str) -> str:
    hits = sorted(d.glob(pattern))
    if not hits:
        raise FileNotFoundError(f"{d}/{pattern}")
    return str(hits[0])


def sense_voice(threads):
    d = M / "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"
    files = [d / "model.int8.onnx", d / "tokens.txt"]
    rec = sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=str(files[0]), tokens=str(files[1]), num_threads=threads,
        language="", use_itn=True)  # language auto-detect, ITN + punctuation on
    return rec, files


def zipformer_reazon(threads):
    d = M / "sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01"
    files = [d / "encoder-epoch-99-avg-1.int8.onnx", d / "decoder-epoch-99-avg-1.int8.onnx",
             d / "joiner-epoch-99-avg-1.int8.onnx", d / "tokens.txt"]
    rec = sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=str(files[0]), decoder=str(files[1]), joiner=str(files[2]), tokens=str(files[3]),
        num_threads=threads, decoding_method="greedy_search")
    return rec, files


def parakeet_ja(threads):
    d = M / "sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8"
    files = [d / "model.int8.onnx", d / "tokens.txt"]
    rec = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=str(files[0]), tokens=str(files[1]), num_threads=threads)
    return rec, files


def dolphin_base(threads):
    d = M / "sherpa-onnx-dolphin-base-ctc-multi-lang-int8-2025-04-02"
    files = [d / "model.int8.onnx", d / "tokens.txt"]
    rec = sherpa_onnx.OfflineRecognizer.from_dolphin_ctc(
        model=str(files[0]), tokens=str(files[1]), num_threads=threads)
    return rec, files


def _whisper(size, threads):
    d = M / f"sherpa-onnx-whisper-{size}"
    files = [d / f"{size}-encoder.int8.onnx", d / f"{size}-decoder.int8.onnx", d / f"{size}-tokens.txt"]
    rec = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=str(files[0]), decoder=str(files[1]), tokens=str(files[2]),
        language="",  # empty = Whisper language auto-detection
        task="transcribe", num_threads=threads, enable_segment_timestamps=True)
    return rec, files


def whisper_base(threads):
    return _whisper("base", threads)


def whisper_small(threads):
    return _whisper("small", threads)


def qwen3_asr(threads):
    d = M / "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"
    conv = _one(d, "conv_frontend*.onnx")
    enc = _one(d, "encoder*.onnx")
    dec = _one(d, "decoder*.onnx")
    tok = d / "tokenizer"
    if not tok.is_dir():
        tok = d
    files = [Path(conv), Path(enc), Path(dec)] + [p for p in tok.iterdir() if p.is_file() and p.suffix != ".onnx"]
    rec = sherpa_onnx.OfflineRecognizer.from_qwen3_asr(
        conv_frontend=conv, encoder=enc, decoder=dec, tokenizer=str(tok), num_threads=threads,
        max_new_tokens=256)
    return rec, files


# name -> (factory, supported languages, short description)
MODELS = {
    "sensevoice_small_int8": (sense_voice, {"ja", "en"}, "SenseVoice-Small int8 (zh/en/ja/ko/yue), ITN on"),
    "zipformer_reazon_int8": (zipformer_reazon, {"ja"}, "k2 Zipformer transducer, ReazonSpeech (ja only)"),
    "parakeet_tdt_ctc_0.6b_ja_int8": (parakeet_ja, {"ja"}, "NVIDIA Parakeet TDT-CTC 0.6B ja (CTC head) int8"),
    "dolphin_base_ctc_int8": (dolphin_base, {"ja"}, "Dolphin base CTC multi-lang (40 Eastern langs, no en)"),
    "whisper_base_int8": (whisper_base, {"ja", "en"}, "OpenAI Whisper base int8, auto language"),
    "whisper_small_int8": (whisper_small, {"ja", "en"}, "OpenAI Whisper small int8, auto language"),
    "qwen3_asr_0.6b_int8": (qwen3_asr, {"ja", "en"}, "Qwen3-ASR 0.6B int8 (LLM-based)"),
}

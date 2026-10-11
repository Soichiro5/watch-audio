"""Offline Japanese TTS used to voice the corpus. Both engines run entirely on this machine:

- Kokoro-82M v1.0 (ONNX, via kokoro-onnx) with misaki's Japanese G2P: 4 female + 1 male voice.
- Open JTalk HTS voices via pyopenjtalk: "Mei" (female, bundled) and NIT ATR503 M001 (male).

Outputs are cached per (voice, speed, sentence) as 16 kHz FLAC so re-running is cheap.
"""
from __future__ import annotations

import hashlib
from fractions import Fraction
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy import signal

from effects import SAMPLE_RATE, normalize_active, trim_silence

KOKORO_VOICES = {"jf_alpha", "jf_gongitsune", "jf_nezumi", "jf_tebukuro", "jm_kumo"}
HTS_VOICES = {"ojt_mei", "ojt_m001"}
VOICES = KOKORO_VOICES | HTS_VOICES

KOKORO_MODEL = "kokoro/kokoro-v1.0.fp16.onnx"
KOKORO_VOICES_FILE = "kokoro/voices-v1.0.bin"
M001_VOICE = "hts/nitech_jp_atr503_m001.htsvoice"

# Every sentence is normalized to this speech-active level before mixing.
SPEECH_LEVEL_DB = -26.0


def _resample(x: np.ndarray, rate: int) -> np.ndarray:
    if rate == SAMPLE_RATE:
        return x.astype(np.float32)
    ratio = Fraction(SAMPLE_RATE, rate)
    return signal.resample_poly(x, ratio.numerator, ratio.denominator).astype(np.float32)


class Synthesizer:
    def __init__(self, models_dir: Path, cache_dir: Path):
        self.models_dir = models_dir
        self.cache_dir = cache_dir
        self._kokoro = None
        self._g2p = None
        self._hts: dict[str, object] = {}

    def say(self, voice: str, sentence: str, speed: float) -> np.ndarray:
        """Speech for one sentence: 16 kHz, silence trimmed, level-normalized."""
        speed = round(speed, 2)
        key = hashlib.sha1(f"{voice}|{speed}|{sentence}".encode()).hexdigest()
        path = self.cache_dir / voice / f"{key}.flac"
        if path.exists():
            audio, _ = sf.read(path, dtype="float32")
            return audio
        audio, rate = self._synthesize(voice, sentence, speed)
        audio = normalize_active(trim_silence(_resample(audio, rate)), SPEECH_LEVEL_DB)
        path.parent.mkdir(parents=True, exist_ok=True)
        sf.write(path, audio, SAMPLE_RATE, subtype="PCM_16")
        return audio

    def _synthesize(self, voice: str, sentence: str, speed: float) -> tuple[np.ndarray, int]:
        if voice in KOKORO_VOICES:
            kokoro, g2p = self._kokoro_engine()
            phonemes = g2p(sentence)
            phonemes = phonemes[0] if isinstance(phonemes, tuple) else phonemes
            return kokoro.create(phonemes, voice=voice, speed=speed, lang="j", is_phonemes=True)
        if voice in HTS_VOICES:
            import pyopenjtalk

            engine = self._hts_engine(voice)
            engine.set_speed(speed)
            try:
                wave = engine.synthesize(pyopenjtalk.extract_fullcontext(sentence))
            finally:
                engine.refresh()
            return (np.asarray(wave) / 32768.0).astype(np.float32), engine.get_sampling_frequency()
        raise ValueError(f"unknown voice {voice}")

    def _kokoro_engine(self):
        if self._kokoro is None:
            from kokoro_onnx import Kokoro
            from misaki import ja

            model = self.models_dir / KOKORO_MODEL
            if not model.exists():
                raise SystemExit(f"{model} missing: run tools/test-audio/download-models.sh")
            self._kokoro = Kokoro(str(model), str(self.models_dir / KOKORO_VOICES_FILE))
            self._g2p = ja.JAG2P()
        return self._kokoro, self._g2p

    def _hts_engine(self, voice: str):
        if voice not in self._hts:
            import pyopenjtalk
            from pyopenjtalk.htsengine import HTSEngine

            if voice == "ojt_mei":
                path = pyopenjtalk.DEFAULT_HTS_VOICE
            else:
                model = self.models_dir / M001_VOICE
                if not model.exists():
                    raise SystemExit(f"{model} missing: run tools/test-audio/download-models.sh")
                path = str(model).encode()
            self._hts[voice] = HTSEngine(path)
        return self._hts[voice]

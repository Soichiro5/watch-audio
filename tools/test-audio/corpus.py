"""Parser for the conversation corpus (tools/test-audio/corpus/*.txt).

File format::

    # title: 週次定例ミーティング
    # scene: office            (home | office | cafe | shop | outdoor | phone)
    # speakers: A=jf_alpha B=jm_kumo C=ojt_m001:phone
    A: こんにちは。{PDF|ピーディーエフ}で送ります。

Speaker A is always the person wearing the watch (closest to the microphone). A `:phone` suffix
marks the far end of a phone call. `{text|reading}` gives the reading the TTS should speak for
text it cannot read itself (Latin letters, unusual numbers); the ground truth keeps `text`.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

RUBY = re.compile(r"\{([^|{}]+)\|([^{}]+)\}")
SENTENCE_END = re.compile(r"(?<=[。？！?!])")
SCENES = {"home", "office", "cafe", "shop", "outdoor", "phone"}


@dataclass(frozen=True)
class Speaker:
    id: str
    voice: str
    phone: bool


@dataclass(frozen=True)
class Line:
    index: int
    speaker: str
    text: str  # ground truth
    reading: str  # what the TTS speaks

    def sentences(self) -> list[str]:
        return [s.strip() for s in SENTENCE_END.split(self.reading) if s.strip()]


@dataclass(frozen=True)
class Conversation:
    id: str
    title: str
    scene: str
    speakers: dict[str, Speaker]
    lines: list[Line]


def parse_line_text(raw: str) -> tuple[str, str]:
    """Returns (ground truth, TTS reading) for a line with optional `{text|reading}` ruby."""
    return RUBY.sub(r"\1", raw), RUBY.sub(r"\2", raw)


def load_conversation(path: Path, known_voices: set[str]) -> Conversation:
    header: dict[str, str] = {}
    lines: list[Line] = []
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        raw = raw.strip()
        if not raw:
            continue
        if raw.startswith("#"):
            key, _, value = raw[1:].partition(":")
            header[key.strip()] = value.strip()
            continue
        speaker, sep, body = raw.partition(":")
        if not sep or not body.strip():
            raise ValueError(f"{path}:{number}: expected '<speaker>: <text>'")
        text, reading = parse_line_text(body.strip())
        if re.search(r"[A-Za-z]", reading):
            raise ValueError(f"{path}:{number}: Latin letters need a reading: {{text|よみ}}")
        lines.append(Line(len(lines), speaker.strip(), text, reading))
    speakers = {}
    for item in header.get("speakers", "").split():
        sid, _, spec = item.partition("=")
        voice, _, channel = spec.partition(":")
        if voice not in known_voices:
            raise ValueError(f"{path}: unknown voice {voice}")
        speakers[sid] = Speaker(sid, voice, channel == "phone")
    scene = header.get("scene", "")
    if scene not in SCENES:
        raise ValueError(f"{path}: scene must be one of {sorted(SCENES)}")
    if "A" not in speakers or speakers["A"].phone:
        raise ValueError(f"{path}: speaker A (the watch wearer) must be in the room")
    for line in lines:
        if line.speaker not in speakers:
            raise ValueError(f"{path}: undeclared speaker {line.speaker}")
    return Conversation(path.stem, header.get("title", path.stem), scene, speakers, lines)


def load_corpus(directory: Path, known_voices: set[str]) -> list[Conversation]:
    return [load_conversation(p, known_voices) for p in sorted(directory.glob("*.txt"))]

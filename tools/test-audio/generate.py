#!/usr/bin/env python3
"""Builds the watch-like Japanese conversation test set (issue #56).

    corpus/*.txt ──TTS (offline)──▶ per-utterance speech ──layout──▶ conversations
        ──per condition: distance/reverb, ambient noise, cloth rub, mic floor──▶ <id>.flac
        ──AAC-LC 24 kbps (like the watch)──▶ <id>.m4a        + manifest.json / segments.csv

Usage (from the repository root):
    tools/test-audio/.venv/bin/python tools/test-audio/generate.py            # everything
    tools/test-audio/.venv/bin/python tools/test-audio/generate.py --only clean,cafe --no-day
    tools/test-audio/.venv/bin/python tools/test-audio/generate.py \
        --reazon-manifest spikes/asr-benchmark/data/manifest.jsonl          # + ReazonSpeech
    tools/test-audio/.venv/bin/python tools/test-audio/generate.py --fixtures tools/test-audio/fixtures

Nothing here talks to the network: the socket layer is disabled before any work starts.
"""
from __future__ import annotations

import argparse
import csv
import json
import socket
import subprocess
import sys
import time
import zlib
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import soundfile as sf

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import effects as fx  # noqa: E402
from corpus import Conversation, load_corpus  # noqa: E402
from tts import SPEECH_LEVEL_DB, VOICES, Synthesizer  # noqa: E402

SR = fx.SAMPLE_RATE


def _no_network(*_args, **_kwargs):
    raise RuntimeError("network access is disabled in the test-audio generator (local only)")


socket.socket.connect = _no_network  # type: ignore[method-assign]
socket.create_connection = _no_network  # type: ignore[assignment]

# Microphone self-noise, dBFS RMS. Well below the watch's silent-chunk threshold (RMS 0.003).
MIC_FLOOR_DB = -70.0

# Mixing levels (dB relative to SPEECH_LEVEL_DB) and rooms per talker role.
#  wearer: speaker A, the person wearing the watch; partner: others in the room;
#  phone: the far end of a call, heard through the phone.
CLOSE_GAINS = {"wearer": 0.0, "partner": -3.0, "phone": -8.0}
WRIST_GAINS = {"wearer": -6.0, "partner": -14.0, "phone": -16.0}
WRIST_ROOMS = {"wearer": (0.30, 8.0), "partner": (0.50, 1.0), "phone": (0.30, 3.0)}  # (RT60 s, DRR dB)

SNR_DB = {"home": 25.0, "office": 15.0, "shop": 12.0, "cafe": 10.0, "outdoor": 8.0}
DAY_RUB_PER_MIN = 1.5


@dataclass(frozen=True)
class Condition:
    id: str
    description: str
    wrist: bool = True
    environment: str | None = None
    rub_per_min: float = 0.0

    @property
    def snr_db(self) -> float | None:
        return SNR_DB[self.environment] if self.environment else None


CONDITIONS = [
    Condition("clean", "近接・無雑音（基準）", wrist=False),
    Condition("wrist", "腕の位置: 距離による減衰＋残響"),
    Condition("office", "wrist ＋ オフィス雑音（空調・キーボード・遠くの話し声）", environment="office"),
    Condition("cafe", "wrist ＋ カフェ雑音（多人数のざわめき・食器）", environment="cafe"),
    Condition("outdoor", "wrist ＋ 屋外雑音（車の通過・風）", environment="outdoor"),
    Condition("rub", "wrist ＋ 腕の動きによる擦れ音（平均 6 回/分）", rub_per_min=6.0),
]


@dataclass
class Placement:
    start: int  # samples from the start of the block
    audio: np.ndarray
    role: str
    group: str  # conversation id: the unit CER is aligned over
    speaker: str
    voice: str
    text: str


@dataclass
class Block:
    length: int
    placements: list[Placement] = field(default_factory=list)
    wrist: bool = True
    environment: str | None = None
    rub_per_min: float = 0.0
    label: str = ""


def role_of(conv: Conversation, speaker: str) -> str:
    if conv.speakers[speaker].phone:
        return "phone"
    return "wearer" if speaker == "A" else "partner"


def layout(conv: Conversation, synth: Synthesizer, rng: np.random.Generator) -> tuple[list[Placement], int]:
    """Places the turns of a conversation on a timeline: short gaps, occasional long pauses,
    and backchannels (相づち) that overlap the end of the previous turn."""
    speeds = {sid: float(rng.uniform(0.95, 1.1)) for sid in conv.speakers}
    placements: list[Placement] = []
    prev_start = prev_end = 0
    prev_speaker = None
    for line in conv.lines:
        sp = conv.speakers[line.speaker]
        parts = [synth.say(sp.voice, s, speeds[sp.id]) for s in line.sentences()]
        pieces = []
        for i, part in enumerate(parts):
            if i:
                pieces.append(np.zeros(int(rng.uniform(0.25, 0.5) * SR), dtype=np.float32))
            pieces.append(part)
        audio = np.concatenate(pieces) * fx.db_to_gain(rng.uniform(-2, 2))
        if prev_speaker is None:
            start = 0
        elif line.speaker == prev_speaker:
            start = prev_end + int(rng.uniform(0.5, 1.0) * SR)
        elif len(line.text) <= 6 and rng.random() < 0.35:
            start = max(prev_start + int(0.3 * SR), prev_end - int(rng.uniform(0.15, 0.5) * SR))
        elif rng.random() < 0.08:
            start = prev_end + int(rng.uniform(1.5, 3.0) * SR)
        else:
            start = prev_end + int(np.clip(rng.normal(0.35, 0.2), 0.08, 1.2) * SR)
        placements.append(Placement(start, audio.astype(np.float32), role_of(conv, line.speaker), conv.id,
                                    f"{conv.id}:{line.speaker}", sp.voice, line.text))
        prev_start, prev_end, prev_speaker = start, max(prev_end, start + audio.size), line.speaker
    return placements, prev_end


def shifted(placements: list[Placement], offset: int) -> list[Placement]:
    return [Placement(p.start + offset, p.audio, p.role, p.group, p.speaker, p.voice, p.text) for p in placements]


def sequence(convs: list[tuple[list[Placement], int]], rng: np.random.Generator, lead_s=2.0, gap_s=(4.0, 8.0)) -> Block:
    """All conversations one after another with a few seconds of ambience in between."""
    block = Block(length=0)
    t = int(lead_s * SR)
    for i, (placements, length) in enumerate(convs):
        if i:
            t += int(rng.uniform(*gap_s) * SR)
        block.placements += shifted(placements, t)
        t += length
    block.length = t + int(lead_s * SR)
    return block


def render(block: Block, rng: np.random.Generator, babble_source: list[np.ndarray]) -> np.ndarray:
    n = block.length
    tracks = {role: np.zeros(n, dtype=np.float32) for role in ("wearer", "partner", "phone")}
    for p in block.placements:
        end = min(n, p.start + p.audio.size)
        tracks[p.role][p.start:end] += p.audio[: end - p.start]
    tracks["phone"] = fx.phone_channel(tracks["phone"])
    gains = WRIST_GAINS if block.wrist else CLOSE_GAINS
    y = np.zeros(n, dtype=np.float32)
    for role, track in tracks.items():
        track = track * fx.db_to_gain(gains[role])
        if block.wrist:
            track = fx.reverb(track, rng, *WRIST_ROOMS[role])
        y += track
    reference_db = SPEECH_LEVEL_DB + gains["wearer"]
    if block.environment:
        noise = fx.environment_noise(block.environment, n, rng, babble_source)
        y += noise * fx.db_to_gain(reference_db - SNR_DB[block.environment])
    if block.rub_per_min:
        y += fx.cloth_rub(n, rng, block.rub_per_min, (reference_db - 6, reference_db + 6))
    y += fx.white(n, rng) * fx.db_to_gain(MIC_FLOOR_DB)
    return fx.soft_clip(fx.highpass(y, 60))


def write_audio(samples: np.ndarray, out_dir: Path, name: str, flac: bool) -> dict[str, str]:
    """Writes the microphone signal (FLAC) and the watch's AAC-LC 24 kbps .m4a."""
    files = {}
    if flac:
        sf.write(out_dir / f"{name}.flac", samples, SR, subtype="PCM_16")
        files["audio"] = f"{name}.flac"
    m4a = out_dir / f"{name}.m4a"
    subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-f", "f32le", "-ac", "1", "-ar", str(SR),
         "-i", "pipe:", "-c:a", "aac", "-b:a", "24000", "-movflags", "+faststart", str(m4a)],
        input=samples.astype("<f4").tobytes(), check=True)
    files["watchAudio"] = m4a.name
    files.setdefault("audio", m4a.name)
    return files


def segments_of(block: Block, offset: int = 0) -> list[dict]:
    return [
        dict(startMs=round((p.start + offset) * 1000 / SR), endMs=round((p.start + offset + p.audio.size) * 1000 / SR),
             speaker=p.speaker, role=p.role, voice=p.voice, group=p.group, text=p.text)
        for p in sorted(block.placements, key=lambda p: p.start)
    ]


def stable_rng(seed: int, name: str) -> np.random.Generator:
    return np.random.default_rng([seed, zlib.crc32(name.encode())])


def build_recording(rec_id: str, description: str, blocks: list[Block], seed: int, babble: list[np.ndarray],
                    out_dir: Path, flac: bool = True, extra: dict | None = None) -> dict:
    started = time.time()
    rng = stable_rng(seed, rec_id)
    rendered, segments, offset = [], [], 0
    fade = int(0.02 * SR)
    for block in blocks:
        audio = render(block, rng, babble)
        audio[:fade] *= np.linspace(0, 1, fade, dtype=np.float32)
        audio[-fade:] *= np.linspace(1, 0, fade, dtype=np.float32)
        rendered.append(audio)
        segments += segments_of(block, offset)
        offset += block.length
    samples = np.concatenate(rendered)
    files = write_audio(samples, out_dir, rec_id, flac)
    speech_ms = sum(s["endMs"] - s["startMs"] for s in segments)
    print(f"  {rec_id}: {samples.size / SR / 60:.1f} min, {len(segments)} utterances, "
          f"speech {speech_ms / 60000:.1f} min ({time.time() - started:.0f}s)", flush=True)
    return dict(id=rec_id, description=description, **files, durationMs=round(samples.size * 1000 / SR),
                **(extra or {}), segments=segments)


def day_blocks(by_id: dict[str, tuple[list[Placement], int]]) -> list[Block]:
    """About one hour of a wearer's day: conversations in their scenes, ambience without speech
    and long quiet stretches (some long enough for the watch to drop a whole chunk as silent)."""
    def conv(cid: str, env: str) -> Block:
        placements, length = by_id[cid]
        lead = int(1.5 * SR)
        return Block(length + 2 * lead, shifted(placements, lead), True, env, DAY_RUB_PER_MIN, cid)

    def ambient(env: str | None, seconds: float, rub: float = DAY_RUB_PER_MIN) -> Block:
        return Block(int(seconds * SR), [], True, env, rub if env else 0.0, env or "quiet")

    return [
        ambient(None, 60),
        conv("02_family_breakfast", "home"),
        ambient("home", 90),
        ambient("outdoor", 300, rub=4.0),  # commute: walking, no conversation
        conv("01_weekly_meeting", "office"),
        ambient("office", 150),
        conv("06_one_on_one", "office"),
        ambient(None, 900),  # desk work: long silence
        conv("13_coworkers_lunch", "cafe"),
        ambient("outdoor", 120),
        conv("04_phone_dentist", "office"),
        conv("10_design_review", "office"),
        ambient("office", 300),
        conv("12_friends_walk", "outdoor"),
        conv("03_supermarket", "shop"),
        ambient("outdoor", 150),
        conv("14_phone_mother", "home"),
        conv("16_homework", "home"),
        ambient(None, 420),
    ]


def reazon_blocks(manifest: Path, rng: np.random.Generator, wrist: bool, environment: str | None) -> Block:
    """ReazonSpeech held-out test utterances (prepared by spikes/asr-benchmark) separated by
    silence. Each utterance is its own alignment group."""
    root = manifest.parent.parent
    block = Block(length=int(2 * SR), wrist=wrist, environment=environment)
    for row in map(json.loads, manifest.read_text(encoding="utf-8").splitlines()):
        if row.get("dataset") != "reazon_ja":
            continue
        audio, rate = sf.read(root / row["path"].format(cond="raw"), dtype="float32")
        assert rate == SR, rate
        audio = fx.normalize_active(audio, SPEECH_LEVEL_DB)
        role = "partner" if wrist else "wearer"
        block.placements.append(Placement(block.length, audio, role, row["id"], "reazon", "reazonspeech", row["ref"]))
        block.length += audio.size + int(rng.uniform(2.5, 4.0) * SR)
    return block


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=Path, default=HERE / "cache" / "out")
    ap.add_argument("--models", type=Path, default=HERE / "cache" / "models")
    ap.add_argument("--corpus", type=Path, default=HERE / "corpus")
    ap.add_argument("--seed", type=int, default=56)
    ap.add_argument("--only", help="comma-separated condition ids (default: all)")
    ap.add_argument("--no-day", action="store_true", help="skip the one-hour 'part of a day' recording")
    ap.add_argument("--reazon-manifest", type=Path, help="spikes/asr-benchmark/data/manifest.jsonl (optional)")
    ap.add_argument("--fixtures", type=Path, help="write the small committed sample set here instead")
    args = ap.parse_args()
    if args.reazon_manifest and not args.reazon_manifest.exists():
        ap.error(f"{args.reazon_manifest} not found (run spikes/asr-benchmark/scripts/prepare_data.py first)")

    synth = Synthesizer(args.models, HERE / "cache" / "tts")
    convs = load_corpus(args.corpus, VOICES)
    print(f"corpus: {len(convs)} conversations, {sum(len(c.lines) for c in convs)} lines", flush=True)

    started = time.time()
    layout_rng = stable_rng(args.seed, "layout")
    laid_out = {c.id: layout(c, synth, layout_rng) for c in convs}
    print(f"tts + layout: {time.time() - started:.0f}s", flush=True)
    babble = [p.audio for placements, _ in laid_out.values() for p in placements if p.audio.size > SR]

    if args.fixtures:
        write_fixtures(args, convs, synth, babble)
        return

    args.out.mkdir(parents=True, exist_ok=True)
    wanted = set(args.only.split(",")) if args.only else None
    recordings = []
    order_rng = stable_rng(args.seed, "order")
    all_convs = sequence([laid_out[c.id] for c in convs], order_rng)
    for cond in CONDITIONS:
        if wanted and cond.id not in wanted:
            continue
        block = Block(all_convs.length, all_convs.placements, cond.wrist, cond.environment, cond.rub_per_min, cond.id)
        recordings.append(build_recording(cond.id, cond.description, [block], args.seed, babble, args.out,
                                          extra=dict(condition=cond.id, snrDb=cond.snr_db)))
    if not args.no_day and (not wanted or "day" in wanted):
        recordings.append(build_recording("day", "1 日の一部（約 1 時間）: 場面ごとの雑音・擦れ音・長い無音を含む",
                                          day_blocks(laid_out), args.seed, babble, args.out,
                                          extra=dict(condition="day")))
    if args.reazon_manifest:
        rrng = stable_rng(args.seed, "reazon")
        for rec_id, wrist, env, desc in [
            ("reazon_clean", False, None, "ReazonSpeech テスト（テレビ音声）150 文をそのまま"),
            ("reazon_cafe", True, "cafe", "ReazonSpeech テスト 150 文 ＋ wrist ＋ カフェ雑音"),
        ]:
            if wanted and rec_id not in wanted:
                continue
            block = reazon_blocks(args.reazon_manifest, rrng, wrist, env)
            recordings.append(build_recording(rec_id, desc, [block], args.seed, babble, args.out,
                                              extra=dict(condition=rec_id, snrDb=SNR_DB.get(env or ""))))

    manifest_path = args.out / "manifest.json"
    previous = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {}
    merged = {r["id"]: r for r in previous.get("recordings", [])}
    merged.update({r["id"]: r for r in recordings})
    write_manifest(args.out, list(merged.values()), args.seed)
    print(f"done in {time.time() - started:.0f}s -> {manifest_path}")


def write_manifest(out: Path, recordings: list[dict], seed: int) -> None:
    order = {c.id: i for i, c in enumerate(CONDITIONS)} | {"day": 90, "reazon_clean": 91, "reazon_cafe": 92}
    recordings.sort(key=lambda r: order.get(r["id"], 99))
    manifest = dict(version=1, sampleRate=SR, seed=seed, generator="tools/test-audio/generate.py",
                    recordings=recordings)
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    with open(out / "segments.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["recording", "startMs", "endMs", "speaker", "role", "voice", "group", "text"])
        for r in recordings:
            for s in r["segments"]:
                w.writerow([r["id"], s["startMs"], s["endMs"], s["speaker"], s["role"], s["voice"], s["group"], s["text"]])


def write_fixtures(args, convs: list[Conversation], synth: Synthesizer, babble: list[np.ndarray]) -> None:
    """A ~1 minute excerpt in three conditions, AAC only, small enough to commit."""
    out: Path = args.fixtures
    out.mkdir(parents=True, exist_ok=True)
    first = convs[0]
    excerpt = Conversation(first.id, first.title, first.scene, first.speakers, first.lines[:14])
    placements, length = layout(excerpt, synth, stable_rng(args.seed, "fixture"))
    recordings = []
    for cond in (c for c in CONDITIONS if c.id in ("clean", "cafe", "rub")):
        block = Block(length + 2 * SR, shifted(placements, SR), cond.wrist, cond.environment, cond.rub_per_min)
        recordings.append(build_recording(f"sample_{cond.id}", cond.description, [block], args.seed, babble, out,
                                          flac=False, extra=dict(condition=cond.id, snrDb=cond.snr_db)))
    write_manifest(out, recordings, args.seed)


if __name__ == "__main__":
    main()

"""Signal processing for the watch-like test audio: levels, room/distance, phone channel and
procedurally generated noises. Everything is synthesized here (no third-party noise recordings),
so the noise has no licensing constraints. All signals are float32 mono at SAMPLE_RATE."""
from __future__ import annotations

import numpy as np
from scipy import signal

SAMPLE_RATE = 16_000


def db_to_gain(db: float) -> float:
    return float(10.0 ** (db / 20.0))


def rms(x: np.ndarray) -> float:
    return float(np.sqrt(np.mean(np.square(x, dtype=np.float64)))) if x.size else 0.0


def active_rms(x: np.ndarray, frame: int = 320, floor_db: float = -40.0) -> float:
    """RMS over the frames that are within `floor_db` of the loudest frame (speech-active level)."""
    n = x.size // frame
    if n == 0:
        return rms(x)
    energy = np.mean(np.square(x[: n * frame].reshape(n, frame), dtype=np.float64), axis=1)
    keep = energy > energy.max() * 10 ** (floor_db / 10)
    return float(np.sqrt(energy[keep].mean()))


def normalize_active(x: np.ndarray, target_db: float) -> np.ndarray:
    level = active_rms(x)
    return x if level == 0 else (x * (db_to_gain(target_db) / level)).astype(np.float32)


def trim_silence(x: np.ndarray, rel_db: float = -40.0, margin_s: float = 0.02) -> np.ndarray:
    """Cuts leading/trailing silence (relative to the peak 10 ms frame), keeping a small margin."""
    frame = SAMPLE_RATE // 100
    n = x.size // frame
    if n == 0:
        return x
    energy = np.mean(np.square(x[: n * frame].reshape(n, frame), dtype=np.float64), axis=1)
    active = np.flatnonzero(energy > energy.max() * 10 ** (rel_db / 10))
    margin = int(margin_s * SAMPLE_RATE)
    start = max(0, active[0] * frame - margin)
    end = min(x.size, (active[-1] + 1) * frame + margin)
    return x[start:end]


def _sos(kind: str, freq, order: int = 4):
    return signal.butter(order, freq, btype=kind, fs=SAMPLE_RATE, output="sos")


def highpass(x: np.ndarray, hz: float, order: int = 2) -> np.ndarray:
    return signal.sosfilt(_sos("highpass", hz, order), x).astype(np.float32)


def lowpass(x: np.ndarray, hz: float, order: int = 4) -> np.ndarray:
    return signal.sosfilt(_sos("lowpass", hz, order), x).astype(np.float32)


def bandpass(x: np.ndarray, lo: float, hi: float, order: int = 4) -> np.ndarray:
    return signal.sosfilt(_sos("bandpass", [lo, hi], order), x).astype(np.float32)


def room_impulse(rng: np.random.Generator, rt60: float, drr_db: float) -> np.ndarray:
    """Synthetic room impulse response: a direct path plus exponentially decaying diffuse noise
    whose energy relative to the direct path is set by the direct-to-reverberant ratio."""
    n = int(rt60 * SAMPLE_RATE)
    t = np.arange(n) / SAMPLE_RATE
    tail = rng.standard_normal(n) * np.exp(-6.9 * t / rt60)
    tail[: int(0.003 * SAMPLE_RATE)] = 0.0  # first reflections arrive a few ms after the direct sound
    tail = lowpass(tail.astype(np.float32), 5000, order=2)  # air/wall absorption of highs
    tail *= np.sqrt(10 ** (-drr_db / 10) / np.sum(np.square(tail, dtype=np.float64)))
    tail[0] += 1.0
    return tail.astype(np.float32)


def reverb(x: np.ndarray, rng: np.random.Generator, rt60: float, drr_db: float) -> np.ndarray:
    if not np.any(x):
        return x
    return signal.oaconvolve(x, room_impulse(rng, rt60, drr_db))[: x.size].astype(np.float32)


def phone_channel(x: np.ndarray) -> np.ndarray:
    """The far end of a call heard from the phone's earpiece/speaker: narrowband and slightly
    distorted."""
    y = bandpass(x, 300, 3400)
    return (np.tanh(3.0 * y) / 3.0).astype(np.float32)


def soft_clip(x: np.ndarray, knee: float = 0.9) -> np.ndarray:
    a = np.abs(x)
    over = a > knee
    if np.any(over):
        x = x.copy()
        x[over] = np.sign(x[over]) * (knee + (1 - knee) * np.tanh((a[over] - knee) / (1 - knee)))
    return x


# --- noises (unit-ish level; callers scale them to an SNR) ---------------------------------

def white(n: int, rng: np.random.Generator) -> np.ndarray:
    return rng.standard_normal(n).astype(np.float32)


def pink(n: int, rng: np.random.Generator) -> np.ndarray:
    # Paul Kellet's economy pink filter approximated by a 3-pole IIR.
    b = [0.049922035, -0.095993537, 0.050612699, -0.004408786]
    a = [1, -2.494956002, 2.017265875, -0.522189400]
    y = signal.lfilter(b, a, rng.standard_normal(n))
    return (y / (rms(y) or 1)).astype(np.float32)


def brown(n: int, rng: np.random.Generator) -> np.ndarray:
    y = signal.lfilter([1.0], [1.0, -0.995], rng.standard_normal(n))
    y = highpass(y.astype(np.float32), 20)
    return (y / (rms(y) or 1)).astype(np.float32)


def _envelope(n: int, attack: int, release: int) -> np.ndarray:
    env = np.ones(n, dtype=np.float32)
    attack, release = min(attack, n // 2), min(release, n // 2)
    if attack:
        env[:attack] = np.linspace(0, 1, attack)
    if release:
        env[-release:] *= np.linspace(1, 0, release)
    return env


def _add(dst: np.ndarray, at: int, src: np.ndarray) -> None:
    if at >= dst.size:
        return
    end = min(dst.size, at + src.size)
    dst[at:end] += src[: end - at]


def keyboard(n: int, rng: np.random.Generator) -> np.ndarray:
    """Bursts of typing: short filtered clicks at 5-9 keys/s, with pauses between bursts."""
    out = np.zeros(n, dtype=np.float32)
    t = int(rng.uniform(0, 5) * SAMPLE_RATE)
    click_len = int(0.012 * SAMPLE_RATE)
    decay = np.exp(-np.arange(click_len) / (0.002 * SAMPLE_RATE)).astype(np.float32)
    while t < n:
        burst_end = t + int(rng.uniform(1.5, 8) * SAMPLE_RATE)
        while t < min(burst_end, n):
            click = bandpass(rng.standard_normal(click_len).astype(np.float32), 1500, 6000, 2) * decay
            _add(out, t, click * rng.uniform(0.3, 1.0))
            t += int(SAMPLE_RATE / rng.uniform(5, 9))
        t += int(rng.uniform(3, 20) * SAMPLE_RATE)
    return out


def clinks(n: int, rng: np.random.Generator, rate_per_min: float) -> np.ndarray:
    """Dishes and cutlery: sparse damped inharmonic partials."""
    out = np.zeros(n, dtype=np.float32)
    length = int(0.4 * SAMPLE_RATE)
    tt = np.arange(length) / SAMPLE_RATE
    t = 0
    while True:
        t += int(rng.exponential(60 / rate_per_min) * SAMPLE_RATE)
        if t >= n:
            return out
        f0 = rng.uniform(1800, 4200)
        tone = sum(np.sin(2 * np.pi * f0 * r * tt) / (k + 1) for k, r in enumerate((1.0, 2.32, 4.25)))
        _add(out, t, (tone * np.exp(-tt / rng.uniform(0.03, 0.12)) * rng.uniform(0.2, 1.0)).astype(np.float32))


def beeps(n: int, rng: np.random.Generator, rate_per_min: float) -> np.ndarray:
    """Checkout scanner beeps."""
    out = np.zeros(n, dtype=np.float32)
    tt = np.arange(int(0.09 * SAMPLE_RATE)) / SAMPLE_RATE
    beep = (np.sin(2 * np.pi * 2900 * tt) * _envelope(tt.size, 40, 120)).astype(np.float32)
    t = 0
    while True:
        t += int(rng.exponential(60 / rate_per_min) * SAMPLE_RATE)
        if t >= n:
            return out
        _add(out, t, beep * 0.5)


def passing_cars(n: int, rng: np.random.Generator) -> np.ndarray:
    out = np.zeros(n, dtype=np.float32)
    t = 0
    while True:
        t += int(rng.uniform(4, 18) * SAMPLE_RATE)
        if t >= n:
            return out
        length = int(rng.uniform(3, 8) * SAMPLE_RATE)
        car = bandpass(rng.standard_normal(length).astype(np.float32), 80, rng.uniform(1200, 2500), 2)
        env = np.hanning(length).astype(np.float32) ** 2
        _add(out, t, car / (rms(car) or 1) * env * rng.uniform(0.5, 1.5))


def wind(n: int, rng: np.random.Generator) -> np.ndarray:
    """Wind on the microphone: low-frequency rumble with slow gusts."""
    rumble = lowpass(rng.standard_normal(n).astype(np.float32), 250, 2)
    gusts = lowpass(rng.standard_normal(n).astype(np.float32), 0.3, 2)
    gusts = np.clip(gusts / (np.std(gusts) or 1) * 0.6 + 0.5, 0, None)
    y = rumble * gusts
    return (y / (rms(y) or 1)).astype(np.float32)


def hum(n: int, mains_hz: float = 50.0) -> np.ndarray:
    tt = np.arange(n) / SAMPLE_RATE
    return (np.sin(2 * np.pi * mains_hz * 2 * tt) + 0.5 * np.sin(2 * np.pi * mains_hz * 4 * tt)).astype(np.float32)


def babble(n: int, rng: np.random.Generator, utterances: list[np.ndarray], talkers: int = 6) -> np.ndarray:
    """Distant crowd: several overlapping talker streams built from (other) synthesized
    utterances, reverberant and dull so that words are hard to make out."""
    out = np.zeros(n, dtype=np.float32)
    for _ in range(talkers):
        stream = np.zeros(n, dtype=np.float32)
        t = int(rng.uniform(0, 3) * SAMPLE_RATE)
        while t < n:
            u = utterances[rng.integers(len(utterances))]
            _add(stream, t, u * db_to_gain(rng.uniform(-6, 0)))
            t += u.size + int(rng.uniform(0.2, 1.5) * SAMPLE_RATE)
        out += reverb(stream, rng, rt60=0.9, drr_db=-6)
    out = lowpass(out, 3500, 2)
    return (out / (rms(out) or 1)).astype(np.float32)


def cloth_rub(n: int, rng: np.random.Generator, rate_per_min: float, level_db: tuple[float, float]) -> np.ndarray:
    """Sleeve/strap friction and arm movement against the watch body: bursts of crackly
    band-limited noise with a low thump, at `level_db` (dBFS RMS of each burst)."""
    out = np.zeros(n, dtype=np.float32)
    t = 0
    while True:
        t += int(rng.exponential(60 / rate_per_min) * SAMPLE_RATE)
        if t >= n:
            return out
        length = int(rng.uniform(0.25, 1.8) * SAMPLE_RATE)
        rustle = bandpass(rng.standard_normal(length).astype(np.float32), 400, 6500, 2)
        crackle = np.abs(lowpass(rng.standard_normal(length).astype(np.float32), 60, 2))
        crackle = crackle / (crackle.max() or 1)
        burst = rustle * crackle ** 1.5
        thump_len = min(length, int(0.08 * SAMPLE_RATE))
        thump = lowpass(rng.standard_normal(thump_len).astype(np.float32), 150, 2) * 4
        burst[:thump_len] += thump * np.exp(-np.arange(thump_len) / (0.02 * SAMPLE_RATE))
        burst *= _envelope(length, int(0.03 * SAMPLE_RATE), int(0.1 * SAMPLE_RATE))
        burst = burst / (rms(burst) or 1) * db_to_gain(rng.uniform(*level_db))
        _add(out, t, burst.astype(np.float32))


def environment_noise(kind: str, n: int, rng: np.random.Generator, babble_source: list[np.ndarray]) -> np.ndarray:
    """Ambient noise of a scene, normalized to RMS 1."""
    if kind == "office":
        y = pink(n, rng) * 0.6 + lowpass(brown(n, rng), 400) * 0.5  # HVAC
        y += keyboard(n, rng) * 3.0
        y += babble(n, rng, babble_source, talkers=3) * 0.35  # colleagues further away
    elif kind == "cafe":
        y = babble(n, rng, babble_source, talkers=8) + clinks(n, rng, 20) * 0.6 + pink(n, rng) * 0.25
    elif kind == "shop":
        y = babble(n, rng, babble_source, talkers=5) * 0.8 + beeps(n, rng, 12) * 0.6 + pink(n, rng) * 0.3
    elif kind == "outdoor":
        y = brown(n, rng) * 0.6 + passing_cars(n, rng) * 0.8 + wind(n, rng) * 0.6
    elif kind == "home":
        y = pink(n, rng) * 0.4 + hum(n) * 0.15 + clinks(n, rng, 4) * 0.5
    else:
        raise ValueError(f"unknown environment {kind}")
    y = highpass(y.astype(np.float32), 40)
    return (y / (rms(y) or 1)).astype(np.float32)

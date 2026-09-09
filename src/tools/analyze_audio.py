"""Bounded signal/spectrum evidence, never a substitute for listening or creative approval."""
import hashlib
import json
from pathlib import Path
import sys
import wave

import numpy as np


def analyze(path):
    with wave.open(str(path)) as wav:
        if wav.getnchannels() != 2 or wav.getsampwidth() != 2 or wav.getframerate() != 48000 or wav.getnframes() > 48000 * 210:
            raise ValueError("Expected bounded stereo PCM48k/16")
        samples = np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2").reshape(-1, 2).astype(np.float32) / 32768
    bands = [(20, 250), (250, 2000), (2000, 8000), (8000, 20000)]
    power = np.zeros(len(bands))
    envelope = []
    window = np.hanning(8192)
    frequency = np.fft.rfftfreq(8192, 1 / 48000)
    for offset in range(0, len(samples) - 48000, 48000):
        second = samples[offset:offset + 48000]
        envelope.append(float(np.sqrt(np.mean(second ** 2))))
        mono = np.mean(second[20000:28192], axis=1)
        spectrum = np.abs(np.fft.rfft(mono * window)) ** 2
        for i, (low, high) in enumerate(bands):
            power[i] += float(spectrum[(frequency >= low) & (frequency < high)].sum())
    return dict(path=str(path.resolve()), sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
        seconds=len(samples) / 48000, peak=float(np.max(np.abs(samples))), rms=float(np.sqrt(np.mean(samples ** 2))),
        channelDcMeans=[float(x) for x in np.mean(samples, axis=0, dtype=np.float64)],
        stereoCorrelation=float(np.corrcoef(samples[::16].T)[0, 1]),
        loopSeamSampleJump=[float(x) for x in np.abs(samples[0] - samples[-1])],
        oneSecondRmsMinimum=min(envelope), oneSecondRmsMaximum=max(envelope),
        bandPowerFraction={f"{low}-{high}Hz": float(value / power.sum()) for (low, high), value in zip(bands, power)},
        interpretation="Measures real PCM spectrum, dynamics and boundary only. Does not prove guitar timbre, pleasantness or stylistic fit.",
        listeningStatus="NOT_AUDITIONED_BY_AUDIO_CAPABLE_TOOL; OWNER_REVIEW_REQUIRED")


if __name__ == "__main__":
    path = Path(sys.argv[1] if len(sys.argv) > 1 else "build/generated-resources/audio/metalmania.wav")
    result = analyze(path)
    target = Path("build/reports/audio-review.json")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))

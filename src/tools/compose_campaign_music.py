"""Author original campaign scores offline. Requires Python 3 + NumPy only; never called by a build.

No recordings, soundfonts, MIDI files or borrowed melodies are inputs. Each arena has a
48-bar through-arranged score, synthesized percussion, bass, doubled power chords and
its own lead timbre. Normal/boss renders share the same clock and arrangement.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import wave

import numpy as np

RATE = 48_000
BARS = 48
SEED = 0x575245434B524946
THEMES = [
    dict(id="construction_17", title="Rebar Oath", bpm=126, root=38, color="steel",
         motif=[0, 0, 3, 0, 7, 6, 3, 1], progression=[0, 0, 3, 1, 0, 5, 3, 1],
         kick=[0, 3, 6, 8, 10], lead=[12, 15, 19, 18, 15, 13, 12, 7]),
    dict(id="neon_zero", title="Last Service Lane", bpm=138, root=40, color="neon",
         motif=[0, 7, 0, 10, 0, 3, 5, 7], progression=[0, 0, 5, 3, 0, 7, 5, 3],
         kick=[0, 4, 7, 8, 12, 14], lead=[19, 15, 12, 10, 12, 17, 15, 14]),
    dict(id="euphoria_park", title="Crooked Midway", bpm=144, root=45, color="carnival",
         motif=[0, 3, 7, 6, 0, 10, 7, 3], progression=[0, 0, 5, 1, 0, 8, 7, 1],
         kick=[0, 3, 8, 11, 14], lead=[12, 15, 19, 18, 19, 22, 19, 13]),
    dict(id="ash_necropolis", title="Cinders Remember", bpm=108, root=36, color="funeral",
         motif=[0, 0, 7, 0, 1, 0, 5, 3], progression=[0, 0, 1, 1, 5, 3, 1, 0],
         kick=[0, 6, 8, 11], lead=[12, 13, 19, 17, 15, 13, 12, 7]),
    dict(id="doomsday_arena", title="The Last Broadcast", bpm=132, root=38, color="finale",
         motif=[0, 3, 0, 7, 1, 6, 5, 0], progression=[0, 3, 5, 1, 0, 8, 7, 1],
         kick=[0, 2, 6, 8, 10, 14], lead=[12, 15, 19, 18, 17, 13, 12, 7]),
]


def lowpass(signal: np.ndarray, cutoff: float) -> np.ndarray:
    spectrum = np.fft.rfft(signal)
    frequencies = np.fft.rfftfreq(len(signal), 1 / RATE)
    spectrum *= 1 / np.sqrt(1 + (frequencies / cutoff) ** 8)
    return np.fft.irfft(spectrum, n=len(signal)).astype(np.float32)


def note(midi: float, duration: float, instrument: str, rng) -> np.ndarray:
    n = max(4, round(duration * RATE))
    t = np.arange(n) / RATE
    frequency = 440 * 2 ** ((midi - 69) / 12)
    attack = np.minimum(t / .004, 1)
    release = np.minimum((duration - t) / .045, 1).clip(0, 1)
    if instrument == "guitar":
        # Plucked-string partials lose upper harmonics faster; two detuned takes are panned separately.
        sound = np.zeros(n)
        for partial in range(1, min(32, int(12000 / frequency))):
            sound += np.sin(2 * np.pi * frequency * partial * t + .018 * partial) * np.exp(-t * (2.5 + partial * .55)) / partial
        sound += rng.uniform(-1, 1, n) * np.exp(-t * 95) * .09
        sound = lowpass(np.tanh(sound * 3.1), 5200) * np.exp(-t * 1.8)
    elif instrument == "bass":
        sound = np.sin(2 * np.pi * frequency * t) + .2 * np.sin(4 * np.pi * frequency * t)
        sound = np.tanh(sound * 1.4) * np.exp(-t * 1.5)
    elif instrument == "bell":
        sound = sum(np.sin(2 * np.pi * frequency * ratio * t) * gain * np.exp(-t * decay)
                    for ratio, gain, decay in [(1, .6, 2), (2.01, .23, 4), (2.73, .16, 6), (4.08, .08, 9)])
    elif instrument == "organ":
        sound = sum(np.sin(2 * np.pi * frequency * partial * t) / partial for partial in [1, 2, 3, 4, 6])
        sound *= .35 * (1 + .12 * np.sin(2 * np.pi * 5.2 * t))
    elif instrument == "synth":
        phase = 2 * np.pi * frequency * t + .02 * np.sin(2 * np.pi * 5 * t)
        sound = (np.sin(phase) + .32 * np.sin(2 * phase) + .17 * np.sin(3 * phase)) * .7
        sound *= np.exp(-t * 1.2)
    else:
        raise ValueError(instrument)
    return (sound * attack * release).astype(np.float32)


def drum(kind: str, rng) -> np.ndarray:
    duration = dict(kick=.35, snare=.26, hat=.095, openhat=.32, crash=1.5, tom=.4, metal=.46)[kind]
    t = np.arange(round(duration * RATE)) / RATE
    noise = rng.uniform(-1, 1, len(t)).astype(np.float32)
    if kind == "kick":
        phase = 2 * np.pi * (48 * t + 95 * .035 * (1 - np.exp(-t / .035)))
        sound = np.sin(phase) * np.exp(-t * 13) + noise * np.exp(-t * 180) * .15
    elif kind == "snare":
        sound = (noise - lowpass(noise, 950)) * np.exp(-t * 23) * .8
        sound += np.sin(2 * np.pi * 185 * t) * np.exp(-t * 28) * .45
    elif kind in ("hat", "openhat", "crash"):
        sound = noise - lowpass(noise, 6500 if kind != "crash" else 3200)
        sound *= np.exp(-t * dict(hat=45, openhat=16, crash=3.5)[kind]) * .6
    elif kind == "tom":
        sound = np.sin(2 * np.pi * (88 * t + 3 * (1 - np.exp(-t * 20)))) * np.exp(-t * 12)
    else:
        sound = sum(np.sin(2 * np.pi * hz * t) * np.exp(-t * decay) * gain
                    for hz, decay, gain in [(271, 12, .3), (713, 9, .2), (1171, 16, .14), (1813, 19, .1)])
    return (sound * np.minimum(t / .001, 1) * np.minimum((duration - t) / .005, 1)).astype(np.float32)


def add(target, sample, seconds, gain=1, pan=0):
    start = round(seconds * RATE) % len(target)
    stereo = sample[:, None] * np.array([np.sqrt((1 - pan) / 2), np.sqrt((1 + pan) / 2)]) * gain
    count = min(len(stereo), len(target) - start)
    target[start:start + count] += stereo[:count]
    if count < len(stereo):
        target[:len(stereo) - count] += stereo[count:]


def score(theme):
    rng = np.random.default_rng(SEED + THEMES.index(theme))
    beat = 60 / theme["bpm"]
    frames = round(BARS * 4 * beat * RATE)
    rhythm = np.zeros((frames, 2), dtype=np.float32)
    melody = np.zeros_like(rhythm)
    extra = np.zeros_like(rhythm)
    percussion = {kind: [drum(kind, rng) for _ in range(3)] for kind in ["kick", "snare", "hat", "openhat", "crash", "tom", "metal"]}
    cache = {}

    def tone(midi, duration, instrument):
        key = (midi, round(duration, 5), instrument)
        if key not in cache:
            cache[key] = note(midi, duration, instrument, rng)
        return cache[key]

    for bar in range(BARS):
        section = bar // 8
        root = theme["root"] + theme["progression"][bar % 8]
        motif = theme["motif"]
        # Finale quotes only this project's four new motives in a newly arranged progression.
        if theme["color"] == "finale":
            motif = THEMES[(bar // 4) % 4]["motif"]
        breakdown = section == 3
        for step in range(16):
            when = (bar * 4 + step / 4) * beat
            take = (bar + step) % 3
            if step in theme["kick"] and (not breakdown or step in (0, 8)):
                add(rhythm, percussion["kick"][take], when, .85)
            if step in (4, 12) and (not breakdown or step == 12):
                add(rhythm, percussion["snare"][take], when, .6, .05)
            if step % 2 == 0:
                add(rhythm, percussion["openhat" if step == 14 and bar % 4 == 3 else "hat"][take], when, .19 if step % 4 == 0 else .13, .45)
            if step % 2 == 1:
                add(extra, percussion["hat"][take], when, .12, -.45)
            if step in (2, 7, 10, 15):
                add(extra, percussion["kick"][take], when, .55)
            if bar % 4 == 3 and step >= 13:
                add(rhythm, percussion["tom"][take], when, .38, (step - 14) * .35)
                add(extra, percussion["snare"][take], when + beat / 8, .26, -.2)
            if theme["color"] == "steel" and step in (3, 11):
                add(rhythm, percussion["metal"][take], when, .2, -.5)
        if bar % 4 == 0:
            add(rhythm, percussion["crash"][bar % 3], bar * 4 * beat, .25, -.5)
        for eighth in range(8):
            when = (bar * 4 + eighth / 2) * beat
            interval = motif[(eighth + (2 if section == 4 else 0)) % 8]
            pitch = root + (interval if eighth in (3, 6, 7) or section in (1, 4, 5) else 0)
            duration = beat * (.85 if breakdown else .42)
            if not breakdown or eighth % 2 == 0:
                for offset, pan in [(-.055, -.72), (.045, .72)]:
                    chord = tone(pitch + offset, duration, "guitar") + .57 * tone(pitch + 7 + offset, duration, "guitar")
                    add(rhythm, chord, when, .29 if section == 0 else .35, pan)
                add(rhythm, tone(pitch - 12, beat * .48, "bass"), when, .44)
            add(extra, tone(pitch + 12, beat * .23, "guitar"), when + beat / 4, .17, .1)
        instrument = dict(steel="bell", neon="synth", carnival="organ", funeral="bell", finale="synth")[theme["color"]]
        if section != 0 or bar >= 4:
            for quarter in range(4):
                index = (bar * 4 + quarter) % len(theme["lead"])
                midi = root + theme["lead"][index]
                when = (bar * 4 + quarter) * beat
                length = beat * (.7 if theme["color"] == "carnival" else 1.7)
                add(melody, tone(midi, length, instrument), when, .19 if section in (2, 3) else .25, -.15)
                if section in (1, 4, 5):
                    add(extra, tone(midi + 12, beat * .7, "synth"), when + beat / 2, .16, .3)
        if theme["color"] in ("funeral", "finale") and bar % 2 == 0:
            add(melody, tone(root + 12, beat * 7.8, "organ"), bar * 4 * beat, .18, .2)
    # Circular delays/reverb include the preceding loop's tail and cannot open a gap at the seam.
    for delay, amount in [(beat * .75, .17), (beat * 1.5, .09), (.071, .08)]:
        melody += np.roll(melody[:, ::-1], round(delay * RATE), axis=0) * amount
    core = rhythm + melody
    renders = {}
    for mode, audio in [("normal", core), ("boss", core + extra)]:
        audio = np.tanh(audio * 1.1)
        audio -= np.mean(audio, axis=0)
        audio *= .83 / max(float(np.max(np.abs(audio))), 1e-8)
        # Only remove the waveform discontinuity, not the musical downbeat or its volume.
        correction = audio[0] - audio[-1]
        audio[-128:] += np.linspace(0, 1, 128)[:, None] * correction
        renders[mode] = np.clip(np.rint(audio * 32767), -32767, 32767).astype("<i2")
    return renders


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("src/tools/assets/audio/campaign"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    source_hash = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    assets = []
    metrics = ["asset,frames,channels,sample_rate,bits,peak,rms,sha256"]
    for theme in THEMES:
        for mode, pcm in score(theme).items():
            name = f"{theme['id']}-{mode}.wav"
            path = args.output / name
            with wave.open(str(path), "wb") as wav:
                wav.setnchannels(2); wav.setsampwidth(2); wav.setframerate(RATE); wav.writeframes(pcm.tobytes())
            samples = pcm.astype(np.float64) / 32768
            peak = float(np.max(np.abs(samples))); rms = float(np.sqrt(np.mean(samples ** 2)))
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            metrics.append(f"{name},{len(pcm)},2,48000,16,{peak:.6f},{rms:.6f},{digest}")
            assets.append(dict(path=f"audio/campaign/{name}", arenaId=theme["id"], title=theme["title"], mix=mode,
                               bpm=theme["bpm"], bars=BARS, frames=len(pcm), sha256=digest, peak=peak, rms=rms,
                               sourcePath="src/tools/compose_campaign_music.py", sourceSha256=source_hash,
                               transformation="Original score and oscillators/noise only; circular instrument tails and delays; stereo mastering to peak 0.83; PCM48k16"))
            print(f"{name}: {len(pcm)/RATE:.3f}s, peak={peak:.3f}, rms={rms:.3f}", flush=True)
    evidence = dict(schemaVersion=1, author="Wreck Riff original project composition and synthesis", origin="ORIGINAL_PROJECT_CONTENT",
                    externalSamples=False, artisticStatus="NEEDS_CREATIVE_REVIEW", generator="src/tools/compose_campaign_music.py",
                    generatorSha256=source_hash, numpyVersion=np.__version__, seed=SEED, themes=THEMES,
                    arrangement="48 bars: A introduction, B lead, A variation, C half-time bridge, B variation, D final refrain; eight bars per section",
                    assets=assets)
    (args.output / "provenance.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (args.output / "audio-metrics.csv").write_text("\n".join(metrics) + "\n", encoding="utf-8")
    (args.output / "score.txt").write_text("WRECK RIFF / ORIGINAL CAMPAIGN SCORES\n" + evidence["arrangement"] + "\n" +
        "\n".join(f"{x['title']} / {x['id']}: {x['bpm']} BPM; MIDI tonic {x['root']}; {x['color']} arrangement" for x in THEMES) +
        "\nNormal and boss mixes share exact tempo, length and note positions. Boss adds double kicks, offbeat rhythm guitar and counterpoint.\n" +
        "No recordings, external melody, samples or soundfonts used. Owner listening review: NEEDS_CREATIVE_REVIEW.\n", encoding="utf-8")


if __name__ == "__main__":
    main()

"""Explicit, offline authoring of the approved CreatorChords recordings.

Prerequisites: Python with numpy, FFmpeg 7.1 (imageio-ffmpeg 0.6.0 bundle).
Run: python src/tools/import_menu_music.py --ffmpeg <ffmpeg executable>
Builds never invoke this importer or download media. Originals and creator-page
license evidence are preserved alongside the finished music and SHA256 manifest.
"""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import subprocess
import tempfile
import wave

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
MUSIC = ROOT / "src/tools/assets/audio/music"
RATE = 48_000
TARGET_LUFS = -16.0
LOUDNESS_TOLERANCE_LU = .5
TRACKS = (
    ("menu", "riffs", "Riffs"),
    ("dead-air-yard", "metal-interlude", "Metal Interlude"),
    ("construction_17-normal", "construction", "Construction"),
    ("construction_17-boss", "the-collapse", "The Collapse"),
    ("neon_zero-normal", "circuits", "Circuits"),
    ("neon_zero-boss", "chaotic", "Chaotic"),
    ("euphoria_park-normal", "hall-of-the-metal-king", "Hall of the Metal King"),
    ("euphoria_park-boss", "bloodmoon-ritual", "Bloodmoon Ritual"),
)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_pcm(path, pcm):
    with wave.open(str(path), "wb") as output:
        output.setnchannels(2)
        output.setsampwidth(2)
        output.setframerate(RATE)
        output.writeframes(pcm.tobytes())


def measure_loudness(ffmpeg, path):
    """Analyze the encoded delivery PCM; input_* values measure it without edits."""
    result = subprocess.run([
        ffmpeg, "-hide_banner", "-nostdin", "-i", str(path),
        "-af", "loudnorm=I=-16:TP=-1.5:LRA=11:print_format=json", "-f", "null", "-",
    ], capture_output=True, check=True)
    log = result.stderr.decode("utf-8")
    values = json.loads(log[log.rfind("{"):log.rfind("}")+1])
    measurement = dict(integratedLufs=float(values["input_i"]),
                       truePeakDbtp=float(values["input_tp"]),
                       loudnessRangeLu=float(values["input_lra"]))
    if not all(math.isfinite(value) for value in measurement.values()):
        raise ValueError(f"Non-finite delivery loudness: {path}")
    return measurement


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ffmpeg", required=True)
    args = parser.parse_args()
    (ROOT / "build").mkdir(exist_ok=True)
    sources = {item["slug"]: item for item in json.loads((MUSIC / "sources.json").read_text("utf-8"))}
    assets, rows = [], []
    decoder_version = subprocess.run([args.ffmpeg, "-version"], capture_output=True, check=True, text=True).stdout.splitlines()[0]
    for identity, slug, title in TRACKS:
        source = MUSIC / "originals" / f"{slug}.mp3"
        evidence = MUSIC / "evidence" / f"{slug}.html"
        record = sources[slug]
        if sha(source) != record["sourceSha256"] or sha(evidence) != record["pageSha256"]:
            raise ValueError(f"Source/evidence changed: {slug}")
        if "Creative Commons Attribution 4.0" not in evidence.read_text("utf-8"):
            raise ValueError(f"Missing author's CC BY 4.0 grant: {slug}")
        decoded = subprocess.run([
            args.ffmpeg, "-hide_banner", "-nostdin", "-i", str(source),
            "-af", "loudnorm=I=-16:TP=-1.5:LRA=11:print_format=json",
            "-map_metadata", "-1", "-ar", str(RATE), "-ac", "2", "-f", "f32le", "-",
        ], capture_output=True, check=True)
        log = decoded.stderr.decode("utf-8")
        source_normalization = json.loads(log[log.rfind("{"):log.rfind("}")+1])
        samples = np.frombuffer(decoded.stdout, dtype="<f4").reshape(-1, 2).copy()
        # Start with a useful musical attack; remove the trailing release/silence.
        # Fixed 50 ms windows and threshold are recorded for reproducibility.
        window = RATE // 20
        blocks = samples[:len(samples)//window*window].reshape(-1, window, 2)
        energy = np.sqrt(np.mean(blocks.astype(np.float64)**2, axis=(1, 2)))
        threshold = max(.025, float(np.sqrt(np.mean(samples.astype(np.float64)**2))) * .40)
        active = np.flatnonzero(energy >= threshold)
        if len(active) < 1200:
            raise ValueError(f"Not a complete energetic recording: {slug}")
        start = max(0, int(active[0]) * window)
        end = min(len(samples), (int(active[-1])+1) * window)
        clip = samples[start:end].astype(np.float64)
        # A cyclic overlap: tail blends into head, then playback continues at
        # head+crossfade. There is no duplicated head segment or silence gap.
        cross = RATE // 8
        weight = np.linspace(0, 1, cross)[:, None]
        clip[-cross:] = clip[-cross:] * (1-weight) + clip[:cross] * weight
        clip = clip[cross:]
        clip -= np.mean(clip, axis=0)
        # 3ms boundary de-click, inaudibly short compared with a musical beat.
        edge = RATE * 3 // 1000
        clip[:edge] *= np.linspace(0, 1, edge)[:, None]
        clip[-edge:] *= np.linspace(1, 0, edge)[:, None]
        gain = min(1.0, .84 / float(np.max(np.abs(clip))))
        pcm = np.rint(clip * gain * 32767).astype("<i2")
        path = MUSIC / f"{identity}.wav"
        # One-pass loudnorm's output_i is not the delivery measurement: trim,
        # resampling, peak safety and quantization happen afterward. Measure the
        # actual loop PCM, compensate its integrated loudness and remeasure the
        # final encoded bytes. Only an accepted result replaces the local asset.
        with tempfile.TemporaryDirectory(prefix="music-loudness-", dir=ROOT / "build") as temporary:
            staging = Path(temporary) / path.name
            write_pcm(staging, pcm)
            before = measure_loudness(args.ffmpeg, staging)
            compensation_db = TARGET_LUFS - before["integratedLufs"]
            compensation_gain = 10 ** (compensation_db / 20)
            clip *= gain * compensation_gain
            final_peak_gain = min(1.0, .84 / float(np.max(np.abs(clip))))
            pcm = np.rint(clip * final_peak_gain * 32767).astype("<i2")
            write_pcm(staging, pcm)
            measured = measure_loudness(args.ffmpeg, staging)
            if abs(measured["integratedLufs"] - TARGET_LUFS) > LOUDNESS_TOLERANCE_LU:
                raise ValueError(f"Delivery loudness/peak constraints cannot both be met: {identity}: {measured}")
            loudness = dict(targetIntegratedLufs=TARGET_LUFS, toleranceLu=LOUDNESS_TOLERANCE_LU,
                            measurementMethod="FFmpeg loudnorm input analysis of final encoded WAV",
                            preCompensationIntegratedLufs=before["integratedLufs"],
                            compensationDb=compensation_db, compensationGain=compensation_gain,
                            finalPeakSafetyGain=final_peak_gain, **measured,
                            measurementSha256=sha(staging))
            staging.replace(path)
        actual = pcm.astype(np.float64) / 32768
        peak = float(np.max(np.abs(actual)))
        rms = float(np.sqrt(np.mean(actual**2)))
        digest = sha(path)
        rows.append([path.name, len(pcm), 2, RATE, 16, f"{peak:.6f}", f"{rms:.6f}", digest])
        assets.append(dict(
            path=f"audio/music/{path.name}", title=title, author="Alexander Nakarada",
            license="CC-BY-4.0", licensePath="licenses/assets/CC-BY-4.0.txt",
            creatorPage=record["creatorPage"], sourceUrl=record["sourceUrl"],
            sourcePath=source.relative_to(ROOT).as_posix(), sourceSha256=sha(source),
            evidencePath=evidence.relative_to(ROOT).as_posix(), evidenceSha256=sha(evidence),
            acquired=record["acquired"], sha256=digest, frames=len(pcm), sampleRate=RATE,
            bits=16, channels=2, trimStartSeconds=start/RATE, trimEndSeconds=end/RATE,
            loopOverlapSeconds=cross/RATE, boundaryDeclickMs=3, peakSafetyGain=gain,
            sourceNormalization=source_normalization, loudness=loudness,
            transformation="FFmpeg loudnorm I=-16 LUFS TP=-1.5 dBTP LRA=11; PCM48k stereo; "
                "50ms musical activity trim; cyclic 125ms linear overlap; DC removal; 3ms de-click; "
                "measure encoded loop LUFS; compensate to -16 LUFS; peak ceiling0.84; "
                "round to signed little-endian16bit; independently remeasure final bytes; metadata removed",
        ))
        print(f"{identity}: {len(pcm)/RATE:.3f}s, {measured['integratedLufs']:.2f} LUFS, peak {peak:.3f}, RMS {rms:.3f}", flush=True)
    with (MUSIC / "audio-metrics.csv").open("w", encoding="utf-8", newline="") as output:
        writer = csv.writer(output, lineterminator="\n")
        writer.writerow(["asset", "frames", "channels", "sample_rate", "bits", "peak", "rms", "sha256"])
        writer.writerows(rows)
    manifest = dict(schemaVersion=2, author="Alexander Nakarada", origin="LICENSED_RECORDINGS",
                    license="CC-BY-4.0", licensePath="licenses/assets/CC-BY-4.0.txt",
                    licenseUrl="https://creativecommons.org/licenses/by/4.0/",
                    generator="src/tools/import_menu_music.py", generatorSha256=sha(Path(__file__)),
                    decoderVersion=decoder_version, numpyVersion=np.__version__,
                    artisticStatus="NEEDS_CREATIVE_REVIEW", assets=assets)
    (MUSIC / "provenance.json").write_text(json.dumps(manifest, indent=2)+"\n", encoding="utf-8")
    score = ["WRECK RIFF / CREATORCHORDS HEAVY SOUNDTRACK", "",
             "Music by Alexander Nakarada (https://creatorchords.com).",
             "Licensed under Creative Commons Attribution 4.0 International (CC BY 4.0).",
             "https://creativecommons.org/licenses/by/4.0/", ""]
    for asset in assets:
        score += [f"{asset['title']} -> {asset['path']}", asset["creatorPage"]]
    score += ["", "Changes: loudness normalization, activity trim, prepared cyclic loop, PCM conversion.",
              "Each final encoded loop is measured and compensated to -16 LUFS (+/-0.5 LU), peak ceiling0.84.",
              "Victory, defeat and draw contain edited excerpts from Riffs with CC0 metal impact.",
              "Original deterministic workshop ambience and mechanical UI cues: GenerateAudio.java.",
              "Author pages and original MP3 hashes retained in provenance.json and sources.json.",
              "Independent recordings have independent lengths and start at zero; no shared seek timeline.",
              "Artistic status: NEEDS_CREATIVE_REVIEW. Technical checks cannot substitute for listening."]
    (MUSIC / "score.txt").write_text("\n".join(score)+"\n", encoding="utf-8")


if __name__ == "__main__":
    main()

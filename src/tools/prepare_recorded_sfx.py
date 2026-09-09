"""Offline recording preparation. --preview makes first audition cues; --all follows audition.

Run with bundled Python/numpy and --ffmpeg pointing at the existing local FFmpeg.
Sources are explicit approved archive entries; no network request is made here.
"""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import wave

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "src/tools/assets/audio/recorded"
DOWNLOADS = ROOT / "build/audio-v03-downloads"
RATE = 48000
FIREARMS = "https://opengameart.org/content/the-free-firearm-sound-library"
BANGS = "https://opengameart.org/content/25-cc0-bang-firework-sfx"
AUTHOR = "Ben Jaszczak, Brian Nelson, Kevin Heras, Matthew Nanney"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pcm_write(path, samples, peak=.88):
    samples = np.asarray(samples, dtype=np.float64)
    samples -= samples.mean()
    # Recorded transients have much greater crest factor than the replaced synths.
    # Explicit 4:1 compression above 10% of source peak keeps the body audible in
    # the music mix without adding oscillators, clipped peaks or extra live voices.
    samples /= np.max(np.abs(samples))
    magnitude=np.abs(samples)
    samples=np.sign(samples)*np.where(magnitude>.1,.1+(magnitude-.1)/4,magnitude)
    # Smooth edges after DC removal; no hard clipping or opaque loudness maximizer.
    fade = min(240, len(samples) // 8)
    samples[:48] *= np.linspace(0, 1, 48)
    samples[-fade:] *= np.linspace(1, 0, fade)
    samples -= samples.mean()
    samples *= peak / np.max(np.abs(samples))
    pcm = np.rint(samples * 32767).astype("<i2")
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(RATE)
        wav.writeframes(pcm.tobytes())
    return pcm.astype(np.float64) / 32767


def read_pcm(path):
    with wave.open(str(path)) as wav:
        assert (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) == (1, 2, RATE)
        return np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2").astype(np.float64) / 32768


def trim_attack(samples, seconds):
    # Preserve 1 ms before the strongest recorded transient; selected source files
    # contain a single shot and a long environmental tail.
    onset = max(0, int(np.argmax(np.abs(samples))) - 48)
    cut = samples[onset:onset + int(seconds * RATE)].copy()
    return np.pad(cut, (0, max(0, int(seconds * RATE) - len(cut)))), onset / RATE


def respeed(samples, ratio):
    return np.interp(np.arange(0, len(samples), ratio), np.arange(len(samples)), samples)


def lowpass(samples, cutoff):
    # Linear-phase FFT low-pass; original attack is retained in a parallel layer.
    frequency = np.fft.rfftfreq(len(samples), 1 / RATE)
    transfer = 1 / np.sqrt(1 + (frequency / cutoff) ** 8)
    return np.fft.irfft(np.fft.rfft(samples) * transfer, n=len(samples))


def layer(*items, seconds):
    output = np.zeros(int(seconds * RATE))
    for signal, gain, delay in items:
        offset = int(delay * RATE)
        size = min(len(signal), len(output) - offset)
        if size > 0: output[offset:offset + size] += signal[:size] * gain
    return output


def sources(ffmpeg):
    entries = []
    choices = [("ak-shot", "Prepared SFX Library/AK-47/C_28P.wav"),
               ("ar-shot", "Prepared SFX Library/AR-15/D_32P.wav"),
               ("smg-shot", "Prepared SFX Library/Carl Gustav M45/G_31P.wav")]
    choices += [(p.stem, p.name) for p in sorted(DOWNLOADS.glob("*.ogg"))]
    archives = {a["file"]: a for a in json.loads((DOWNLOADS / "archives.json").read_text())}
    for key, archive_entry in choices:
        is_gun = archive_entry.endswith(".wav")
        original = SOURCE / "original" / (key + Path(archive_entry).suffix)
        original.parent.mkdir(parents=True, exist_ok=True)
        if not original.exists(): shutil.copyfile(DOWNLOADS / archive_entry, original)
        decoded = SOURCE / "decoded" / (key + ".wav")
        decoded.parent.mkdir(parents=True, exist_ok=True)
        if not decoded.exists():
            subprocess.run([str(ffmpeg), "-hide_banner", "-loglevel", "error", "-y", "-i", str(original),
                            "-map_metadata", "-1", "-ac", "1", "-ar", str(RATE), "-c:a", "pcm_s16le",
                            "-bitexact", str(decoded)], check=True)
        archive = archives["firearms.7z" if is_gun else "bangs.zip"]
        entries.append(dict(id=key, sourcePath=str(original.relative_to(ROOT)).replace("\\", "/"),
            sourceSha256=sha(original), decodedPath=str(decoded.relative_to(ROOT)).replace("\\", "/"),
            decodedSha256=sha(decoded), sourceUrl=FIREARMS if is_gun else BANGS,
            author=AUTHOR if is_gun else "rubberduck", license="CC0-1.0",
            acquired="2026-09-09",
            licensePath="licenses/assets/CC0-1.0.txt", archiveUrl=archive["url"],
            archiveSha256=archive["sha256"], archiveEntry=archive_entry,
            transformation="FFmpeg 7.1: downmix mono; resample 48000Hz; PCM16LE; strip metadata"))
    (SOURCE / "sources.json").write_text(json.dumps(dict(schemaVersion=1, sources=entries), indent=2), encoding="utf-8")
    return {e["id"]: read_pcm(ROOT / e["decodedPath"]) for e in entries}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ffmpeg", type=Path, required=True)
    parser.add_argument("--all", action="store_true")
    args = parser.parse_args()
    raw = sources(args.ffmpeg)
    output = SOURCE / "processed" if args.all else ROOT / "build/audio-v03-audition"
    provenance, metrics = [], []

    def emit(name, samples, inputs, transform, peak=.88):
        target = output / (name + ".wav")
        pcm = pcm_write(target, samples, peak)
        entry = dict(path="audio/" + name + ".wav", preparedPath=str(target.relative_to(ROOT)).replace("\\", "/"),
            sha256=sha(target), inputs=inputs, transformation=transform + "; 4:1 compression above10% source peak; DC removal; edge fades; peak " + str(peak),
            license="CC0-1.0", licensePath="licenses/assets/CC0-1.0.txt", frames=len(pcm),
            artisticStatus="NEEDS_CREATIVE_REVIEW")
        provenance.append(entry)
        metrics.append([name+".wav", len(pcm), 1, RATE, 16, f"{np.max(np.abs(pcm)):.8f}",
                        f"{np.sqrt(np.mean(pcm**2)):.8f}", sha(target)])

    guns = ["ak-shot", "ar-shot", "smg-shot"]
    for variant in (range(3) if args.all else range(1)):
        gun_key = guns[variant]
        gun, gun_onset = trim_attack(raw[gun_key], .28)
        # Recorded attack/body only: eliminate the old synthetic noise/oscillator foundation.
        gun = layer((gun, 1, 0), (lowpass(respeed(gun, .76), 1300), .42, .005), seconds=.32)
        emit(f"machine-gun-{variant+1}", gun, [gun_key], f"recorded shot transient at {gun_onset:.6f}s; 280ms cut; body layer speed0.76 LP1300Hz +5ms; total320ms")
        boom_key = f"cannon_{variant+1:02}"
        boom, boom_onset = trim_attack(raw[boom_key], 1.15)
        body = lowpass(respeed(boom, .55), 1000)
        explosion = layer((boom, .52, 0), (body, 1, .012), (respeed(gun, .6), .2, .10), seconds=1.7)
        emit(f"explosion-{variant+1}", explosion, [boom_key, gun_key], f"firework cannon at {boom_onset:.6f}s; speed0.55 LP1000Hz body +12ms; recorded debris +100ms; total1.7s")
        metal = gun - lowpass(gun, 1700)
        # A recorded transient excites short resonant echoes; this is designed metal impact,
        # not a claim that the source library contains a separately recorded metal plate.
        hit = layer((metal, .85, 0), (respeed(metal, 1.29), .35, .011),
                    (respeed(metal, .84), .24, .027), seconds=.23)
        emit(f"metal-hit-{variant+1}", hit, [gun_key], "recorded high-pass1700Hz transient; short pitch-shifted resonant layers at11/27ms; total230ms", .78)
        if args.all:
            fire_key=f"fw_{variant+1:02}"
            fire,_=trim_attack(raw[fire_key],1.4)
            fizz=fire-lowpass(fire,600)
            homing=layer((gun,.8,0),(lowpass(respeed(boom,1.7),4200),.48,.008),seconds=.55)
            power_launch=layer((gun,.55,0),(lowpass(respeed(boom,.76),1800),1,.006),seconds=.85)
            heavy=layer((boom,.4,0),(lowpass(respeed(boom,.39),650),1,.012),(fizz,.18,.18),seconds=2.25)
            mine=layer((boom,.45,0),(lowpass(respeed(boom,.34),720),1.1,.018),(hit,.38,.14),seconds=2.3)
            destroyed=layer((explosion,.6,0),(lowpass(respeed(boom,.43),550),.8,.10),(fizz,.45,.32),seconds=2.8)
            napalm_launch=layer((gun,.35,0),(lowpass(respeed(fizz,.8),3200),.9,.015),seconds=.7)
            napalm_blast=layer((boom,.48,0),(lowpass(respeed(fire,.72),2100),.8,.04),(fizz,.45,.24),seconds=1.8)
            emit(f"homing-launch-{variant+1}",homing,[gun_key,boom_key],"recorded gun attack and speed1.7 LP4200Hz cannon body; total550ms")
            emit(f"power-launch-{variant+1}",power_launch,[gun_key,boom_key],"recorded gun attack and speed0.76 LP1800Hz cannon body; total850ms")
            emit(f"power-explosion-{variant+1}",heavy,[boom_key,fire_key],"recorded cannon attack; speed0.39 LP650Hz blast body; firework debris at180ms; total2.25s")
            emit(f"mine-detonate-{variant+1}",mine,[boom_key,gun_key],"recorded cannon attack; speed0.34 LP720Hz blast body; metal-impact derivative at140ms; total2.3s")
            emit(f"destroyed-{variant+1}",destroyed,[boom_key,gun_key,fire_key],"recorded layered blast plus speed0.43 LP550Hz body at100ms; firework debris at320ms; total2.8s")
            emit(f"napalm-launch-{variant+1}",napalm_launch,[gun_key,fire_key],"recorded attack plus speed0.8 LP3200Hz firework hiss; total700ms")
            emit(f"napalm-explosion-{variant+1}",napalm_blast,[boom_key,fire_key],"recorded cannon attack plus slowed firework body and high-passed crackle; total1.8s")
    if args.all:
        loop=raw["fw_loop"]
        fire=layer((np.tile(loop,max(1,int(2*RATE/len(loop))+1)),1,0),seconds=2)
        fire=lowpass(fire,3900)-lowpass(fire,140)
        cross=1920
        fire[-cross:]=fire[-cross:]*np.linspace(1,0,cross)+fire[:cross]*np.linspace(0,1,cross)
        emit("napalm-fire",fire,["fw_loop"],"recorded loopable firework crackle; band140..3900Hz; repeat/crop2s; 40ms tail/head crossfade",.69)
        ice,_=trim_attack(raw["shot_01"],.32)
        ice=respeed(ice,1.6);ice=ice-lowpass(ice,1800)
        icy_launch=layer((ice[::-1],.5,0),(respeed(ice,1.2),.65,.11),seconds=.42)
        icy_hit=layer((ice,1,0),(respeed(ice,.79),.6,.037),(respeed(ice,1.37),.28,.11),seconds=.52)
        icy_end=layer((respeed(ice,.87),.7,0),(ice,.48,.089),(respeed(ice,1.19),.27,.18),seconds=.65)
        emit("freeze-launch",icy_launch,["shot_01"],"recorded crack HP1800Hz speed1.6; reversed intake followed by short forward launch at110ms; total420ms")
        emit("freeze-hit",icy_hit,["shot_01"],"recorded bright crack; pitch-separated breakup layers at37/110ms; total520ms")
        emit("freeze-end",icy_end,["shot_01"],"recorded ice-crack design: three declining pitch-separated fragments at0/89/180ms; total650ms",.78)
        shield,_=trim_attack(raw["bang_08"],.6)
        shield=lowpass(respeed(shield,.62),2200)
        on=layer((shield[::-1],1,0),seconds=.75)
        on[:4800]*=np.linspace(0,1,4800)
        shield_hit=layer((shield,.6,0),(respeed(ice,.75),.36,0),(respeed(shield,.81),.3,.02),seconds=.48)
        end=layer((respeed(shield,1.5),1,0),seconds=.65)*np.linspace(1,0,int(.65*RATE))
        emit("shield-on",on,["bang_08"],"recorded bang speed0.62 LP2200Hz; reversed swell with100ms fade-in; total750ms",.82)
        emit("shield-hit",shield_hit,["bang_08","shot_01"],"recorded low resonance plus bright crack and shifted20ms reflection; total480ms",.82)
        emit("shield-end",end,["bang_08"],"recorded resonance speed1.5 with declining tail; total650ms",.76)
    metadata=SOURCE if args.all else output
    with (metadata / "audio-metrics.csv").open("w", newline="", encoding="utf-8") as f:
        writer=csv.writer(f); writer.writerow(["asset","frames","channels","sample_rate","bits","peak","rms","sha256"]); writer.writerows(metrics)
    (metadata / "sfx-provenance.json").write_text(json.dumps(dict(schemaVersion=1,
        sourceEvidenceSha256=sha(SOURCE / "sources.json"), assets=provenance),indent=2), encoding="utf-8")
    print("Prepared",len(provenance),"recorded cues:",output,flush=True)


if __name__ == "__main__": main()

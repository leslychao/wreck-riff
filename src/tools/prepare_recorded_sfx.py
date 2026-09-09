"""Offline 0.4 recording preparation. Default makes first audition cues; --all follows audition.

Run with bundled Python/numpy. Only preserved, hash-verified 48kHz PCM is read.
The original 0.3 decoder/import recipe remains in docs/asset-history/audio-0.3.
"""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import wave

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "src/tools/assets/audio/recorded"
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


def band(samples, low, high):
    return lowpass(samples, high) - lowpass(samples, low)


def layer(*items, seconds):
    output = np.zeros(int(seconds * RATE))
    for signal, gain, delay in items:
        offset = int(delay * RATE)
        size = min(len(signal), len(output) - offset)
        if size > 0: output[offset:offset + size] += signal[:size] * gain
    return output


def sources():
    evidence=json.loads((SOURCE / "sources.json").read_text(encoding="utf-8"))
    if evidence["schemaVersion"] != 1: raise ValueError("Unsupported recording source manifest")
    result={}
    for entry in evidence["sources"]:
        if entry["id"] in result or entry["sourceUrl"] not in (FIREARMS,BANGS):
            raise ValueError("Unapproved or duplicate recording source")
        for kind in ("source","decoded"):
            path=(ROOT / entry[kind+"Path"]).resolve()
            if not path.is_relative_to(SOURCE) or sha(path)!=entry[kind+"Sha256"]:
                raise ValueError("Recording source hash/path mismatch: "+str(path))
        result[entry["id"]]=read_pcm(ROOT / entry["decodedPath"])
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--all", action="store_true")
    args = parser.parse_args()
    raw = sources()
    output = SOURCE / "processed" if args.all else ROOT / "build/audio-v04-audition"
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
        body = band(respeed(boom, .43), 45, 300)
        crack = gun-lowpass(gun,2200)
        explosion = layer((boom,.95,0),(crack,.72,0),(body,2.4,.008),
                          (lowpass(respeed(boom,.55),1400),.55,.018),
                          (respeed(crack,.66),.33,.075),(respeed(crack,.91),.18,.145),seconds=1.65)
        emit(f"explosion-{variant+1}", explosion, [boom_key, gun_key], f"0.4 recorded cannon at {boom_onset:.6f}s + HP2200Hz gun attack; speed0.43 band45..300Hz body gain2.4 +8ms; speed0.55 LP1400Hz gain0.55 +18ms; metallic tails speeds0.66/0.91 gains0.33/0.18 +75/145ms; total1.65s",.90)
        metal = gun - lowpass(gun, 1700)
        # A recorded transient excites short resonant echoes; this is designed metal impact,
        # not a claim that the source library contains a separately recorded metal plate.
        hit = layer((metal,1,0),(band(respeed(gun,.57),70,420),1.5,.004),
                    (respeed(metal,1.29),.42,.009),(respeed(metal,.72),.38,.026),
                    (respeed(metal,.88),.21,.057),seconds=.34)
        emit(f"metal-hit-{variant+1}", hit, [gun_key], "0.4 recorded HP1700Hz attack; speed0.57 band70..420Hz body gain1.5 +4ms; metallic layers speeds1.29/0.72/0.88 gains0.42/0.38/0.21 +9/26/57ms; total340ms", .86)
        # New weapon layers remain premixed into exactly one runtime voice per event.
        cannon_launch=layer((gun,.95,0),(boom,.45,0),(body,2.1,.007),
                            (respeed(hit,.74),.22,.08),seconds=.9)
        ricochet=layer((metal,1,0),(respeed(metal,1.5),.65,.013),(respeed(metal,1.12),.42,.036),
                       (band(respeed(gun,.65),100,600),.52,.002),seconds=.48)
        cannon_hit=layer((hit,.95,0),(boom,.65,0),(body,2.5,.006),
                         (respeed(hit,.53),.47,.11),(respeed(metal,.71),.3,.20),seconds=1.35)
        fire_key=f"fw_{variant+1:02}"
        fire,_=trim_attack(raw[fire_key],1.4)
        fizz=fire-lowpass(fire,600)
        ballistic_launch=layer((gun,.8,0),(body,1.5,.015),(lowpass(respeed(fizz,.72),3300),.95,.045),seconds=1.3)
        # Reversed recorded firework intake rises into an arriving descending crack.
        fall=layer((respeed(fizz,.84)[::-1],.7,0),(respeed(metal,.65),.42,.45),seconds=.75)
        fall*=np.linspace(.1,1,len(fall))
        ballistic_blast=layer((explosion,.9,0),(band(respeed(boom,.30),40,220),2.3,.012),
                              (respeed(hit,.45),.5,.13),(fizz,.22,.31),seconds=2.6)
        ram=layer((hit,1,0),(band(respeed(boom,.48),65,430),1.35,.003),
                  (respeed(metal,.57),.65,.025),(respeed(hit,.83),.48,.075),seconds=.82)
        for name,samples,inputs,transform in [
            ("cannon-launch",cannon_launch,[gun_key,boom_key],"gun/cannon attack; speed0.43 band45..300Hz body gain2.1 +7ms; speed0.74 metal tail +80ms; total900ms"),
            ("cannon-ricochet",ricochet,[gun_key],"HP1700Hz metal attack; speeds1.5/1.12 ringing tails +13/36ms; speed0.65 band100..600Hz thump +2ms; total480ms"),
            ("cannon-hit",cannon_hit,[gun_key,boom_key],"metal/cannon attack; speed0.43 band45..300Hz body gain2.5 +6ms; slowed crushed-metal tails +110/200ms; total1.35s"),
            ("ballistic-launch",ballistic_launch,[gun_key,boom_key,fire_key],"gun attack; speed0.43 low body +15ms; speed0.72 LP3300Hz firework exhaust +45ms; total1.3s"),
            ("ballistic-fall",fall,[gun_key,fire_key],"reversed speed0.84 HP600Hz recorded firework rush; speed0.65 metal crack +450ms; rising envelope0.1..1; total750ms"),
            ("ballistic-explosion",ballistic_blast,[gun_key,boom_key,fire_key],"layered blast attack; speed0.30 band40..220Hz body gain2.3 +12ms; speed0.45 crushed metal +130ms; firework debris +310ms; total2.6s"),
            ("ram-hit",ram,[gun_key,boom_key],"metal impact attack; speed0.48 band65..430Hz body gain1.35 +3ms; speed0.57 metal flex +25ms; speed0.83 crushing tail +75ms; total820ms")]:
            emit(f"{name}-{variant+1}",samples,inputs,"0.4 recorded "+transform,.9 if name!="ballistic-fall" else .80)
        if args.all:
            homing=layer((gun,.8,0),(lowpass(respeed(boom,1.7),4200),.48,.008),seconds=.55)
            power_launch=layer((gun,.55,0),(lowpass(respeed(boom,.76),1800),1,.006),seconds=.85)
            heavy=layer((explosion,.95,0),(band(respeed(boom,.34),40,250),2.0,.01),(hit,.42,.11),(fizz,.25,.20),seconds=2.25)
            mine=layer((explosion,.88,0),(band(respeed(boom,.30),40,280),2.2,.008),(respeed(hit,.63),.65,.07),seconds=2.3)
            destroyed=layer((explosion,.95,0),(band(respeed(boom,.37),45,330),1.7,.015),(respeed(hit,.44),.58,.15),(fizz,.45,.32),seconds=2.8)
            napalm_launch=layer((gun,.35,0),(lowpass(respeed(fizz,.8),3200),.9,.015),seconds=.7)
            napalm_blast=layer((boom,.75,0),(crack,.4,0),(body,1.1,.008),
                               (lowpass(respeed(fire,.72),2100),.65,.04),(fizz,.45,.24),seconds=1.8)
            emit(f"homing-launch-{variant+1}",homing,[gun_key,boom_key],"recorded gun attack and speed1.7 LP4200Hz cannon body; total550ms")
            emit(f"power-launch-{variant+1}",power_launch,[gun_key,boom_key],"recorded gun attack and speed0.76 LP1800Hz cannon body; total850ms")
            emit(f"power-explosion-{variant+1}",heavy,[boom_key,gun_key,fire_key],"0.4 layered attack; speed0.34 band40..250Hz body gain2 +10ms; crushed metal +110ms; firework debris +200ms; total2.25s",.90)
            emit(f"mine-detonate-{variant+1}",mine,[boom_key,gun_key],"0.4 layered attack; speed0.30 band40..280Hz body gain2.2 +8ms; speed0.63 crushed metal +70ms; total2.3s",.90)
            emit(f"destroyed-{variant+1}",destroyed,[boom_key,gun_key,fire_key],"0.4 layered attack; speed0.37 band45..330Hz body gain1.7 +15ms; speed0.44 metal breakup +150ms; firework debris +320ms; total2.8s",.90)
            emit(f"napalm-launch-{variant+1}",napalm_launch,[gun_key,fire_key],"recorded attack plus speed0.8 LP3200Hz firework hiss; total700ms")
            emit(f"napalm-explosion-{variant+1}",napalm_blast,[boom_key,gun_key,fire_key],"0.4 cannon and HP2200Hz gun attack; speed0.43 band45..300Hz body gain1.1 +8ms; speed0.72 LP2100Hz firework +40ms; crackle +240ms; total1.8s",.90)
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
    (metadata / "sfx-provenance.json").write_text(json.dumps(dict(schemaVersion=1, revision="0.4",
        preparationScriptPath="src/tools/prepare_recorded_sfx.py",
        preparationScriptSha256=sha(Path(__file__)),
        sourceEvidenceSha256=sha(SOURCE / "sources.json"), assets=provenance),indent=2), encoding="utf-8")
    print("Prepared",len(provenance),"recorded cues:",output,flush=True)


if __name__ == "__main__": main()

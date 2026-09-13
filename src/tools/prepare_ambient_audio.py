"""Offline decoding of preserved CC0 recordings; no network or synthetic layers.

GenerateAudio owns the final loop overlap, filtering and normalization. The two
Freesound files are public HQ preview encodings, explicitly not original masters.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import wave

ROOT = Path(__file__).resolve().parents[2]
DIRECTORY = ROOT / 'src/tools/assets/audio/ambient'
SOURCES = (
    ('qubodup-fan', 'qubodup-fan.wav', '4c0287ffdad82a7001403c82ab77e2a04475fb30c9919938b311f644f429c531',
     'https://opengameart.org/content/defect-motor-loop', 'https://opengameart.org/sites/default/files/fan_interval.wav',
     'qubodup', 'original WAV published by the recordist', 'Recorded graphics-card fan; source author describes his GFX card fan.'),
    ('rvgerxini-motor', 'rvgerxini-motor-preview.mp3', 'a4d8091a9d5067f18cc665549e94ecabf86524a250346774c13fc2458c5622d1',
     'https://freesound.org/people/Rvgerxini/sounds/518037/', 'https://cdn.freesound.org/previews/518/518037_9453283-hq.mp3',
     'Rvgerxini', 'public HQ MP3 preview; original master was not downloaded', 'Mini-fan motor against a microphone, recorded and processed with Audacity.'),
    ('jerimee-machinery', 'jerimee-machinery-preview.mp3', '3bf8e9b3f66db1881ff518f091278fec30484d38402ac80648f29b39cc0d8850',
     'https://freesound.org/people/Jerimee/sounds/521777/', 'https://cdn.freesound.org/previews/521/521777_3202600-hq.mp3',
     'Jerimee', 'public HQ MP3 preview; original 8 kHz master was not downloaded', 'Industrial machinery hum; uploader explicitly tags field-recording. Limited source bandwidth is retained.'),
)


def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def relative(path):return path.relative_to(ROOT).as_posix()


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--ffmpeg',required=True);args=parser.parse_args()
    decoded=DIRECTORY/'decoded';decoded.mkdir(exist_ok=True)
    version=subprocess.run([args.ffmpeg,'-version'],capture_output=True,text=True,check=True).stdout.splitlines()[0]
    evidence=[]
    for identity,filename,expected,page,url,author,form,description in SOURCES:
        source=DIRECTORY/'original'/filename;capture=DIRECTORY/(identity+'-source.html')
        if sha(source)!=expected:raise ValueError('Source checksum mismatch: '+filename)
        if 'creativecommons.org/publicdomain/zero/1.0' not in capture.read_text(encoding='utf-8'):
            raise ValueError('Captured primary page does not contain CC0 evidence: '+identity)
        output=decoded/(identity+'.wav')
        subprocess.run([args.ffmpeg,'-hide_banner','-loglevel','error','-nostdin','-y','-i',str(source),
                        '-ac','1','-ar','48000','-c:a','pcm_s16le','-map_metadata','-1',str(output)],check=True)
        with wave.open(str(output),'rb') as audio:
            if audio.getnchannels()!=1 or audio.getframerate()!=48000 or audio.getsampwidth()!=2:raise ValueError('Invalid decoded PCM')
            frames=audio.getnframes()
        evidence.append(dict(id=identity,sourcePath=relative(source),sourceSha256=sha(source),sourceUrl=page,
            downloadUrl=url,author=author,artifactForm=form,description=description,license='CC0-1.0',
            licensePath='licenses/assets/CC0-1.0.txt',acquired='2026-09-13',evidencePath=relative(capture),
            evidenceSha256=sha(capture),decodedPath=relative(output),decodedSha256=sha(output),frames=frames,
            transformation=version+'; mono downmix; resample 48000 Hz; PCM16LE; metadata removed'))
    manifest=dict(schemaVersion=1,origin='LICENSED_RECORDINGS',preparationScriptPath=relative(Path(__file__)),
                  preparationScriptSha256=sha(Path(__file__)),sources=evidence)
    (DIRECTORY/'sources.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Decoded three preserved CC0 recordings; no network used')


if __name__=='__main__':main()

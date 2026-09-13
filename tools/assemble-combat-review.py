"""Build a local, lossless before/after viewer from real game capture directories.

No images are transformed and no acceptance status is inferred from screenshots.
Run with Python 3.11+: --before <diagnostic-result.json> --after <...> --output <directory>.
"""
import argparse
import hashlib
import html
import json
import os
from pathlib import Path
import re
from urllib.parse import quote


def captures(directory):
    result = {}
    for path in sorted((directory / "captures").glob("*.png")):
        match = re.match(r"WreckRiff-[0-9.]+-(.+)-\d{12,}-\d+\.png$", path.name)
        if match:
            key = match.group(1)
            if key in result:
                raise ValueError(f"Ambiguous capture {key}: {directory}")
            result[key] = path
    return result


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def build(before_path, after_path, output):
    before_path, after_path, output = (p.resolve() for p in (before_path, after_path, output))
    output.mkdir(parents=True, exist_ok=True)
    reports = [json.loads(p.read_text(encoding="utf-8-sig")) for p in (before_path, after_path)]
    sidecars = []
    for path, report in zip((before_path, after_path), reports):
        summary = path.parent / "session-summary.json"
        if summary.is_file():
            report["seed"] = json.loads(summary.read_text(encoding="utf-8-sig")).get("seed", "UNKNOWN")
            sidecars.append(summary)
        else:
            report.setdefault("seed", "UNKNOWN")
    conditions = ("mode", "arenaId", "width", "height", "msaaSamples", "glow", "seed")
    differences = [key for key in conditions if reports[0].get(key) != reports[1].get(key)]
    if differences:
        raise ValueError("Capture conditions differ: " + ", ".join(differences))
    shots = [captures(p.parent) for p in (before_path, after_path)]
    common = [key for key in shots[0] if key in shots[1]]
    if not common:
        raise ValueError("No matching real game captures; refusing to create an empty comparison")

    def url(path):
        return quote(os.path.relpath(path, output).replace("\\", "/"), safe="/.-")

    pairs = [{"label": key, "before": url(shots[0][key]), "after": url(shots[1][key])} for key in common]
    facts = []
    for label, path, report in zip(("До", "После"), (before_path, after_path), reports):
        facts.append(f'<tr><th>{label}</th><td>{html.escape(str(report.get("status")))}</td>'
                     f'<td>{report.get("width")} × {report.get("height")}</td>'
                     f'<td>MSAA {report.get("msaaSamples")}, bloom {report.get("glow")}</td>'
                     f'<td>{html.escape(str(report.get("gpu", "не измерено")))}</td>'
                     f'<td><a href="{url(path)}">Исходный отчёт</a></td></tr>')
    videos = []
    media = []
    for label, path in zip(("До", "После"), (before_path, after_path)):
        mp4 = path.parent / "showcase-sound.mp4"
        reconstructed = mp4.is_file()
        if not reconstructed:
            mp4 = path.parent / "showcase.mp4"
        avi = path.parent / "showcase.avi"
        if mp4.is_file():
            caption = label + (" · звук восстановлен из журнала реально выделенных аудиоголосов" if reconstructed else " · видео без звука")
            videos.append(f'<figure><figcaption>{caption}</figcaption><video controls preload="metadata" src="{url(mp4)}"></video></figure>')
            media.append(mp4)
            if reconstructed:
                for name in ("audio.wav", "audio-events.json", "AUDIO_README.txt"):
                    audio_path = path.parent / name
                    if not audio_path.is_file():
                        raise ValueError(f"Reconstructed audio evidence is missing: {audio_path}")
                    media.append(audio_path)
        elif avi.is_file():
            videos.append(f'<p><a href="{url(avi)}">{label}: исходное видео AVI</a></p>')
            media.append(avi)
    page = """<!doctype html><html lang="ru"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Wreck Riff — боевая графика</title>
<style>
:root{color-scheme:dark;font:16px/1.5 system-ui;background:#121519;color:#e8edf1}body{max-width:1500px;margin:auto;padding:24px}
h1{font-size:28px;margin:0 0 8px}p{max-width:1000px;color:#bac5cd}a{color:#ffcc79}table{border-collapse:collapse;width:100%;margin:24px 0;font-size:14px}
td,th{padding:10px;text-align:left;border-bottom:1px solid #39414a}label{display:flex;gap:12px;align-items:center;flex-wrap:wrap}
select,button{font:inherit;padding:8px;background:#242c34;color:inherit;border:1px solid #626e79;border-radius:4px}
.compare{position:relative;aspect-ratio:16/9;overflow:hidden;background:#080b0e;margin-top:18px}.compare img{position:absolute;inset:0;width:100%;height:100%;object-fit:contain}
#after{clip-path:inset(0 0 0 50%)}#divider{position:absolute;left:50%;height:100%;border-left:2px solid #ffbb64;pointer-events:none}
.tag{position:absolute;bottom:10px;background:#111b;padding:4px 10px}.right{right:10px}.left{left:10px}
input{width:100%;accent-color:#ffbb64}.videos{display:flex;flex-wrap:wrap;gap:16px}.videos figure{flex:1;min-width:280px;margin:0}video{width:100%}
small{color:#a7b3bd}footer{margin-top:28px}
</style><h1>Wreck Riff · боевая графика</h1>
<p>Сравнение исходных кадров настоящего окна. Выберите сцену и двигайте границу. Изображения открываются без обработки.
HP-галерея и подготовка позиций помечены в самой записи; атаки выполняет обычная симуляция.
Этот просмотр не присваивает FEEL_APPROVED или MVP_ACCEPTED.</p>
<p>COMPARABILITY</p>
<table><thead><tr><th>Версия</th><th>Статус запуска</th><th>Окно</th><th>Настройки</th><th>GPU</th><th>Доказательство</th></tr></thead><tbody>FACTS</tbody></table>
<label>Сцена <select id="scene"></select><button id="previous">←</button><button id="next">→</button>
<a id="openBefore" target="_blank">Открыть «до»</a><a id="openAfter" target="_blank">Открыть «после»</a></label>
<div class="compare"><img id="before" alt="До"><img id="after" alt="После"><span id="divider"></span><b class="tag left">ДО</b><b class="tag right">ПОСЛЕ</b></div>
<input aria-label="Граница сравнения" id="split" type="range" min="0" max="100" value="50">
<div class="videos">VIDEOS</div><footer><small>Измерения производительности выполняются отдельно от записи видео.
Показатель RTX 5080 не подтверждает RTX 3060. Перечень файлов и SHA-256: <a href="manifest.json">manifest.json</a>.</small></footer>
<script>
const pairs=PAIRS, select=document.getElementById('scene');
pairs.forEach((p,i)=>{const o=document.createElement('option');o.value=i;o.textContent=p.label;select.append(o)});
function show(){const p=pairs[Number(select.value)];for(const side of ['before','after'])document.getElementById(side).src=p[side];document.getElementById('openBefore').href=p.before;document.getElementById('openAfter').href=p.after}
select.onchange=show;document.getElementById('previous').onclick=()=>{select.value=(Number(select.value)+pairs.length-1)%pairs.length;show()};document.getElementById('next').onclick=()=>{select.value=(Number(select.value)+1)%pairs.length;show()};
document.getElementById('split').oninput=e=>{const v=e.target.value;document.getElementById('after').style.clipPath=`inset(0 0 0 ${v}%)`;document.getElementById('divider').style.left=v+'%'};show();
</script></html>"""
    page = page.replace("FACTS", "".join(facts)).replace("VIDEOS", "".join(videos)).replace("PAIRS", json.dumps(pairs, ensure_ascii=False).replace("<", "\\u003c"))
    unmatched = (len(shots[0].keys() - shots[1].keys()), len(shots[1].keys() - shots[0].keys()))
    comparability = f"Общие кадры: {len(common)}. Без пары: до {unmatched[0]}, после {unmatched[1]}. Seed: {reports[0]['seed']}. "
    comparability += "Режим, карта, окно, MSAA и bloom сопоставлены по отчётам. Точные матрицы камеры и тождество сценария в исходных отчётах отсутствуют (UNKNOWN); название сцены само по себе их не доказывает."
    page = page.replace("COMPARABILITY", html.escape(comparability))
    (output / "index.html").write_text(page, encoding="utf-8")
    fields = ("status", "mode", "arenaId", "seed", "sourceSha256", "gpu", "width", "height", "msaaSamples", "glow", "recordingFrameRate", "errors")
    manifest = {"schemaVersion": 1, "before": {k: reports[0].get(k) for k in fields}, "after": {k: reports[1].get(k) for k in fields},
                "pairedCaptures": common, "unpairedBefore": sorted(shots[0].keys() - shots[1].keys()),
                "unpairedAfter": sorted(shots[1].keys() - shots[0].keys()), "cameraMatrixIdentity": "UNKNOWN", "fixtureIdentity": "UNKNOWN", "files": {}}
    for path in [before_path, after_path, *sidecars, *media, *shots[0].values(), *shots[1].values()]:
        manifest["files"][url(path)] = sha(path)
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{len(common)} real capture pairs: {output / 'index.html'}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("before", "after", "output"):
        parser.add_argument(f"--{name}", type=Path, required=True)
    args = parser.parse_args()
    build(args.before, args.after, args.output)

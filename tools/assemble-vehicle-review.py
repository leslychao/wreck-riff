"""Present verified, unmodified native vehicle screenshots as a local review page."""
import argparse
import hashlib
import html
import json
import os
from pathlib import Path
from urllib.parse import quote


def build(report_path, output):
    report_path, output = report_path.resolve(), output.resolve()
    report = json.loads(report_path.read_text(encoding="utf-8-sig"))
    if not report.get("realWindow") or report.get("captureCount") != len(report.get("captures", [])):
        raise ValueError("A complete native-window report is required")
    identity = report["buildIdentity"]["sourceSha256"]
    hashes = report["captureSha256"]
    output.mkdir(parents=True, exist_ok=True)

    def url(path):
        return quote(os.path.relpath(path, output).replace("\\", "/"), safe="/.-")

    shots = []
    assertions = 0
    for capture in report["captures"]:
        name = capture["capture"]
        matches = [file for file in hashes if file.startswith(name + "-")]
        if len(matches) != 1:
            raise ValueError("Ambiguous or missing native capture: " + name)
        path = report_path.parent / matches[0]
        if hashlib.sha256(path.read_bytes()).hexdigest() != hashes[matches[0]]:
            raise ValueError("Captured image changed: " + str(path))
        for vehicle in capture["vehicles"]:
            for morph in vehicle["activeGpuMorphs"]:
                if morph["gpuTargets"] != 2:
                    raise ValueError("Native GPU morph fallback: " + name)
                assertions += 1
        shots.append({"label": name, "url": url(path), "state": capture["vehicles"]})
    page = """<!doctype html><html lang="ru"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Wreck Riff — модели и повреждения</title>
<style>:root{color-scheme:dark;background:#121519;color:#e8edf1;font:16px/1.5 system-ui}body{max-width:1500px;margin:auto;padding:24px}h1{font-size:28px}p{max-width:1000px;color:#bdc8d0}a{color:#ffcc79}select,button{font:inherit;padding:8px;background:#242c34;color:inherit;border:1px solid #626e79;border-radius:4px}label{display:flex;gap:12px;align-items:center;flex-wrap:wrap}img{display:block;width:100%;margin:18px 0;background:#080b0e}pre{white-space:pre-wrap;font-size:13px;color:#b9c4cd}code{overflow-wrap:anywhere}</style>
<h1>Wreck Riff · машины, повреждения и статусы</h1>
<p>Исходные кадры настоящего окна: шесть профилей, пять стадий HP, стороны попадания,
ремонт, щит, иней, LOD и гараж. Это проверка моделей; игровой бой и производительность
оцениваются отдельными запусками. FEEL_APPROVED и MVP_ACCEPTED остаются за владельцем.</p>
<p>COUNT кадров · ASSERTIONS проверок двух активных GPU morph targets · 1600×900, MSAA4, bloom off.
<a href="REPORT">Полный native-отчёт</a></p>
<label>Кадр <select id="scene"></select><button id="previous">←</button><button id="next">→</button><a id="original" target="_blank">Открыть оригинал</a></label>
<img id="image" alt="Нативный кадр Wreck Riff"><details><summary>Состояние показанных машин</summary><pre id="state"></pre></details>
<p>SHA-256 исходного снимка сборки: <code>IDENTITY</code>. SHA-256 каждого PNG проверен перед созданием этой страницы.</p>
<script>const shots=SHOTS,select=document.getElementById('scene');shots.forEach((shot,i)=>{const option=document.createElement('option');option.value=i;option.textContent=shot.label;select.append(option)});function show(){const shot=shots[Number(select.value)];document.getElementById('image').src=shot.url;document.getElementById('original').href=shot.url;document.getElementById('state').textContent=JSON.stringify(shot.state,null,2)}select.onchange=show;document.getElementById('previous').onclick=()=>{select.value=(Number(select.value)+shots.length-1)%shots.length;show()};document.getElementById('next').onclick=()=>{select.value=(Number(select.value)+1)%shots.length;show()};show();</script></html>"""
    page = page.replace("COUNT", str(len(shots))).replace("ASSERTIONS", str(assertions))
    page = page.replace("REPORT", url(report_path)).replace("IDENTITY", html.escape(identity))
    page = page.replace("SHOTS", json.dumps(shots, ensure_ascii=False).replace("<", "\\u003c"))
    (output / "index.html").write_text(page, encoding="utf-8")
    print(f"{len(shots)} verified native captures: {output / 'index.html'}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()
    build(arguments.report, arguments.output)

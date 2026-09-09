"""Explicit one-time asset import. Never invoked by a normal build or the game.

Uses five author-approved Poly Haven CC0 materials, creator-hosted Metalmania,
and the official Roboto release. Original bytes and per-file provenance are kept.
Run with the bundled Python (Pillow/numpy), with imageio-ffmpeg in build/asset-tooling.
"""
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import urllib.request
import zipfile

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / "src/tools/assets"
RESOURCES = ROOT / "src/main/resources"
ENTRIES = []
DATE = "2026-09-09"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def fetch(url, maximum=128 * 1024 * 1024):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "WreckRiff-asset-import/0.2"}), timeout=60) as response:
        data = response.read(maximum + 1)
    if not data or len(data) > maximum:
        raise ValueError("Empty or oversized source: " + url)
    return data


def save(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def entry(path, source_path, source_url, author, license_id, license_path, transformation):
    ENTRIES.append(dict(path=str(path.relative_to(RESOURCES)).replace("\\", "/"),
        sourcePath=str(source_path.relative_to(ROOT)).replace("\\", "/"), sourceUrl=source_url,
        author=author, license=license_id, licensePath=license_path, acquired=DATE,
        sourceSha256=sha(source_path.read_bytes()), sha256=sha(path.read_bytes()),
        transformation=transformation))


def png(path, pixels):
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(pixels).save(path, optimize=True)


def materials():
    for asset, author in [("asphalt_02", "Rob Tuytel"), ("cracked_concrete", "Dimitrios Savva"),
            ("metal_plate_02", "Rob Tuytel"), ("blue_metal_plate", "Rob Tuytel"),
            ("rusty_metal_03", "Amal Kumar")]:
        page = "https://polyhaven.com/a/" + asset
        html = fetch(page).decode()
        urls = re.findall(r'https://dl\.polyhaven\.org/[^"<>\s]+', html)
        for original, target in [("diff", "diffuse"), ("nor_gl", "normal"), ("rough", "specular")]:
            marker = asset + "_" + original + "_4k.png"
            url = next((u for u in urls if marker in u), None)
            if url is None:
                # Exact CDN naming of the selected material download, no catalog crawl.
                url = f"https://dl.polyhaven.org/file/ph-assets/Textures/png/4k/{asset}/{marker}"
            url = url.replace("/4k/", "/2k/").replace("_4k.png", "_2k.png")
            source = SOURCES / "materials" / asset / (original + ".png")
            if not source.exists():
                save(source, fetch(url))
            image = Image.open(source)
            if image.size != (2048, 2048):
                raise ValueError("Expected 2K source: " + str(source))
            destination = RESOURCES / "textures/materials" / asset / (target + ".png")
            if target == "specular":
                rough = np.asarray(image.convert("L"), dtype=np.float32) / 255
                # Phong strength mask, NOT a metallic/roughness packed map. Restrained
                # highlights retain material readability under the existing Lighting shader.
                maximum = .70 if asset in ("metal_plate_02", "blue_metal_plate") else .22
                spec = np.rint(255 * (.025 + maximum * (1 - rough) ** 2)).astype(np.uint8)
                png(destination, np.repeat(spec[:, :, None], 3, axis=2))
                change = f"Roughness linear -> RGB Phong strength: 0.025 + {maximum}*(1-r)^2; PNG 2K"
            elif target == "normal":
                # Poly Haven's lower-resolution normal files can contain averaged
                # vectors shorter than one. Normalize after decoding, retain the
                # original source, and strip color metadata from this data texture.
                vector = np.asarray(image.convert("RGB"), dtype=np.float32) / 127.5 - 1
                magnitude = np.linalg.norm(vector, axis=2, keepdims=True)
                if np.any(magnitude < .01):
                    raise ValueError("Degenerate normal source: " + str(source))
                normal = np.rint((vector / magnitude + 1) * 127.5).clip(0, 255).astype(np.uint8)
                png(destination, normal)
                change = "2K OpenGL normal vectors normalized per texel after source downsampling; RGB8 linear data PNG; color metadata removed"
            else:
                save(destination, source.read_bytes())
                change = "Unmodified 2K PNG; sRGB color"
            entry(destination, source, url, author + " / Poly Haven", "CC0-1.0", "licenses/assets/CC0-1.0.txt", change)
        print("Imported material", asset, flush=True)
    # Vehicle paint is a neutral-color derivative of the flat blue painted sheet.
    # Livery tint is applied by the renderer. Fine chips stay local rather than
    # multiplying large rust areas across every body panel.
    source = SOURCES / "materials/blue_metal_plate/diff.png"
    color = np.asarray(Image.open(source).convert("RGB"), dtype=np.float32) / 255
    luminance = color @ np.array([.2126, .7152, .0722], dtype=np.float32)
    neutral = np.clip(.58 + (luminance - luminance.mean()) * .7, .3, .85)
    paint_path = RESOURCES / "textures/vehicle/paint.png"
    png(paint_path, np.rint(np.repeat(neutral[:, :, None], 3, axis=2) * 255).astype(np.uint8))
    entry(paint_path, source, "https://polyhaven.com/a/blue_metal_plate", "Rob Tuytel / Poly Haven", "CC0-1.0",
          "licenses/assets/CC0-1.0.txt", "Neutral livery diffuse: sRGB luma contrast centered at .58; renderer supplies paint tint")
    rubber_path = RESOURCES / "textures/vehicle/rubber.png"
    rubber = np.clip(.45 + (luminance - luminance.mean()) * .13, .3, .6)
    png(rubber_path, np.rint(np.repeat(rubber[:, :, None], 3, axis=2) * 255).astype(np.uint8))
    entry(rubber_path, source, "https://polyhaven.com/a/blue_metal_plate", "Rob Tuytel / Poly Haven", "CC0-1.0",
          "licenses/assets/CC0-1.0.txt", "Neutral low-contrast grayscale derivative for rubber; renderer supplies dark tint; geometry owns tread")


def font():
    url = "https://github.com/googlefonts/roboto-3-classic/releases/download/v3.016/Roboto_v3.016.zip"
    if (SOURCES / "fonts/source.json").exists():
        return
    archive = zipfile.ZipFile(io.BytesIO(fetch(url)))
    names = archive.namelist()
    for style in ("Regular", "Bold"):
        name = next(n for n in names if n.endswith("/RobotoCondensed-" + style + ".ttf") and n.startswith("hinted/static/"))
        save(SOURCES / "fonts" / ("RobotoCondensed-" + style + ".ttf"), archive.read(name))
    license_url = "https://raw.githubusercontent.com/googlefonts/roboto-3-classic/v3.016/OFL.txt"
    save(RESOURCES / "licenses/assets/Roboto-OFL.txt", fetch(license_url))
    save(SOURCES / "fonts/source.json", json.dumps(dict(sourceUrl=url,
        sourceCommit="5166f3d07889bf7d3732fb72e09623d7e52f862b", licenseUrl=license_url,
        acquired=DATE, archiveSha256=sha(archive.fp.getvalue()) if isinstance(archive.fp, io.BytesIO) else "",
        files={p.name: sha(p.read_bytes()) for p in (SOURCES / "fonts").glob("*.ttf")}), indent=2).encode())
    print("Imported Roboto Condensed regular/bold", flush=True)


def music():
    catalog_url = "https://incompetech.com/music/royalty-free/pieces.json"
    catalog = json.loads(fetch(catalog_url))
    # Creator's catalog shape is inspected, and only this approved ISRC is used.
    tracks = catalog.values() if isinstance(catalog, dict) else catalog
    track = next(t for t in tracks if isinstance(t, dict) and "USUAN1700023" in json.dumps(t))
    save(SOURCES / "audio/creator-catalog-entry.json", json.dumps(track, ensure_ascii=False, indent=2).encode())
    mp3_url = next((v for v in track.values() if isinstance(v, str) and v.endswith("Metalmania.mp3")),
                   "https://incompetech.com/music/royalty-free/mp3-royaltyfree/Metalmania.mp3")
    if not mp3_url.startswith("https://"):
        mp3_url = "https://incompetech.com/music/royalty-free/mp3-royaltyfree/" + mp3_url.lstrip("/")
    mp3 = SOURCES / "audio/Metalmania-original.mp3"
    save(mp3, fetch(mp3_url))
    sys.path.insert(0, str(ROOT / "build/asset-tooling"))
    import imageio_ffmpeg
    wav = SOURCES / "audio/Metalmania-source.wav"
    subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), "-hide_banner", "-loglevel", "warning", "-y", "-i", str(mp3),
        "-map_metadata", "-1", "-ac", "2", "-ar", "48000", "-c:a", "pcm_s16le", "-bitexact", str(wav)], check=True)
    save(SOURCES / "audio/source.json", json.dumps(dict(title="Metalmania", author="Kevin MacLeod",
        isrc="USUAN1700023", sourceUrl=mp3_url,
        creatorPage="https://incompetech.com/music/royalty-free/index.html?Search=Search&isrc=USUAN1700023",
        license="CC-BY-4.0", licenseUrl="https://creativecommons.org/licenses/by/4.0/", acquired=DATE,
        originalSha256=sha(mp3.read_bytes()), pcmSha256=sha(wav.read_bytes()),
        decode="imageio-ffmpeg 0.6.0; bundled FFmpeg; pcm_s16le 48000Hz stereo; metadata stripped",
        artisticStatus="NEEDS_CREATIVE_REVIEW", audition="Not auditioned by an audio-capable tool; owner must listen in the actual mix"), indent=2).encode())
    print("Imported creator-hosted Metalmania", flush=True)


if __name__ == "__main__":
    materials()
    font()
    music()
    for name, url in [("CC0-1.0", "https://creativecommons.org/publicdomain/zero/1.0/legalcode.txt"),
                      ("CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/legalcode.txt")]:
        save(RESOURCES / "licenses/assets" / (name + ".txt"), fetch(url))
    save(RESOURCES / "licenses/asset-provenance.json", json.dumps(dict(schemaVersion=1, assets=ENTRIES), indent=2).encode())

"""Explicit one-time asset import. Never invoked by a normal build or the game.

Uses five author-approved Poly Haven CC0 materials and the official Roboto release.
Original bytes and per-file provenance are kept. Run with bundled Python (Pillow/numpy).
Soundtrack authoring is separate: src/tools/import_menu_music.py.
Diffuse derivatives use Microsoft JDK 21 via JAVA_HOME or --java. To regenerate
only these derivatives from preserved local originals, use --runtime-diffuse-only.
It writes src/tools/assets/materials/<id>/runtime-diffuse.png for thirteen DDS
materials and the single lossless textures/materials/leafy_grass/diffuse.png.
Then explicitly run prepareEnvironmentDiffuse with the pinned local texconv tool;
ordinary builds never encode or download assets. DDS license hashes stay untouched.
--runtime-specular-only rebuilds only the five original scalar Phong maps as L8
from preserved roughness files; it also stays offline and preserves their values.
"""
import argparse
import hashlib
import io
import json
import os
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
ORIGINAL_MATERIALS = {"asphalt_02": "Rob Tuytel", "cracked_concrete": "Dimitrios Savva",
    "metal_plate_02": "Rob Tuytel", "blue_metal_plate": "Rob Tuytel", "rusty_metal_03": "Amal Kumar"}
LOSSLESS_DIFFUSE_PATH = "textures/materials/leafy_grass/diffuse.png"
DIFFUSE_TRANSFORMATION = ("2K RGB(A)16 -> RGB(A)8 PNG via Microsoft JDK 21 BufferedImage.getRGB, "
    "matching jME 3.8.1 AWTLoader decoded channels exactly; alpha and texel layout preserved; "
    "sRGB color; no resize, tint, dithering or additional gamma conversion; "
    "src/tools/import_assets.py + src/tools/java/game/wreckriff/tools/PrepareDiffuseTextures.java")


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


def runtime_specular(source, destination, base, maximum):
    """Same authored Phong values as before; omit only duplicated RGB channels."""
    with Image.open(source) as image:
        if image.size != (2048, 2048):
            raise ValueError("Expected 2K roughness source: " + str(source))
        # Keep the existing Pillow transfer, including I;16 -> L clipping.
        rough = np.asarray(image.convert("L"), dtype=np.float32) / 255
    strength = np.rint(255 * (base + maximum * (1 - rough) ** 2)).astype(np.uint8)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(".prepared")
    Image.fromarray(strength).save(temporary, format="PNG", optimize=True)
    temporary.replace(destination)
    return f"Roughness linear -> L8 Phong strength: {base} + {maximum}*(1-r)^2; PNG 2K, raw samples unchanged, no color metadata"


def runtime_specular_only():
    """Offline update of only the five original scalar maps and their provenance."""
    manifest = RESOURCES / "licenses/asset-provenance.json"
    provenance = json.loads(manifest.read_text(encoding="utf-8"))
    paths = {f"textures/materials/{name}/specular.png": name for name in ORIGINAL_MATERIALS}
    found = set()
    for item in provenance["assets"]:
        name = paths.get(item["path"])
        if name is None:
            continue
        source = ROOT / item["sourcePath"]
        if sha(source.read_bytes()) != item["sourceSha256"]:
            raise ValueError("Original roughness checksum changed: " + str(source))
        maximum = .70 if name in ("metal_plate_02", "blue_metal_plate") else .22
        destination = RESOURCES / item["path"]
        item["transformation"] = runtime_specular(source, destination, .025, maximum) + "; src/tools/import_assets.py"
        item["sha256"] = sha(destination.read_bytes())
        found.add(name)
    if found != set(ORIGINAL_MATERIALS):
        raise ValueError("Missing original specular provenance")
    save(manifest, (json.dumps(provenance, indent=2) + "\n").encode("utf-8"))


def runtime_diffuse(source, destination, java=None):
    with Image.open(source) as image:
        if image.size != (2048, 2048):
            raise ValueError("Expected 2K source: " + str(source))
    header = source.read_bytes()[:29]
    if header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR" or header[25] not in (2, 6):
        raise ValueError("Expected RGB(A) PNG: " + str(source))
    if header[24] == 8:
        save(destination, source.read_bytes())
        return "Unmodified 2K PNG; sRGB color"
    if header[24] != 16:
        raise ValueError("Unexpected diffuse bit depth: " + str(source))
    java_home = os.environ.get("JAVA_HOME")
    executable = Path(java) if java else Path(java_home) / "bin/java.exe" if java_home else None
    if executable is None or not executable.is_file():
        raise ValueError("Set JAVA_HOME to Microsoft JDK 21 or supply --java")
    helper = ROOT / "src/tools/java/game/wreckriff/tools/PrepareDiffuseTextures.java"
    subprocess.run([str(executable), "-Djava.awt.headless=true", str(helper), str(source), str(destination)], check=True)
    return DIFFUSE_TRANSFORMATION


def runtime_diffuse_only(java=None):
    manifest = RESOURCES / "licenses/asset-provenance.json"
    provenance = json.loads(manifest.read_text(encoding="utf-8"))
    diffuse = [item for item in provenance["assets"] if item["path"] == LOSSLESS_DIFFUSE_PATH
        or (item["path"].startswith("textures/materials/") and item["path"].endswith("/diffuse.dds"))]
    if any(item["path"] == "textures/materials/leafy_grass/diffuse.dds" for item in diffuse):
        raise ValueError("leafy_grass must have its approved lossless PNG entry, never DDS")
    if not diffuse:
        raise ValueError("Missing licensed environment diffuse entries; complete the initial explicit preparation first")
    # Validate every approved original before replacing any intermediate. DDS hashes
    # belong exclusively to PrepareEnvironmentDiffuse, including after this rebuild.
    for item in diffuse:
        source = ROOT / item["sourcePath"]
        if sha(source.read_bytes()) != item["sourceSha256"]:
            raise ValueError("Original diffuse source hash mismatch: " + str(source))
    for item in diffuse:
        source = ROOT / item["sourcePath"]
        if item["path"] == LOSSLESS_DIFFUSE_PATH:
            replacement = material_diffuse_entry("leafy_grass", source, item.get("sourceUrl", ""),
                item.get("author", ""), acquired=item.get("acquired", DATE), java=java)
            merge_material_entries([replacement])
            print("Prepared lossless runtime diffuse", item["path"], flush=True)
        else:
            destination = source.with_name("runtime-diffuse.png")
            runtime_diffuse(source, destination, java)
            print("Prepared local diffuse intermediate", destination.relative_to(ROOT), flush=True)


def material_diffuse_entry(asset, source, source_url, author, acquired=DATE, java=None):
    """Prepare the declared PNG or DDS intermediate; never sign DDS output bytes."""
    lossless = asset == "leafy_grass"
    path = LOSSLESS_DIFFUSE_PATH if lossless else f"textures/materials/{asset}/diffuse.dds"
    manifest = RESOURCES / "licenses/asset-provenance.json"
    provenance = json.loads(manifest.read_text(encoding="utf-8")) if manifest.exists() else {"assets": []}
    previous = next((item for item in provenance["assets"] if item["path"] == path), None)
    source_path = source.relative_to(ROOT).as_posix()
    source_hash = sha(source.read_bytes())
    if previous is not None and (previous["sourcePath"] != source_path or previous["sourceSha256"] != source_hash):
        raise ValueError("Original diffuse source hash mismatch: " + str(source))
    destination = RESOURCES / path if lossless else source.with_name("runtime-diffuse.png")
    change = runtime_diffuse(source, destination, java)
    output_hash = sha(destination.read_bytes()) if lossless else ""
    if previous is not None:
        if lossless and output_hash != previous["sha256"]:
            return {**previous, "sha256": output_hash, "transformation": change}
        return previous
    # A fresh import is deliberately incomplete until the separate DDS preparation
    # publishes its actual hash. Never pretend the PNG checksum describes a DDS.
    return dict(path=path, sourcePath=source_path, sourceUrl=source_url, author=author,
        license="CC0-1.0", licensePath="licenses/assets/CC0-1.0.txt", acquired=acquired,
        sourceSha256=source_hash, sha256=output_hash, transformation=change)


def merge_material_entries(entries):
    """Preserve the other importer's licensed maps and already prepared DDS records."""
    manifest = RESOURCES / "licenses/asset-provenance.json"
    provenance = json.loads(manifest.read_text(encoding="utf-8")) if manifest.exists() else dict(schemaVersion=1, assets=[])
    replacements = {item["path"]: item for item in entries}
    retired = {path[:-4] + ".png" for path in replacements if path.endswith("/diffuse.dds")}
    if LOSSLESS_DIFFUSE_PATH in replacements:
        retired.add("textures/materials/leafy_grass/diffuse.dds")
    merged = []
    for item in provenance["assets"]:
        if item["path"] not in retired:
            merged.append(replacements.pop(item["path"], item))
    merged.extend(replacements.values())
    if merged != provenance["assets"] or not manifest.exists():
        provenance["assets"] = merged
        save(manifest, (json.dumps(provenance, indent=2) + "\n").encode("utf-8"))


def materials(java=None):
    for asset, author in ORIGINAL_MATERIALS.items():
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
            if target == "diffuse":
                ENTRIES.append(material_diffuse_entry(asset, source, url, author + " / Poly Haven", java=java))
                continue
            destination = RESOURCES / "textures/materials" / asset / (target + ".png")
            with Image.open(source) as image:
                if image.size != (2048, 2048):
                    raise ValueError("Expected 2K source: " + str(source))
                if target == "specular":
                    # Phong strength mask, NOT a metallic/roughness packed map. Restrained
                    # highlights retain material readability under the existing Lighting shader.
                    maximum = .70 if asset in ("metal_plate_02", "blue_metal_plate") else .22
                    change = runtime_specular(source, destination, .025, maximum)
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
            entry(destination, source, url, author + " / Poly Haven", "CC0-1.0", "licenses/assets/CC0-1.0.txt", change)
        print("Imported material", asset, flush=True)
    # Vehicle paint is a neutral-color derivative of the flat blue painted sheet.
    # Livery tint is applied by the renderer. Fine chips stay local rather than
    # multiplying large rust areas across every body panel.
    source = SOURCES / "materials/blue_metal_plate/diff.png"
    # Read the same JDK-decoded RGB8 derivative consumed by environment compression;
    # Pillow's direct RGB16 conversion would use a different channel transfer.
    with Image.open(source.with_name("runtime-diffuse.png")) as image:
        color = np.asarray(image.convert("RGB"), dtype=np.float32) / 255
    luminance = color @ np.array([.2126, .7152, .0722], dtype=np.float32)
    neutral = np.clip(.58 + (luminance - luminance.mean()) * .7, .3, .85)
    paint_path = RESOURCES / "textures/vehicle/paint.png"
    png(paint_path, np.rint(np.repeat(neutral[:, :, None], 3, axis=2) * 255).astype(np.uint8))
    entry(paint_path, source, "https://polyhaven.com/a/blue_metal_plate", "Rob Tuytel / Poly Haven", "CC0-1.0",
          "licenses/assets/CC0-1.0.txt", "From preserved runtime-diffuse.png intermediate; neutral livery diffuse: sRGB luma contrast centered at .58; renderer supplies paint tint")
    rubber_path = RESOURCES / "textures/vehicle/rubber.png"
    rubber = np.clip(.45 + (luminance - luminance.mean()) * .13, .3, .6)
    png(rubber_path, np.rint(np.repeat(rubber[:, :, None], 3, axis=2) * 255).astype(np.uint8))
    entry(rubber_path, source, "https://polyhaven.com/a/blue_metal_plate", "Rob Tuytel / Poly Haven", "CC0-1.0",
          "licenses/assets/CC0-1.0.txt", "From preserved runtime-diffuse.png intermediate; neutral low-contrast grayscale derivative for rubber; renderer supplies dark tint; geometry owns tread")


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


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-diffuse-only", action="store_true", help="Offline: rebuild thirteen DDS intermediates and leafy_grass lossless PNG; preserve DDS bytes and hashes")
    parser.add_argument("--runtime-specular-only", action="store_true", help="Offline: prepare only five original L8 specular maps and their provenance")
    parser.add_argument("--java", help="Path to Microsoft JDK 21 java executable; defaults to JAVA_HOME/bin/java.exe")
    arguments = parser.parse_args()
    if arguments.runtime_specular_only and arguments.runtime_diffuse_only:
        parser.error("Choose one derivative preparation mode")
    if arguments.runtime_specular_only:
        runtime_specular_only()
        sys.exit(0)
    if arguments.runtime_diffuse_only:
        runtime_diffuse_only(arguments.java)
        sys.exit(0)
    materials(arguments.java)
    font()
    for name, url in [("CC0-1.0", "https://creativecommons.org/publicdomain/zero/1.0/legalcode.txt"),
                      ("CC-BY-4.0", "https://creativecommons.org/licenses/by/4.0/legalcode.txt")]:
        save(RESOURCES / "licenses/assets" / (name + ".txt"), fetch(url))
    merge_material_entries(ENTRIES)

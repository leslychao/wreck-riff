"""Explicit CC0 environment import; normal builds never download assets.

Source PNGs and license evidence remain local. Re-running without --download only
rebuilds derivatives from those originals. No other asset provenance is replaced.
"""
import argparse
import json
from pathlib import Path
import numpy as np
from PIL import Image
from import_assets import fetch, png, runtime_diffuse, save, sha

ROOT = Path(__file__).resolve().parents[2]
RESOURCES = ROOT / 'src/main/resources'
SOURCES = ROOT / 'src/tools/assets/materials'
MATERIALS = {
    'concrete_wall_009': 'Charlotte Baglioni / Poly Haven',
    'leafy_grass': 'Charlotte Baglioni / Poly Haven',
    'brown_mud': 'Rob Tuytel / Poly Haven',
    'gravelly_sand': 'Poly Haven',
    'red_brick_03': 'Rob Tuytel / Poly Haven',
    'wood_planks_grey': 'Rob Tuytel / Poly Haven',
}


def prepare(download=False, selected=None):
    entries = []
    for asset, author in MATERIALS.items():
        if selected and asset not in selected:
            continue
        if download:
            page = fetch('https://polyhaven.com/a/' + asset)
            if b'CC0' not in page:
                raise ValueError('Missing explicit CC0 evidence: ' + asset)
            save(SOURCES / asset / 'source-page.html', page)
        for original, target in [('diff', 'diffuse'), ('nor_gl', 'normal'), ('rough', 'specular')]:
            source = SOURCES / asset / (original + '.png')
            url = f'https://dl.polyhaven.org/file/ph-assets/Textures/png/2k/{asset}/{asset}_{original}_2k.png'
            if not source.exists():
                if not download:
                    raise ValueError('Missing original; explicit --download required: ' + str(source))
                save(source, fetch(url, 64 * 1024 * 1024))
            destination = RESOURCES / 'textures/materials' / asset / (target + '.png')
            with Image.open(source) as image:
                if image.size != (2048, 2048):
                    raise ValueError('Expected 2K image: ' + str(source))
                if target == 'diffuse':
                    change = runtime_diffuse(source, destination)
                elif target == 'normal':
                    normal = np.asarray(image.convert('RGB'), dtype=np.float32) / 127.5 - 1
                    normal /= np.maximum(np.linalg.norm(normal, axis=2, keepdims=True), 1e-6)
                    png(destination, np.rint((normal + 1) * 127.5).clip(0, 255).astype(np.uint8))
                    change = '2K OpenGL tangent normals normalized; RGB8 linear PNG without color metadata'
                else:
                    rough = np.asarray(image.convert('L'), dtype=np.float32) / 255
                    strength = np.rint(255 * (.02 + .12 * (1 - rough) ** 2)).astype(np.uint8)
                    png(destination, np.repeat(strength[:, :, None], 3, axis=2))
                    change = 'Roughness linear -> restrained RGB Phong strength: 0.02 + 0.12*(1-r)^2; PNG 2K'
            entries.append(dict(path=destination.relative_to(RESOURCES).as_posix(),
                sourcePath=source.relative_to(ROOT).as_posix(), sourceUrl=url, author=author,
                license='CC0-1.0', licensePath='licenses/assets/CC0-1.0.txt', acquired='2026-09-13',
                sourceSha256=sha(source.read_bytes()), sha256=sha(destination.read_bytes()),
                transformation=change + '; src/tools/import_environment_materials.py'))
            print('Prepared', destination.relative_to(RESOURCES), flush=True)
    manifest = RESOURCES / 'licenses/asset-provenance.json'
    provenance = json.loads(manifest.read_text(encoding='utf-8'))
    replacements = {entry['path'] for entry in entries}
    provenance['assets'] = [entry for entry in provenance['assets'] if entry['path'] not in replacements] + entries
    save(manifest, (json.dumps(provenance, indent=2) + '\n').encode('utf-8'))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--download', action='store_true', help='Explicitly acquire selected CC0 originals')
    parser.add_argument('--only', nargs='+', choices=MATERIALS, help='Prepare only these local material sets')
    args=parser.parse_args()
    prepare(args.download,args.only)

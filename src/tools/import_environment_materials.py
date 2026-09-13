"""Explicit CC0 environment import; normal builds never download assets.

Source PNGs and license evidence remain local. Re-running without --download only
rebuilds derivatives from those originals. No other asset provenance is replaced.
Diffuse writes only src/tools/assets/materials/<id>/runtime-diffuse.png; the
separate explicit prepareEnvironmentDiffuse task publishes licensed BC7 DDS.
"""
import argparse
from pathlib import Path
import numpy as np
from PIL import Image
from import_assets import fetch, material_diffuse_entry, merge_material_entries, png, runtime_specular, save, sha

ROOT = Path(__file__).resolve().parents[2]
RESOURCES = ROOT / 'src/main/resources'
SOURCES = ROOT / 'src/tools/assets/materials'
MATERIALS = {
    'dirt': 'Charlotte Baglioni / Poly Haven',
    'grass_ground': 'Charlotte Baglioni / Poly Haven',
    'asphalt_pit_lane': 'Dimitrios Savva / Poly Haven',
    'concrete_wall_009': 'Charlotte Baglioni / Poly Haven',
    'leafy_grass': 'Charlotte Baglioni / Poly Haven',
    'brown_mud': 'Rob Tuytel / Poly Haven',
    'gravelly_sand': 'Poly Haven',
    'red_brick_03': 'Rob Tuytel / Poly Haven',
    'wood_planks_grey': 'Rob Tuytel / Poly Haven',
}


def prepare(download=False, selected=None, maps=None):
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
            if maps and target not in maps:
                continue
            source = SOURCES / asset / (original + '.png')
            url = f'https://dl.polyhaven.org/file/ph-assets/Textures/png/2k/{asset}/{asset}_{original}_2k.png'
            if not source.exists():
                if not download:
                    raise ValueError('Missing original; explicit --download required: ' + str(source))
                save(source, fetch(url, 64 * 1024 * 1024))
            if target == 'diffuse':
                entries.append(material_diffuse_entry(asset, source, url, author, acquired='2026-09-13'))
                print('Prepared local diffuse intermediate', source.with_name('runtime-diffuse.png').relative_to(ROOT), flush=True)
                continue
            destination = RESOURCES / 'textures/materials' / asset / (target + '.png')
            with Image.open(source) as image:
                if image.size != (2048, 2048):
                    raise ValueError('Expected 2K image: ' + str(source))
                if target == 'normal':
                    normal = np.asarray(image.convert('RGB'), dtype=np.float32) / 127.5 - 1
                    normal /= np.maximum(np.linalg.norm(normal, axis=2, keepdims=True), 1e-6)
                    png(destination, np.rint((normal + 1) * 127.5).clip(0, 255).astype(np.uint8))
                    change = '2K OpenGL tangent normals normalized; RGB8 linear PNG without color metadata'
                else:
                    change = runtime_specular(source, destination, .02, .12)
            entries.append(dict(path=destination.relative_to(RESOURCES).as_posix(),
                sourcePath=source.relative_to(ROOT).as_posix(), sourceUrl=url, author=author,
                license='CC0-1.0', licensePath='licenses/assets/CC0-1.0.txt', acquired='2026-09-13',
                sourceSha256=sha(source.read_bytes()), sha256=sha(destination.read_bytes()),
                transformation=change + '; src/tools/import_environment_materials.py'))
            print('Prepared', destination.relative_to(RESOURCES), flush=True)
    merge_material_entries(entries)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--download', action='store_true', help='Explicitly acquire selected CC0 originals')
    parser.add_argument('--only', nargs='+', choices=MATERIALS, help='Prepare only these local material sets')
    parser.add_argument('--maps', nargs='+', choices=('diffuse', 'normal', 'specular'), help='Prepare only selected map channels, using local originals')
    args=parser.parse_args()
    prepare(args.download,args.only,args.maps)

"""Original offline volumetric VFX authoring; run with Blender 4.5.9 --background --python.

No network, downloaded artwork, runtime baking, or dependency on a build task. The
editable Blender scene and this deterministic recipe are retained as source evidence.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import sys

import bpy
import numpy as np
from mathutils import Vector


ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src/main/resources"
SOURCE = ROOT / "src/tools/assets/combat-vfx"
SIZE, GRID, FRAMES, GUTTER = 2048, 8, 64, 8


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save_pixels(path, pixels):
    image = bpy.data.images.new(path.stem, pixels.shape[1], pixels.shape[0], alpha=True)
    image.pixels.foreach_set(np.ascontiguousarray(pixels, dtype=np.float32).ravel())
    image.filepath_raw = str(path)
    image.file_format = "PNG"
    image.save()
    bpy.data.images.remove(image)


def node(nodes, kind, **values):
    result = nodes.new(kind)
    for key, value in values.items():
        setattr(result, key, value)
    return result


def volume_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.samples = 12
    scene.cycles.use_denoising = False
    scene.cycles.device = "CPU"
    scene.render.resolution_x = scene.render.resolution_y = SIZE // GRID - GUTTER * 2
    scene.render.resolution_percentage = 100
    scene.render.film_transparent = True
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGBA"
    scene.view_settings.view_transform = "Standard"
    scene.world = bpy.data.worlds.new("Neutral bake world")
    scene.world.use_nodes = True
    scene.world.node_tree.nodes["Background"].inputs["Color"].default_value = (.2, .2, .2, 1)
    scene.world.node_tree.nodes["Background"].inputs["Strength"].default_value = .35
    bpy.ops.object.camera_add(location=(0, -7, 0))
    camera = bpy.context.object
    camera.rotation_euler = (Vector((0, 0, 0)) - camera.location).to_track_quat("-Z", "Y").to_euler()
    camera.data.type = "ORTHO"
    camera.data.ortho_scale = 4.5
    scene.camera = camera
    bpy.ops.object.light_add(type="AREA", location=(-3, -4, 4))
    light = bpy.context.object
    light.data.energy = 650
    light.data.shape = "DISK"
    light.data.size = 5
    light.rotation_euler = (-light.location).to_track_quat("-Z", "Y").to_euler()
    bpy.ops.mesh.primitive_cube_add(size=4)
    cloud = bpy.context.object
    cloud.name = "Original turbulent density volume"
    material = bpy.data.materials.new("Animated original smoke and flame")
    material.use_nodes = True
    cloud.data.materials.append(material)
    nodes, links = material.node_tree.nodes, material.node_tree.links
    nodes.clear()
    output = node(nodes, "ShaderNodeOutputMaterial")
    shader = node(nodes, "ShaderNodeVolumePrincipled")
    shader.inputs["Color"].default_value = (.72, .72, .72, 1)
    shader.inputs["Anisotropy"].default_value = .12
    links.new(shader.outputs["Volume"], output.inputs["Volume"])
    tex = node(nodes, "ShaderNodeTexCoord")
    centre = node(nodes, "ShaderNodeVectorMath", operation="SUBTRACT")
    centre.inputs[1].default_value = (.5, .5, .5)
    links.new(tex.outputs["Generated"], centre.inputs[0])
    radius = node(nodes, "ShaderNodeVectorMath", operation="LENGTH")
    links.new(centre.outputs[0], radius.inputs[0])
    edge = node(nodes, "ShaderNodeMath", operation="SUBTRACT")
    edge.inputs[0].default_value = .44
    links.new(radius.outputs["Value"], edge.inputs[1])
    noise = node(nodes, "ShaderNodeTexNoise", noise_dimensions="4D")
    noise.inputs["Scale"].default_value = 5
    noise.inputs["Detail"].default_value = 6
    noise.inputs["Roughness"].default_value = .72
    links.new(tex.outputs["Generated"], noise.inputs["Vector"])
    turbulence = node(nodes, "ShaderNodeMath", operation="MULTIPLY_ADD")
    turbulence.inputs[1].default_value = .58
    turbulence.inputs[2].default_value = -.27
    links.new(noise.outputs["Fac"], turbulence.inputs[0])
    mask = node(nodes, "ShaderNodeMath", operation="ADD")
    links.new(edge.outputs[0], mask.inputs[0])
    links.new(turbulence.outputs[0], mask.inputs[1])
    positive = node(nodes, "ShaderNodeMath", operation="MAXIMUM")
    links.new(mask.outputs[0], positive.inputs[0])
    positive.inputs[1].default_value = 0
    density = node(nodes, "ShaderNodeMath", operation="MULTIPLY")
    # Density cavities and coherent thick lobes produce real volume self-shadow,
    # rather than perturbing only the edge of an otherwise uniform soft sphere.
    billows = node(nodes, "ShaderNodeMapRange")
    billows.inputs["From Min"].default_value = .34
    billows.inputs["From Max"].default_value = .67
    billows.inputs["To Min"].default_value = .04
    billows.inputs["To Max"].default_value = 2.6
    links.new(noise.outputs["Fac"], billows.inputs["Value"])
    sculpt = node(nodes, "ShaderNodeMath", operation="MULTIPLY")
    links.new(positive.outputs[0], sculpt.inputs[0])
    links.new(billows.outputs["Result"], sculpt.inputs[1])
    links.new(sculpt.outputs[0], density.inputs[0])
    density.inputs[1].default_value = 14
    links.new(density.outputs[0], shader.inputs["Density"])
    glow = node(nodes, "ShaderNodeMath", operation="MULTIPLY")
    links.new(sculpt.outputs[0], glow.inputs[0])
    glow.inputs[1].default_value = 0
    links.new(glow.outputs[0], shader.inputs["Emission Strength"])
    color = node(nodes, "ShaderNodeValToRGB")
    color.color_ramp.elements[0].position = .25
    color.color_ramp.elements[0].color = (.65, .025, .001, 1)
    color.color_ramp.elements[1].position = .74
    color.color_ramp.elements[1].color = (1, .78, .22, 1)
    middle = color.color_ramp.elements.new(.53)
    middle.color = (1, .19, .008, 1)
    links.new(noise.outputs["Fac"], color.inputs["Fac"])
    links.new(color.outputs["Color"], shader.inputs["Emission Color"])
    return scene, cloud, noise, density, glow, shader


def bake_family(family, setup):
    scene, cloud, noise, density, glow, shader = setup
    tile, interior = SIZE // GRID, SIZE // GRID - GUTTER * 2
    atlas = np.zeros((SIZE, SIZE, 4), dtype=np.float32)
    for frame in range(FRAMES):
        t = frame / (FRAMES - 1)
        variant = 0
        if family == "blast":
            variant = min(frame // 21, 2)
            t = (frame % 21) / 20 if frame < 63 else 1.0
        seed = {"smoke": 11, "flame": 31, "blast": 51, "dust": 71}[family]
        noise.inputs["W"].default_value = seed + variant * 7.3 + t * (2.1 if family == "flame" else 1.15)
        noise.inputs["Scale"].default_value = (3.3 + t * 2) if family in ("flame", "blast") else 4.0
        cloud.scale = (1, .8, 1.0)
        cloud.rotation_euler = (0, 0, 0)
        density.inputs[1].default_value = 15 * (.85 - t * .4)
        glow.inputs[1].default_value = 0
        shader.inputs["Color"].default_value = (.72, .72, .72, 1)
        if family == "flame":
            cloud.scale = (.72 - t * .15, .65, .8 + t * .3)
            glow.inputs[1].default_value = 7 * (1 - t * .65)
            density.inputs[1].default_value = 5
            shader.inputs["Color"].default_value = (.14, .065, .02, 1)
        elif family == "blast":
            cloud.scale = (.7 + .28 * t + variant * .035, .72, .65 + .32 * t - variant * .035)
            cloud.rotation_euler = (0, variant * .31, 0)
            glow.inputs[1].default_value = max(0, 10 * (1 - t * 1.5))
            density.inputs[1].default_value = 9 + t * 4
            shader.inputs["Color"].default_value = (.19 + t * .25,) * 3 + (1,)
        elif family == "dust":
            cloud.scale = (1, .65, .55 + .18 * t)
            density.inputs[1].default_value = 7 * (1 - t * .4)
        scene.cycles.seed = 15213 + frame + seed * 100
        # Blender's Render Result pixel buffer is empty on some headless builds;
        # save/load the render output explicitly before atlas assembly.
        scratch = ROOT / "build/combat-graphics-work/vfx-bake" / f"{family}-{frame:02}.png"
        scratch.parent.mkdir(parents=True, exist_ok=True)
        scene.render.filepath = str(scratch)
        bpy.ops.render.render(write_still=True)
        rendered = bpy.data.images.load(str(scratch), check_existing=False)
        pixels = np.empty(interior * interior * 4, dtype=np.float32)
        rendered.pixels.foreach_get(pixels)
        pixels = pixels.reshape((interior, interior, 4))
        # Feather the volume domain edge so no billboard rectangle survives.
        yy, xx = np.mgrid[0:interior, 0:interior]
        edge = np.clip(np.minimum.reduce((xx, yy, interior - 1 - xx, interior - 1 - yy)) / 12, 0, 1)
        pixels[:, :, 3] *= edge
        # Extrude RGB into transparent gutters; alpha remains zero. Each mip is
        # clamped inside its frame in the runtime shader to prevent tile bleeding.
        padded = np.pad(pixels, ((GUTTER, GUTTER), (GUTTER, GUTTER), (0, 0)), mode="edge")
        padded[:GUTTER, :, 3] = padded[-GUTTER:, :, 3] = 0
        padded[:, :GUTTER, 3] = padded[:, -GUTTER:, 3] = 0
        x, y = (frame % GRID) * tile, (frame // GRID) * tile
        atlas[y:y + tile, x:x + tile] = padded
        bpy.data.images.remove(rendered)
        print(f"VFX {family} {frame + 1}/{FRAMES}", flush=True)
    target = OUT / "textures/vfx" / f"{family}.png"
    save_pixels(target, atlas)
    return target


def auxiliary():
    size, tile = 1024, 256
    atlas = np.zeros((size, size, 4), dtype=np.float32)
    yy, xx = np.mgrid[0:tile, 0:tile].astype(np.float32)
    x, y = (xx + .5) / tile * 2 - 1, (yy + .5) / tile * 2 - 1
    r, angle = np.sqrt(x*x + y*y), np.arctan2(y, x)
    for cell in range(16):
        # Authored nonuniform muzzle, spark, chip and heat shapes, no source images.
        lobes = .70 + .16*np.cos(angle*(3 + cell % 4) + cell*.41)
        alpha = np.clip(1-r/np.maximum(.1, lobes), 0, 1)**(1.1 + cell%3*.4)
        if cell >= 8:
            alpha = np.exp(-x*x*75-y*y*3)*np.clip(1-r,0,1)
        color = np.stack((np.ones_like(r), .40 + .45*(1-r).clip(0,1), .06 + .35*(1-r).clip(0,1), alpha), axis=-1)
        px, py = (cell % 4)*tile, (cell//4)*tile
        atlas[py:py+tile, px:px+tile] = color
    path = OUT / "textures/vfx/auxiliary.png"
    save_pixels(path, atlas)
    return path


def main():
    (OUT / "textures/vfx").mkdir(parents=True, exist_ok=True)
    (OUT / "vfx").mkdir(parents=True, exist_ok=True)
    SOURCE.mkdir(parents=True, exist_ok=True)
    setup = volume_scene()
    bpy.ops.wm.save_as_mainfile(filepath=str(SOURCE / "combat-volumes.blend"))
    textures = [bake_family(name, setup) for name in ("smoke", "flame", "blast", "dust")]
    textures.append(auxiliary())
    recipes = {
        "schemaVersion": 1, "frames": FRAMES, "grid": GRID, "gutter": GUTTER,
        "atlasSize": SIZE, "auxiliarySize": 1024, "residentLimitBytes": 128*1024*1024,
        "blastVariants": 3, "blastFramesPerVariant": 21,
        "explosions": {
            "homing": [1.0, .075, .45, 1.6, 18, 8, 8],
            "power": [1.3, .09, .62, 2.1, 24, 12, 12],
            "napalm": [.9, .06, .5, 1.3, 14, 5, 5],
            "cannon": [.85, .055, .32, 1.1, 12, 18, 8],
            "mine": [1.15, .075, .5, 1.7, 20, 16, 14],
            "ballistic": [1.2, .08, .64, 2.2, 28, 18, 14],
            "destroyed": [1.5, .10, .78, 2.6, 30, 22, 16],
            "special-bomb": [1.4, .09, .70, 2.3, 26, 20, 16]
        },
        "explosionFields": ["scale", "flashSeconds", "flameSeconds", "smokeSeconds", "flames", "fragments", "dust"],
        "origin": "ORIGINAL_PROJECT_CONTENT", "seed": 15213,
        "lighting": "Neutral volume density bake; scene key/fill and alpha-gradient normals at runtime",
        "colorSpace": "sRGB RGB; linear alpha", "atlasLayout": "bottom-left frame zero, eight columns",
        "bake": "Cycles procedural 3D density and emission; 12 samples; original animated noise, no external images"
    }
    recipe_path = OUT / "vfx/recipes.json"
    recipe_path.write_text(json.dumps(recipes, indent=2)+"\n", encoding="utf-8")
    source = Path(__file__).resolve()
    provenance = {
        "schemaVersion": 1, "origin": "ORIGINAL_PROJECT_CONTENT", "generator": str(source.relative_to(ROOT)).replace("\\", "/"),
        "generatorSha256": digest(source), "tool": bpy.app.version_string,
        "sourceScene": "src/tools/assets/combat-vfx/combat-volumes.blend",
        "sourceSceneSha256": digest(SOURCE / "combat-volumes.blend"),
        "licensePermission": "Original Wreck Riff project content; redistribution with the game permitted",
        "externalImages": False, "artisticStatus": "NEEDS_CREATIVE_REVIEW",
        "assets": [{"path": str(p.relative_to(OUT)).replace("\\", "/"), "sha256": digest(p), "bytes": p.stat().st_size} for p in textures+[recipe_path]]
    }
    (OUT / "vfx/provenance.json").write_text(json.dumps(provenance, indent=2)+"\n", encoding="utf-8")


if __name__ == "__main__":
    main()

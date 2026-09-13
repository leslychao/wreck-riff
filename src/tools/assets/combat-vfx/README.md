# Original combat VFX sources

`src/tools/author_combat_vfx.py` is the editable recipe. `combat-volumes.blend`
retains the authoring scene. Both are original project content; no external images
or downloaded game assets enter these textures. The previous analytical runtime
is preserved only as source history under `history/`.

Rebuild explicitly, outside Gradle, using the locally installed Blender 4.5.9:

```powershell
& build/tools/blender-4.5.9-windows-x64/blender.exe --background --factory-startup --python src/tools/author_combat_vfx.py
```

The CPU Cycles recipe bakes four 2048×2048 RGBA atlases (smoke, flame, blast,
dust), each with 64 frames in an 8×8 grid. Each 256px tile contains 240px of
rendered volume and an 8px transparent gutter. Frame zero is bottom left;
OpenGL UV increases right and up. The auxiliary 1024px atlas contains original
flash/spark silhouettes. RGB is sRGB; alpha is linear coverage. Runtime loads
the finished PNGs with mipmaps and trilinear minification; builds perform no bake
or network access. Total calculated RGBA8 mip residency is 95,070,884 bytes
(90.67 MiB), below the 128 MiB atlas budget. This is not driver VRAM telemetry.

`vfx/recipes.json` is generated with the atlases. It specifies dimensions, frame
count, layout, and explosion scale, flash/flame/smoke duration and emission counts.
`vfx/provenance.json` records SHA-256 of every exported asset, the generator and
editable scene. `CombatVfxAssetsVerifier` checks hashes, complete asset membership,
alpha, tile gutters, dimensions and memory. Missing files and Git LFS pointers
fail before packaging. Updating the authoring recipe requires rebaking the whole
set so provenance stays reproducible. No LFS service is needed for this set.

World units are metres, +Y up and vehicle +Z forward. The volume bake is camera
space artwork; `CombatVisuals` owns world-space positions, velocities and sizes.
Five atlas textures are shared by the alpha and additive batches. The alpha
batch contains animated smoke/fire; additive contains brief flashes, sparks and
tracers. Fragments use the existing Lighting material with vertex colours and
normals. Prepared vehicle panels use shared read-only meshes (maximum 96 triangles)
inside that same fragment batch. There is no fragment PhysicsSpace.

Particle count remains 768; shards 192; MG shots 128; shield flares 24; cosmetic
fire sites 16; lights 2. Static-only fragment sweeps are limited to 24 per frame
and tracked tracer obstruction checks to 16. Overloaded near debris retires rather
than advancing through an unqueried wall. The expanded fragment vertex buffer
holds 55,296 vertices plus 1,152 shield-flare vertices; this accommodates shaped
panels without adding a draw call per part. Runtime counters report actual and
peak occupancy separately from these ceilings.

The soft pass is appended to the existing FilterPostProcessor and copies scene
colour into a separate depthless target before drawing the translucent batch.
It samples the original scene depth and averages individual MSAA sample coverage;
it never samples a depth attachment that it is writing. It remains active with
bloom disabled. Heat distortion is intentionally outside this pipeline.

`CombatVfxReview` is a short real-window shader and attachment fixture: MSAA4,
bloom off/on, an off-axis camera, single sample, and close/rebind. Its captures
confirm the real GL path only. Full-match captures and release benchmarks remain
the acceptance evidence; neither a bake nor this fixture grants creative approval.

Objects-only bloom runs before the VFX composite: the particles themselves do not
receive bloom. Their baked temperature/front contrast and two world lights provide
brightness directly. Changing MSAA recreates the single FPP to release stale
single-sample/MS attachments in jME 3.8.1; ordinary frames and Retry do not.

The blast atlas now contains three coherent 21-frame variants (frame63 is unused);
other volume atlases keep64frames. Density cavities and turbulent lobes are baked
in3D. Burst clipping captures six static directions once, at most30queries per
frame across all bursts, and shares the resulting world planes among particles.
Particles slide at walls/ceilings; if capture budget is exhausted they stay at
the last known point and fade quickly. This is a bounded local plane estimate,
not full collision of every billboard against arbitrary concave geometry.

# Original combat VFX sources

`src/tools/author_combat_vfx.py` is the editable recipe. `combat-volumes.blend`
retains the authoring scene. Both are original project content; no external images
or downloaded game assets enter these textures. The previous analytical runtime
is preserved only as source history under `history/`.

Rebuild explicitly, outside Gradle, using the locally installed Blender 4.5.9:

```powershell
& build/tools/blender-4.5.9-windows-x64/blender.exe --background --python-exit-code 1 --python src/tools/author_combat_vfx.py
# Microsoft JDK 21, after installing the pinned local texconv described below:
./gradlew.bat --offline prepareCombatVfx
```

The CPU Cycles recipe bakes four 2048×2048 RGBA atlases (smoke, flame, blast,
dust), each with 64 tiles in an 8×8 grid. Smoke, flame and dust contain 64 frames;
blast contains three coherent 21-frame variants and one unused tile.
Each 256px tile contains 240px of
rendered volume and an 8px transparent gutter. Frame zero is bottom left;
OpenGL UV increases right and up. The auxiliary 1024px atlas contains original
flash/spark silhouettes. RGB is sRGB; alpha is linear coverage. The four lossless PNGs remain here as editable bake sources. Runtime loads only
legacy DDS DXT5/BC3 with twelve offline mip levels; the 1K auxiliary remains PNG
with generated mips. All use trilinear minification, explicit sRGB image colour
space and TextureKey flipY=false. jME 3.8.1 uploads native sRGB DXT5 (GL0x8c4f);
the render path fails clearly if S3TC is unavailable. Explicit gradients cap
atlas filtering at mip 3, where the transparent gutter remains one pixel wide.
Builds perform no bake
or network access. Total calculated mip residency is 27,962,132 bytes (26.67 MiB), below the
unchanged 128 MiB atlas budget. The previous RGBA8 set occupied 95,070,884 bytes
(90.67 MiB). Four full DDS payloads retain 22,369,728 CPU bytes instead of
67,108,864 base RGBA bytes. These are format payload calculations, not RSS or
driver VRAM telemetry.

`vfx/recipes.json` is generated with the atlases. It specifies dimensions, frame
count, layout, and explosion scale, flash/flame/smoke duration and emission counts.
`bake-provenance.json` here records the source PNGs, recipe and editable scene.
Runtime `vfx/provenance.json` links that exact bake to the encoder source, settings,
tool SHA, MIT license, all runtime assets, decoded quality metrics and repeated
compressed hashes. `CombatVfxAssetsVerifier` checks hashes, complete asset membership,
decoded BC3 alpha, zero-alpha tile gutters, all twelve mip payloads, dimensions,
source/encoder evidence and memory. Verification does not run the encoder. Missing files and Git LFS pointers
fail before packaging. Updating the authoring recipe requires rebaking the whole
set so provenance stays reproducible. No LFS service is needed for this set.


Explicit compression requires the official Microsoft DirectXTex **may2026**
`texconv.exe` in `build/asset-tooling/DirectXTex/` (or `-PtexconvExecutable=...`).
Download is a separate preparation step, never a Gradle dependency:

- Binary: https://github.com/microsoft/DirectXTex/releases/download/may2026/texconv.exe
- Required SHA256: `dcfdec10244e02cf5037fba089c55fb7e1326b1c8181742d77d15fa5cb5eef06`
- MIT source license: https://raw.githubusercontent.com/microsoft/DirectXTex/may2026/LICENSE
- Redistributed notice: `src/main/resources/licenses/DirectXTex-MIT.txt`
- Pinned command: `-nologo -f BC3_UNORM_SRGB -srgb -dx9 -m 0 -nogpu -if BOX -sepalpha -y`.

Legacy DXT5 is intentional: this jME DDSLoader does not support DX10 BC3 sRGB
metadata. The legacy texture is explicitly marked sRGB on load, so RGB is decoded
once and alpha stays linear. Offline Y flip is unnecessary for the existing VFX
coordinates; native A/B verifies orientation and subframe interpolation.
BC7 CPU encoding was rejected for this artwork: its measured alpha maximum error
was 124/255 versus 13–14/255 with BC3, and it introduced nonzero gutters. Both use
8 bits per pixel. These are compression choices, not a change to the renderer.

The converter runs CPU encoding twice for every atlas and rejects differing DDS
SHA256. Before publishing anything, it decodes each base mip and checks alpha
RMSE <=1.5/255, maximum <=20/255, visible RGB RMSE <=8/255, linear composite over
18% grey RMSE <=0.009 and exactly zero alpha in source and decoded gutters. The
current set measured alpha RMSE 1.008–1.169/255 and maximum 13–14/255. Native
comparison remains necessary: numerical limits do not prove artistic approval.
Temporary outputs stay in a unique build directory; complete files are published
atomically with bounded Windows replacement retries and provenance last. The old
runtime PNGs are removed only after their exact bytes have been preserved here.

PNG, JSON and Blender exports first write a unique temporary file in the same
directory, preserving its format suffix. Only a nonempty finished export replaces
the previous file. Sharing conflicts receive at most six attempts and 1.5 seconds
of total backoff; failure preserves the previous destination. Provenance publishes
last, so verification rejects a mixed set after an interrupted multi-file bake.

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

Closing a match explicitly releases the 21 private vertex buffers of the four
batches through jME's native object queue; buffers never uploaded are released
directly. Shared atlas images and prepared ordnance/panel meshes remain cached
for Retry. Closing the soft pass also disposes its private colour target.

The soft pass is appended to the existing FilterPostProcessor and copies scene
colour into a separate depthless target before drawing the translucent batch.
It samples the original scene depth and averages individual MSAA sample coverage;
it never samples a depth attachment that it is writing. It remains active with
bloom disabled. Heat distortion is intentionally outside this pipeline.

`CombatVfxReview` is a short real-window shader and attachment fixture: MSAA 0/2/4/8,
bloom off/on, an off-axis camera, actual 4:3/16:9 window resize, and close/rebind.
It asserts actual depth attachment sample counts and fails the process on any
render exception, missing stage, or timeout. Its fixed-time hot and smoke captures
confirm the real GL path only. Full-match captures and release benchmarks remain
the acceptance evidence; neither a bake nor this fixture grants creative approval.

Objects-only bloom runs before the VFX composite: the particles themselves do not
receive bloom. Their baked temperature/front contrast and two world lights provide
brightness directly. Changing MSAA recreates the single FPP to release stale
single-sample/MS attachments in jME 3.8.1; ordinary frames and Retry do not.

Density cavities and turbulent lobes are baked in 3D. Burst clipping captures six
environment directions once, at most 30 sweeps per frame across all bursts and
critical smoke emitters. The radius includes maximum authored velocity times
lifetime and the sprite extent. Particles share the resulting world planes;
bounded repeated projection handles acute corners as well as walls and ceilings.
If the capture budget is exhausted, particles stay at the known origin and fade
within 0.13 seconds. Environment pose/incarnation stamps are read once per named
surface per frame. A removed or moved boundary retires its old gas particles in
place within 0.13 seconds, without additional sweeps. Critical smoke caches are
refreshed after movement, invalidation, inherited-speed growth, or 0.5 seconds.
Their radius covers the actual socket offset, spawn jitter, inherited vehicle
velocity, maximum rise over the full lifetime, and sprite extent. Settled fragments
keep their final contact pose while fading, without further motion or queries.
The VFX GPU timestamp interval includes resolve, particles and the final fullscreen
composite; exceptions close its pending interval as well. FireSurface topology caching
is separate and is unchanged by particle animation.

This is a bounded local plane estimate for bursts and critical smoke, not full
collision of every billboard against arbitrary concave geometry. Short flame jets
from existing ground danger surfaces retain their surface normals and soft depth
intersections. The soft depth fade itself is rendering coverage, not collision.

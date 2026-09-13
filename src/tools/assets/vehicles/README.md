# Offline vehicle sources

These are original Wreck Riff models and texture recipes. No external geometry or
bitmap sources are used. Runtime builds load the checked-in J3O/DDS/PNG files and do
not invoke Blender, fetch assets, or download from Git LFS.

`author_vehicle_models.py` runs in Blender 4.5.9 LTS. Each `source.blend` contains
three LODs (`lod` object property), canonical UVs, the intact `Basis`, four
`damage-N` shape keys and eight `region-N` shape keys. Moving weapon parts retain
their `runtimePart` property. All coordinates are metres; exported jME uses
X right, Y up, Z forward.

To rebuild the original recipe, run `tools/prepare-combat-assets.ps1 -Bake` from
the repository. The explicit Gradle `prepareVehicleModels` task converts already
prepared local sources. It validates `source.json` before conversion, including
the generator hash and every Blender/GLB/companion/texture export hash. A stale or
edited source fails instead of silently reusing an old companion.

To edit a saved model, open its `source.blend` in Blender and retain the three
LODs, names, UVs and shape keys. Edit the relevant intact/damage/region poses and
save the file. Then reexport that actual saved scene:

```powershell
build/tools/blender-4.5.9-windows-x64/blender.exe --background --factory-startup --python-exit-code 1 --python src/tools/author_vehicle_models.py -- --reexport rivet
./gradlew.bat --offline prepareVehicleDiffuse prepareVehicleModels
./gradlew.bat --offline verifyAssets
```

Use Microsoft JDK 21 for Gradle. Replace `rivet` with the chosen profile. The
reexport reads mesh vertices, canonical UV loops and every shape key from the
saved Blender scene, writes a new GLB and the three-LOD canonical companion,
then records their hashes. Run conversion after reexport; running `-Bake` would
regenerate the original recipe and replace manual scene edits.

The companion preserves exact triangle/UV correspondence and eight sparse
regional deltas. The LOD0 meshes share packed, nonoverlapping UV islands. Lower
LOD triangles receive the corresponding canonical projection offline. A 512
pixel ownership image clips each brush footprint to its authored part, including
when a footprint reaches another island. Bullet craters, chips, scratches and
glass fractures come from the shared original damage stamp atlas and are
resolved into the per-vehicle mask without extra draw calls.

J3O contains the five immutable poses with normals, tangents
and initial collision acceleration data prepared offline. Runtime owns two
mutable morph composites; it never invokes Blender or MikkTSpace. Local contacts
use the intact canonical triangles, so marks stay attached through damage and
LOD changes. Detached doors/hood/trunk use the prepared LOD2 silhouette, at most
96 triangles per panel.

Paint diffuse/normal are 2048 pixels; paint specular and the three metal maps
are 1024 pixels. The metal maps and damage stamp atlas are shared by all six
profiles. Editable source PNGs retain RGBA8. Opaque diffuse maps use BC7 sRGB DDS,
prepared explicitly by `prepareVehicleDiffuse` with the pinned Microsoft
DirectXTex may2026 CPU encoder. The locally installed executable is passed through
`-PtexconvExecutable=...`; preparation and ordinary builds never download it.
`tools/prepare-combat-assets.ps1` accepts the same path as `-TexconvExecutable`.
The encoder runs twice and requires byte-identical DDS, then decodes the base mip
and checks RGB RMSE <=1.2/255, maximum error <=16/255 and opaque source alpha.
Seven source/runtime hashes, exact arguments, quality, tool SHA and MIT license
are recorded in `textures/vehicles/diffuse-provenance.json`. Ordinary builds use
these prepared outputs and do not invoke the encoder. The model converter replaces
every serialized bank material with the DDS key and removes the seven retired
runtime diffuse PNGs only after checking they match the preserved source pixels.

BC7 carries its complete mip chain. The original vehicle PNG loader flips Y;
since jME 3.8.1 cannot flip BC7 blocks, the encoder flips once offline and the DDS
TextureKey disables flipping. DX10 BC7_UNORM_SRGB already selects OpenGL's sRGB
internal format. Both Image ColorSpace and the material DiffuseMap requirement
are Linear to avoid an extra sRGB lookup; the GPU still performs sRGB decoding.
Native A/B verifies UVs and appearance, independently of CPU compression metrics.

Normal uses RGB8 and specular uses L8 when all three source channels are exactly
equal. Their converter requires constant opaque alpha, preserves every normal
RGB/scalar sample and performs no resizing or color conversion. In
particular, grayscale samples are written through the raster without AWT's gray
gamma transfer. Ownership and damage atlas images retain RGBA8. The verifier
checks every retained sample against the source and budgets the actual channels.
Each profile retains its own canonical ownership image. Normal/specular and the runtime 512 pixel
damage mask are linear. Verification checks checksums, pointer files, five-pose
topology, all LOD triangle limits and the combined vehicle/VFX decoded texture
budget. These contracts do not establish creative approval.

The `history` directory preserves the replaced procedural vehicle builders.
They are source history, not a second active runtime implementation. New Blender
backup files are disabled; the editable sources are versioned directly.

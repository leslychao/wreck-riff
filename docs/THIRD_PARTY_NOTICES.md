# Third-party notices

The game uses unmodified dependency JARs and their native libraries. `verifyAssets`
records the exact filenames, Maven coordinates and SHA-256 values in
`build/reports/assets/third-party/dependency-evidence.json`. The Windows package
includes only Windows x64 native classifiers; it retains the original JAR contents.

License text presence is a technical evidence check, not a claim of legal approval.
Missing license evidence fails `verifyAssets`. Artistic approval is separate.

| Component | Evidence retained with the build |
|---|---|
| jMonkeyEngine 3.8.1-stable | Upstream BSD 3-Clause [license](https://github.com/jMonkeyEngine/jmonkeyengine/blob/v3.8.1-stable/LICENSE.md) |
| Minie 9.0.3 | Upstream [license and asset exceptions](https://github.com/stephengold/Minie/blob/9.0.3/LICENSE); no Minie example assets are used |
| Heart 9.2.0 | [BSD notice](https://github.com/stephengold/Heart/blob/9.2.0/license.txt) plus the separately noted Icosphere source header |
| Libbulletjme 22.0.3 | [Combined BSD, zlib and MIT notices](https://github.com/stephengold/Libbulletjme/blob/22.0.3/LICENSE) for glue, Bullet and decomposition code |
| Gson 2.13.1 / Error Prone annotations 2.38.0 | Pinned Apache 2.0 texts |
| sim-math 1.6.0 / lwjgl3-awt 0.2.3 | Notices extracted from their original JAR entries |
| LWJGL 3.3.6 | [BSD notice](https://github.com/LWJGL/lwjgl3/blob/3.3.6/LICENSE.md), and upstream notices for libffi, liburing, GLFW, jemalloc, OpenAL Soft and Khronos headers |
| SLF4J 1.7.32 | Pinned MIT notice |
| Microsoft OpenJDK 21 runtime | Original `runtime/legal` directory and runtime `release` metadata retained by jpackage |

The checked-in `src/main/resources/licenses/license-index.json` gives the exact
pinned source URL and checksum of every captured upstream document. The maintenance
command `tools/refresh-license-evidence.ps1` can refresh this evidence deliberately;
normal builds never access the network to retrieve license text.

OpenAL Soft is a dynamically loaded LGPL library. The upstream LWJGL distribution
includes its [GNU Library GPL text](https://github.com/LWJGL/lwjgl3/blob/3.3.6/modules/lwjgl/openal/openal_soft_license.txt).
Before external publication, the distributor must verify the corresponding-source
delivery arrangement for the exact native build and preserve recipients' ability
to replace/debug the library. This project does not make an unsupported source
offer on the owner's behalf. Local packaging reports keep this item as
`REVIEW_REQUIRED`; a technical ZIP is not an approval to publish it.

## Licensed game assets in 0.2

**Metalmania** by **Kevin MacLeod** (incompetech.com), ISRC USUAN1700023.
Source: https://incompetech.com/music/royalty-free/index.html?Search=Search&isrc=USUAN1700023
License: **Creative Commons Attribution 4.0 International**,
https://creativecommons.org/licenses/by/4.0/ ; full text: `licenses/assets/CC-BY-4.0.txt`.
Changes: MP3 decoded to stereo PCM 48 kHz / 16-bit; edited to a 179.2-second loop with
100 ms equal-power crossfade; DC removed; linear peak normalized to 0.84.
The original MP3, decoded local PCM and creator catalog entry are retained in
`src/tools/assets/audio`; generated `audio/music-source.json` and
`audio/music-provenance.json` bind sources and output by SHA-256. This attribution
does not suggest that Kevin MacLeod endorses Wreck Riff. Retain this credit, source,
license and modification notice when redistributing the music or the game.

**Poly Haven textures, CC0 1.0**. Source files may be redistributed and adapted under
https://creativecommons.org/publicdomain/zero/1.0/ ; retained legal text:
`licenses/assets/CC0-1.0.txt`. Poly Haven policy: https://polyhaven.com/license .

| Material | Author | Source |
|---|---|---|
| Asphalt 02 | Rob Tuytel | https://polyhaven.com/a/asphalt_02 |
| Cracked Concrete | Dimitrios Savva | https://polyhaven.com/a/cracked_concrete |
| Metal Plate 02 | Rob Tuytel | https://polyhaven.com/a/metal_plate_02 |
| Blue Metal Plate | Rob Tuytel | https://polyhaven.com/a/blue_metal_plate |
| Rusty Metal 03 | Amal Kumar | https://polyhaven.com/a/rusty_metal_03 |

All selected sources are 2K PNG. Diffuse maps retain their source bytes. OpenGL normal vectors are normalized per
texel after source downsampling and color metadata is removed from the linear data
PNG; the unmodified source maps are retained separately. Roughness is transformed into a restrained Phong specular-strength mask:
`0.025 + k * (1 - roughness)^2`, with k=0.70 for metal_plate_02/blue_metal_plate and
k=0.22 for the other three materials. This is a rendering approximation for the
existing Lighting.j3md, not a metallic-roughness PBR conversion. Neutral paint/rubber
diffuse derivatives come from blue_metal_plate; the renderer owns their final tint.
Exact original/download URLs, SHA-256 values and transformations for all 17 texture
outputs are in `licenses/asset-provenance.json`. Original maps remain under
`src/tools/assets/materials`. No Poly Haven webpage previews, logos or example
renders are included as game assets. CC0 does not require credit; provenance is
retained voluntarily for maintenance.

**Roboto Condensed Regular and Bold**, The Roboto Project Authors, **SIL OFL 1.1**.
Official source: https://github.com/googlefonts/roboto-3-classic/releases/tag/v3.016
Source archive: https://github.com/googlefonts/roboto-3-classic/releases/download/v3.016/Roboto_v3.016.zip
Retained notice: `licenses/assets/Roboto-OFL.txt`.
The static local TTFs are converted by GenerateFont to 48 px grayscale-antialiased,
2x supersampled bitmap atlases containing ASCII and the complete Russian alphabet.
The fonts and their generated font derivatives remain under OFL, not the game's
source-code license. Include the copyright and OFL notice with every copy, do not
sell the font alone, and respect any reserved font names for modified versions.
No system-font or online font service is used.

## Recorded combat effects in 0.3 and 0.4

The Free Firearm Sound Library: **Ben Jaszczak, Brian Nelson, Kevin Heras and
Matthew Nanney**, [source and CC0 declaration](https://opengameart.org/content/the-free-firearm-sound-library).
Approved prepared archive: https://opengameart.org/sites/default/files/Prepared%20SFX%20Library.7z
SHA-256: `cc1ab5a99a0a365105c7c5dd783f4b0b1fe90938114d3ceec53856bfe005f7d6`.
Selected single-shot recordings: AK-47/C_28P.wav, AR-15/D_32P.wav and
Carl Gustav M45/G_31P.wav. These contain recorded firearms, not recorded metal plates.

25 CC0 bang / firework SFX: **rubberduck**,
[source and CC0 declaration](https://opengameart.org/content/25-cc0-bang-firework-sfx).
Archive: https://opengameart.org/sites/default/files/25-CC0-bang-sfx.zip
SHA-256: `c0c9ecc11e2dc0d190f0cced755569858234b8528286bc83becb6314c663407f`.

Both sources use CC0 1.0; retained text `licenses/assets/CC0-1.0.txt`. CC0 does not
require attribution. We retain creator names and origin voluntarily, without implying
endorsement. Selected original bytes and decoded mono PCM are retained in
`src/tools/assets/audio/recorded`. The game packages only processed effects and
`audio/sfx-sources.json` / `audio/sfx-provenance.json`, with per-file source/output
hashes and explicit edits: trimming, downmix/resampling, filters, speed changes,
pre-baked transient/body/debris layers, compression, DC removal and normalization.
Layering is performed locally by `src/tools/prepare_recorded_sfx.py`; no decoder,
asset download or account is required at build/runtime. Designed ice/metal/shield
sounds are recording derivatives, not claims about what object was originally recorded.
Version 0.4 uses these same preserved recordings for harder blast/metal layers and
three takes each of cannon launch/ricochet/hit, ballistic launch/fall/explosion and
ram crush. No new external recordings were imported. The current preparation script
SHA-256 is linked from sfx-provenance.json; superseded 0.3 recipes, output hashes and
metrics remain in docs/asset-history/audio-0.3, excluded from runtime.

Original procedural vehicle/arena geometry, shader code and ancillary sound effects
remain project-authored. Recorded combat effects are identified separately above. Superseded score and 5x7 glyph recipes are preserved only as
historical source in `docs/asset-history`, excluded from compilation and runtime.
The generated registry distinguishes licensed recordings/textures/fonts from these
original recipes. Technical evidence does not grant artistic or legal approval;
owner creative review and the existing native-library distribution review remain.

Result stings (Victory, Defeat, Draw) also adapt **Metalmania**, Kevin MacLeod,
CC BY 4.0: short mono guitar/drum excerpts, filtering, release envelopes and a
continuous slowdown for Defeat, mixed with a CC0 recorded metal impact. The
main music loop is unchanged. Source/output hashes and exact transformations
are retained in `audio/result-provenance.json`; retain the Metalmania credit,
source URL and license notice above when redistributing these stings.

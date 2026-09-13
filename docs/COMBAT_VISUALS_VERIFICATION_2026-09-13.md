# Combat graphics: evidence ledger, 2026-09-13

Status: implementation and verification in progress. This ledger does not grant
FEEL_APPROVED, MVP_ACCEPTED, or release acceptance. Paths below are relative to the
repository; artifacts in `build/` are local evidence and are not runtime assets.

## Hardware and measurement

`build/combat-graphics-work/hardware.json`: Intel Core i9-14900F (24 cores / 32
logical processors), 68,490,100,736 bytes system RAM, NVIDIA RTX 5080, driver 610.62,
16,303 MiB reported GPU memory. Microsoft JDK 21.0.11. The target RTX 3060 class
machine is unavailable on this host; its performance remains unverified.

Frame and simulation measurements come from the real game. Windows process
PeakWorkingSet includes loading; Java heap and allocation samples do not replace
that measure. Profile runs also sample the process-specific WDDM GPU memory
counter. It reports driver accounting, not an independent physical VRAM residency
audit. GPU timestamps are asynchronous, and measured-combat query coverage is
reported separately from warmup/loading.

## Preserved before and intermediate after

The original empty-ammunition showcase failure is preserved. The corrected
diagnostic-only ammunition baseline is
`build/combat-graphics-work/before-userdata/WreckRiff/diagnostics/run-12afd2b5-e296-4fcc-abd0-cf4057314a73/`.
Its 19 core captures use 1600x900, MSAA4, bloom, seed 42. Exact before-camera
matrices were not recorded and are marked UNKNOWN in the comparison viewer.

`build/combat-graphics-work/review-sixth/index.html` compares those captures with
the sixth image's real 49-second arsenal showcase (29 captures, including the
Freeze/Ballistic combo). The sound video reconstructs allocated game voices from
the event log; it is not an OpenAL device recording. The intermediate immutable
JAR SHA-256 is
`28a6a3d37b967abb99bb6039448e4f9ee952b14b03934ed862700576f3dd9b07`.

`build/combat-graphics-work/review-vehicles/index.html` contains 55 native captures
of all six authored profiles, five HP stages, directional damage, statuses and
LODs. The underlying report validates actual two-target GPU morphing (1,146
assertions), source identity and PNG SHA-256. This gallery supplements gameplay.

The ninth image's real moving chase-camera capture is
`build/combat-graphics-work/moving-captures/dead-air-yard-bad0fed9fe07430b825f3f1b55e31ab3/moving-combat.mp4`.
The engine rendered and the decoder verified 1,200 frames / 19.9992 seconds.
Samples at 3, 12 and 19 seconds show real driving, enemies, a projectile and
advancing HUD; the sample review is in `visual-review.json`. This is a sparse
combat interval, not coverage of the whole arsenal. It is silent and is not
performance evidence. JAR SHA-256:
`e089883c5363b43cb2e79052dd5b567e7c0e982ca8f635e6789178a9ba3e180a`.
The earlier GDI recording froze on loading and remains explicitly rejected in
its separate `dead-air-yard-7bce56466048445c884a1813bad27b3f` directory.

## Loading-memory work and short profile

The fifth image peaked at 2,230,018,048 process working-set bytes. Streaming
regional deformation data, sharing metal textures, releasing owned visual
buffers, losslessly reducing vehicle PNG channels and cleaning old private
post-processing targets on resize reduced the eighth image peak to
1,810,067,456 bytes. That still **fails** the 1.5 GiB limit (1,610,612,736 bytes).
No gate or heap limit was raised. The subsequent compression and measurements
are recorded below.

Vehicle PNG channel conversion preserved exact diffuse/normal RGB and specular
luminance samples. The six-profile CPU image bank shrank from 228 to 157 MiB.
Two fresh native 55-capture runs compared RGBA sources against the new formats:
14 images were byte-identical; the others differed by at most 1/255 in at most
0.00903% of pixels. Actual GPU memory savings are not inferred from CPU payload.
Evidence: `build/combat-graphics-work/vehicles/residency/`.

Latest short profile before DDS integration:
`build/local-world-runs/benchmark-dead-air-yard-64a1435a68c1493d898d6498054bacb7/`.
Immutable JAR SHA-256:
`be482f88c8a4a369457078ab1a87211e4faf3867c42adc8a68f7e358e2aa9e9b`.
This was a real 1920x1080/MSAA4, sound-on, VSync-off window, 30 active seconds
warmup plus 60 measured active seconds, no video, detailed profiling enabled.

| Measure | Observed |
|---|---:|
| Measured active frames | 26,759 |
| Frame p95 / p99 / maximum | 3.10 / 3.60 / 24.6285 ms |
| Combat frames above 100 ms / lost simulation time | 0 / 0 |
| Conservative visual-update CPU p95 | 0.55 ms |
| Whole-scene GPU p95 | 0.55 ms |
| VFX composite GPU p95 | 0.10 ms |
| Peak process working set, including loading | 1,810,067,456 bytes — FAIL |
| Sampled peak process WDDM dedicated memory | 1,134,100,480 bytes |
| Peak particles / fragments / lights | 596 / 104 / 2 |

CPU sums disjoint presentation/contact/scene-update intervals per frame before
calculating percentiles. It includes HUD, audio and camera, but not render
submission. Whole-scene GPU timing bounds VFX GPU cost on this hardware;
the composite alone is not all VFX work. The GPU whole-frame series has 26,758
completed samples and one discarded query; the VFX series has 26,759 completed
samples. Neither series skipped submissions or retained pending queries.

This short profiled run does not substitute for the final four-map 600-second
unprofiled package benchmark or the 1,800-second soak. Those runs, current full
`test`, `physicsTest`, `verifyAssets`, final DDS visuals and final package identity
remain to be recorded after the shared source and resource preparation stabilize.

## Completed compression and release-tool checks

All four VFX atlas families now use native BC3 DDS with a complete offline mip
chain. Their previous PNG bytes remain in the editable-source directory. Exact
format payload including the auxiliary texture changed from 95,070,884 to
27,962,132 bytes; this is not a process-RSS or driver-memory measurement. Repeat
CPU encodes were byte-identical. Alpha RMSE is 1.008–1.169/255, maximum alpha error
13–14/255 and every base-level tile gutter remains fully transparent.
`build/combat-graphics-work/vfx/bc7-prototype/native-all4/complete.json` records
16 native day/night comparison stages, actual sRGB DXT5 uploads and source hashes.
The root inspected smoke/day pairs plus flame/night and dust/day; no new obvious
rectangle, orientation or edge artifact was observed. The earlier 72 scoped VFX
tests passed; the final combined suite is recorded separately.

All seven vehicle paint/metal diffuse maps now use BC7; the six model banks refer
only to DDS. Normal/specular/mask resources remain lossless. Every map passed two
identical CPU encodes and a decoded-color check (RGB RMSE 0.430–0.529/255,
maximum channel error 2–7/255). The native six-profile image bank changed from
157 to 115.3335 MiB. The complete vehicle/VFX/16-mask mip budget is 194.0003 MiB.
These figures describe retained format payload, not total working set.

`build/combat-graphics-work/vehicles/bc7-all-review/complete.json` records 55 native
captures and 1,146 actual GPU morph assertions. Thirty focused tests passed; the
offline model conversion repeated with all 42 output SHA-256 values unchanged.
The final gallery is `build/combat-graphics-work/review-vehicles-bc7/index.html`.
Additional optional rear-body art work was cancelled when the owner asked to
finish the working version; no further geometry changes were introduced.

Release tooling now requires the Windows lifetime PeakWorkingSet counter,
including a final read through the retained process handle after exit. Old
memory reports without that evidence cannot produce a release PASS. Soak counts
must match paired LOAD/UNLOAD records, including all four maps and seven modes.
The updated tools passed 193 PowerShell 5.1 assertions and four SoakSchedule tests.

Packaged showcase verification records the immutable ZIP/JAR, requested render
conditions and all 29 exact capture labels. Export copies only verified paths,
decodes full source/output media and checks size/FPS/duration/frame preservation.
Twelve media regressions passed, including rejection of truncated AVI/WAV. The
existing sixth image's complete source video contained 1,469 frames at 30 FPS,
48.966177 seconds; its MP4 preserved all frames. This correct one-frame boundary
is accepted by the explicit 49-second ±2-frame tolerance.

The first current combined unit run is preserved in
`build/combat-graphics-work/final-checks-first.log`: 520 tests, 517 passed, one
existing skipped, two failures in the concurrent arena update (authored layout
assertion and obsolete construction preview). They are not reported as passed.

The first current physics run is preserved in
`build/combat-graphics-work/final-native-assets-first.log`: 231 tests, 229 passed,
one existing skipped and one failure at the construction factory's coincident
glass/metal faces. The same run completed installDist, while verifyAssets rejected
the pending architecture export and obsolete construction preview. Those checks
must be repeated after the final arena publication.

Fourteen environment specular maps now use L8, preserving all 58,720,256 scalar
samples and all diffuse/normal resources. Their decoded base payload changed from
168 to 56 MiB. Exact source/formula tests, repeat SHA checks and actual jME loading
passed. Native Phong/MSAA4 A/B at 1280x720 produced zero differing framebuffer
channels; GL queried R8 with the required RGB swizzle for all fourteen maps.
Evidence: `build/combat-graphics-work/environment-spec/`.

The post-compression diagnostic image is frozen as
`build/combat-graphics-work/tenth-image/`, JAR SHA-256
`56f9fd5c3943b44f6b640b635d83e09af5827951d98c322eca2d67449f6775a7`, source SHA-256
`3f7e079ac76fe4e56e83d6b991ead82383ec0d32d2ab431f49eafb62d9770a8a`.
It is an installDist image for a short memory diagnostic, not the final release ZIP.

The tenth image's 30-second warmup plus 60-second profiled run is preserved in
`build/local-world-runs/benchmark-dead-air-yard-c7eb8ab3569941b38471385b48af39b1/`.
It completed 35,023 measured frames: p95 2.95 ms, p99 3.60 ms, maximum 12.9559 ms,
zero frames above 100 ms. Lifetime PeakWorkingSet, including the successful final
post-exit handle query, was 1,651,335,168 bytes: **FAIL**, 40,722,432 bytes above
the 1.5 GiB limit. This run enabled JFR and detailed CPU/GPU instrumentation;
it does not replace the required unprofiled package measurement.

Its external sampler also recorded a 7.55-second startup gap caused by the first
synchronous WDDM provider query. This is a collector failure, not a measured
7.55-second game frame. The optional blocking query was removed from the sampler;
new runs explicitly report VRAM as UNAVAILABLE while engine GPU timestamps remain
available with profiling. Thirteen PowerShell 5.1 regression assertions passed,
including continued rejection of the original sampling gap. The old failed report
is preserved and is not upgraded to PASS.

The latest targeted root run, `build/combat-graphics-work/final-targeted-checks.log`,
passed the authored-layout and preview unit cases and verifyAssets (555 files,
60 dependencies, four Windows native entries). It exposed a remaining internal
metal/concrete overlap at the coaster footing. The arena owner corrected the
source geometry; `build/revision3-footing-native.log` then passed all three strict
architecture contact cases. The shared full unit snapshot in
`build/revision3-verification/closing-full-run/test/` contains 852 passing cases.
The associated native snapshot remains failed because a separate saturation test
retained an unclosed asset cache and exhausted direct-buffer memory. Its closing
repeat and the final package evidence are recorded separately.

## First combined Windows package

The closing unit run passed **853 cases**, zero failures/errors/skips. Its immutable
XML snapshot is `build/combat-graphics-work/final-unit-20260913T1503/test/`.
verifyAssets passed 555 files. Packaging initially failed because the child
PowerShell could not autoload Get-FileHash. The shared file hashing helper now
uses its existing streaming SHA-256 implementation, with guaranteed file disposal;
the duplicate packaging hash helper was removed. All 196 release-tool assertions
passed on PowerShell 5.1, including unavailable-command, literal-path and file-handle
regressions. No game gate was relaxed.

The resulting immutable ZIP is
`build/combat-graphics-delivery/WreckRiff-0.4.0-windows-x64.zip` (845,132,367 bytes),
SHA-256 `07f965b7d44c0d6c63dee9108258b59cbe0d91ac287479187299799dfee7af4a`.
JAR SHA-256 `cf0625a9bbb0df396c8615540e32fd6be1f0441f59c1ccca9e7127b9e032f1d4`;
source SHA-256 `f299bc83ff833a58304c507b335aea08ca28afd8dfb930c5a5bdfa31876a9ce1`.
It includes Microsoft Java 21.0.11. Structure/integrity checks passed.

Its actual extracted EXE ran the classic arena with sound, 1920x1080/MSAA4,
VSync off, no video and **no profiling**: 30 seconds warmup and 60.0010685 measured
seconds, 42,731 frames, p95 2.55 ms, p99 3.15 ms, maximum 14.8009 ms, zero measured
frames above 100 ms and zero lost simulation time. The process exited normally.
Evidence: `build/benchmark-runs/e9399becedcb4a9e8a2b5281e34a7a12/`.
The script's DIAGNOSTIC_COMPLETE status means the short run completed, not release
acceptance. The separately applied memory gate **failed**: final OS lifetime peak
1,659,105,280 bytes, 48,492,544 bytes above the limit. All sample gaps were bounded.

The concurrent full native repeat also remains failed: 403 cases reached, eight
navigation failures and one executor direct-memory failure, with two aborted cases.
Snapshot: `build/revision3-verification/closing-physics-1907/`. These are retained
failures, not a passing full physics suite. The current package remains available
as the exact measured intermediate; subsequent fixes require a new ZIP identity.

The same ZIP passed its full 49-second arsenal/Freeze-Ballistic showcase:
`build/showcase-runs/54227153f4a04428a2d8a416842aa328/showcase-verification.json`.
All 29 named captures are hashed. Full input and output decoding confirmed
1,470 frames at 1600x900/30 FPS (48.99951 seconds); stereo reconstructed audio is
48.981333 seconds. Export: `build/combat-graphics-work/final-package-showcase/`.
The MP4 SHA-256 is `aff01bbe9afede9e8518d55f08bfac6fc58204b14220518c0f3e64f9de21c80a`.
Root sampled six key captures; the observations and limits are in
`visual-review.json`. Fire remains stylized; no owner quality approval is inferred.
The comparable 19-pair viewer is
`build/combat-graphics-work/review-package-before-after/index.html`.

The first export attempt exposed Windows PowerShell 5.1's interpretation of
UTF-8 scripts without a BOM. Russian captions and the HTML template now live in
UTF-8 resource files, read with an explicit encoding; the script is ASCII.
The complete real export and all 15 media fixtures passed. The original failed
export log remains preserved. This only changes local evidence tooling.

## Final lossless loading changes

Ordnance specular maps now preserve all 8,388,608 scalar samples in L8 instead
of three identical RGB channels. Their base image payload fell by 16 MiB; all
four diffuse/normal files and all eight ordnance models remain byte-identical.
Evidence: `build/combat-graphics-work/ordnance-spec/proof.json`.

`RawPngLoader` selects the JDK decoder's native RGB/RGBA destination and writes
the same final jME BGR/ABGR bytes. It avoids the decoder's temporary per-row
channel shuffle; it does not replace ImageIO or change colour interpretation.
All 78 packaged PNGs matched the pinned AWT loader with both flip settings.
Fifteen focused checks passed, including scalar/indexed/16-bit conversion,
malformed input, reader/stream cleanup and the allocation regression. The
156-decode prototype allocated 1,184,277,144 fewer bytes cumulatively, while
CPU time increased from 5.69 to 6.52 seconds. These are allocation/CPU measures,
not process peak savings. Evidence: `build/combat-graphics-work/png-loader/`.
Immutable vehicle parts also avoid allocating unused deformation arrays;
thirty focused checks passed after that change.

The combined pre-arena-freeze unit run reached 860 cases: 858 passed, two failed
because the concurrent arena export and Euphoria preview no longer matched
their source revision. verifyAssets rejected the same two provenance changes.
The full failed snapshot is retained at
`build/combat-graphics-work/final-unit-pre-arena-freeze/`; it is not a full PASS.

The resulting diagnostic installDist is preserved at
`build/combat-graphics-work/lossless-memory-image/`, source SHA-256
`3642e12f9cf862e6c4ad13de2d42af9b0b616c7bbb6cfd2fa988243eadef2927`.
Two real-window 30+60-second unprofiled runs used this identical image:

| Run | Lifetime PeakWorkingSet | Memory gate |
|---|---:|---|
| `benchmark-dead-air-yard-bc818206af22490587e5682b45a51c63` | 1,649,152,000 bytes | FAIL |
| `benchmark-dead-air-yard-39e6ca2aff084051b8b0eadec6175484` | 1,599,766,528 bytes | PASS |

Both directories are under `build/local-world-runs/`. The first peak overlapped
the parallel arena task's OpenGL preview windows. The second ran without those
windows; parallel headless work was not isolated. This observation does not
prove a causal explanation for the difference and is not final package FPS
evidence. Both final post-exit OS peak queries succeeded. The passing run has
only 10,846,208 bytes of margin under 1.5 GiB, so it does not establish memory
headroom on larger maps or over a long session. Final ZIP checks follow below.

## Closing environment memory change

Construction's preceding 30+60-second run retained a lifetime PeakWorkingSet of
1,915,895,808 bytes, so it failed the 1.5 GiB requirement. The final resource
change uses 13 BC7 sRGB environment maps and preserves leafy_grass as its sole
lossless PNG. Source resolution, normal/specular maps and material parameters
remain unchanged. Leafy's GPU and CPU candidates failed the declared fine-detail
bounds; no threshold was relaxed to accept them. The CPU result was RGB RMSE
3.04017, p99=10, maximum32; the runtime source remains intact.

All 13 selected maps passed two identical encodes, RGB RMSE<=3, p99<=8,
maximum<=48 and alpha=255, with pinned texconv and GPU/driver. Explicit alpha
weight100 corrects the default encoder's opacity error. Preparation is separate
from Gradle builds and the game. Native A/B evidence is under
`build/combat-graphics-work/environment-diffuse-native/`: two real MSAA4 scenes,
13 actual BC7 sRGB uploads plus one unchanged PNG, day/neon frame RMSE
0.53746/0.45628, maximum17/255; leafy's image region is byte-identical. The root
reviewed both images and the daylight baseline: no apparent orientation or colour
shift. This is technical image review, not owner acceptance or a memory benchmark.

The user requested closure without further art or codec iterations. Final build,
package and short-run outcomes are recorded separately in build artifacts. The
original 600-second-per-map and 1800-second soak matrix has not yet been run on
the final image; earlier short results do not substitute for it. RTX3060-class
performance remains unconfirmed on this RTX5080 workstation.

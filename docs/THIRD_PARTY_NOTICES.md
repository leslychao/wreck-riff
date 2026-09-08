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

The game uses the original `Wreck Grid` ASCII font generated from recipes in
`GenerateFont.java`, not system fonts or the engine's default atlas. Engine JARs
are not altered to remove unused bundled resources. The original procedural
models, glyph recipes and audio score remain in the project's sources; their
artistic status is `NEEDS_CREATIVE_REVIEW` until the owner's actual evaluation.

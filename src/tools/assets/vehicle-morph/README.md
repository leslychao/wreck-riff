# Vehicle GPU morph shader preparation

The eight upstream sources are retained byte-for-byte from the locked jMonkeyEngine
3.8.1-stable core/effects dependencies. The runtime manifest records each exact tag
URL, original and transformed SHA-256, generator hash and BSD-3-Clause license hash.
The license is `src/main/resources/licenses/jme-BSD3.txt`.

`PrepareVehicleMorph` is an explicit offline tools entry point. It extracts the
locked dependency resources and writes the prepared runtime shaders. Ordinary
Gradle compilation and the game use only the prepared local files.

```powershell
$env:JAVA_HOME='C:/Users/vitalii/.jdks/ms-21.0.11'
./gradlew.bat --offline prepareVehicleMorph
./gradlew.bat --offline vehicleDamageReview
```

The upstream tangent morph function redeclares `normWeight` for every target in
the same scope. GLSL compilation then fails for two targets, and `MorphControl`
silently reduces GPU capacity. The prepared function declares the local once;
its normal-only overload also forwards the real normal for three-buffer targets.
Normal prepass and shadow-facing normals follow the deformed surface. Vehicle
lighting, shadow, glow, lamps, shields and frost use unique `materials/Vehicle*`
paths. No dependency resource is overridden and no CPU fallback is introduced.

`vehicleDamageReview` must create a real 1600x900 MSAA4 window, produce all 55
captures within 60 seconds, and assert exactly two simultaneous **GPU** targets
on every active deformable body, lamp and status geometry. A prepared mesh having
two targets alone is insufficient. The completion JSON records those live counts,
material isolation, local-mask repair, active LOD and short-scene CPU/GPU timings.
The review is supplementary evidence; it does not replace the match benchmark.

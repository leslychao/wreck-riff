param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Maintenance command only. Normal builds use the checked-in evidence and never fetch licenses.
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$licenseRoot = Join-Path $projectRoot 'src/main/resources/licenses'
[IO.Directory]::CreateDirectory($licenseRoot) | Out-Null
$sources = @(
    @{ file='jme-BSD3.txt'; url='https://raw.githubusercontent.com/jMonkeyEngine/jmonkeyengine/v3.8.1-stable/LICENSE.md'; components=@('org.jmonkeyengine:jme3-core:3.8.1-stable','org.jmonkeyengine:jme3-desktop:3.8.1-stable','org.jmonkeyengine:jme3-lwjgl3:3.8.1-stable','org.jmonkeyengine:jme3-terrain:3.8.1-stable','org.jmonkeyengine:jme3-effects:3.8.1-stable') },
    @{ file='minie-BSD3.txt'; url='https://raw.githubusercontent.com/stephengold/Minie/9.0.3/LICENSE'; components=@('com.github.stephengold:Minie:9.0.3') },
    @{ file='heart-BSD3.txt'; url='https://raw.githubusercontent.com/stephengold/Heart/9.2.0/license.txt'; components=@('com.github.stephengold:Heart:9.2.0') },
    @{ file='heart-Icosphere-source.txt'; url='https://raw.githubusercontent.com/stephengold/Heart/9.2.0/HeartLibrary/src/main/java/jme3utilities/mesh/Icosphere.java'; components=@('com.github.stephengold:Heart:9.2.0') },
    @{ file='libbulletjme-BSD3-zlib-MIT.txt'; url='https://raw.githubusercontent.com/stephengold/Libbulletjme/22.0.3/LICENSE'; components=@('com.github.stephengold:Libbulletjme-Windows64:22.0.3','com.github.stephengold:Libbulletjme-Linux64:22.0.3','com.github.stephengold:Libbulletjme-Linux_ARM32hf:22.0.3','com.github.stephengold:Libbulletjme-Linux_ARM64:22.0.3','com.github.stephengold:Libbulletjme-MacOSX64:22.0.3','com.github.stephengold:Libbulletjme-MacOSX_ARM64:22.0.3') },
    @{ file='gson-Apache2.txt'; url='https://raw.githubusercontent.com/google/gson/gson-parent-2.13.1/LICENSE'; components=@('com.google.code.gson:gson:2.13.1') },
    @{ file='error-prone-Apache2.txt'; url='https://raw.githubusercontent.com/google/error-prone/v2.38.0/COPYING'; components=@('com.google.errorprone:error_prone_annotations:2.38.0') },
    @{ file='slf4j-MIT.txt'; url='https://raw.githubusercontent.com/qos-ch/slf4j/v_1.7.32/LICENSE.txt'; components=@('org.slf4j:slf4j-api:1.7.32') },
    @{ file='lwjgl-BSD3.txt'; url='https://raw.githubusercontent.com/LWJGL/lwjgl3/3.3.6/LICENSE.md'; components=@('org.lwjgl:lwjgl:3.3.6','org.lwjgl:lwjgl-glfw:3.3.6','org.lwjgl:lwjgl-jawt:3.3.6','org.lwjgl:lwjgl-jemalloc:3.3.6','org.lwjgl:lwjgl-openal:3.3.6','org.lwjgl:lwjgl-opencl:3.3.6','org.lwjgl:lwjgl-opengl:3.3.6') },
    @{ file='lwjgl-libffi-MIT.txt'; url='https://raw.githubusercontent.com/LWJGL/lwjgl3/3.3.6/modules/lwjgl/core/libffi_license.txt'; components=@('org.lwjgl:lwjgl:3.3.6') },
    @{ file='lwjgl-liburing.txt'; url='https://raw.githubusercontent.com/LWJGL/lwjgl3/3.3.6/modules/lwjgl/core/liburing_license.txt'; components=@('org.lwjgl:lwjgl:3.3.6') },
    @{ file='lwjgl-glfw-zlib.txt'; url='https://raw.githubusercontent.com/LWJGL/lwjgl3/3.3.6/modules/lwjgl/glfw/glfw_license.txt'; components=@('org.lwjgl:lwjgl-glfw:3.3.6') },
    @{ file='lwjgl-jemalloc.txt'; url='https://raw.githubusercontent.com/LWJGL/lwjgl3/3.3.6/modules/lwjgl/jemalloc/jemalloc_license.txt'; components=@('org.lwjgl:lwjgl-jemalloc:3.3.6') },
    @{ file='lwjgl-openal-soft-LGPL2.txt'; url='https://raw.githubusercontent.com/LWJGL/lwjgl3/3.3.6/modules/lwjgl/openal/openal_soft_license.txt'; components=@('org.lwjgl:lwjgl-openal:3.3.6') },
    @{ file='lwjgl-opencl-Khronos.txt'; url='https://raw.githubusercontent.com/LWJGL/lwjgl3/3.3.6/modules/lwjgl/opencl/khronos_license.txt'; components=@('org.lwjgl:lwjgl-opencl:3.3.6') },
    @{ file='lwjgl-opengl-Khronos.txt'; url='https://raw.githubusercontent.com/LWJGL/lwjgl3/3.3.6/modules/lwjgl/opengl/khronos_license.txt'; components=@('org.lwjgl:lwjgl-opengl:3.3.6') }
)
$records = @()
foreach ($source in $sources) {
    $target = Join-Path $licenseRoot $source.file
    Invoke-WebRequest -Uri $source.url -OutFile $target -TimeoutSec 30 -UseBasicParsing
    if ((Get-Item -LiteralPath $target).Length -lt 100) { throw "License evidence is empty: $($source.file)" }
    $records += [ordered]@{ file=$source.file; source=$source.url; components=$source.components; sha256=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$index = [ordered]@{ schemaVersion=1; description='Pinned upstream license evidence; presence is not a legal approval of a distribution'; sources=$records }
[IO.File]::WriteAllText((Join-Path $licenseRoot 'license-index.json'), ($index | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
Write-Output "Captured $($records.Count) pinned upstream license files."

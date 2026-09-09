param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+$')][string]$Version,
    [Parameter(Mandatory = $true)][string]$JdkHome
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw 'packageWindows requires Windows; it cannot cross-package a Windows executable.' }
if (-not [Environment]::Is64BitOperatingSystem) { throw 'The MVP package targets Windows x64.' }
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$buildRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'build'))
$jdkRoot = (Resolve-Path -LiteralPath $JdkHome).ProviderPath
$jpackage = Join-Path $jdkRoot 'bin/jpackage.exe'
if (-not (Test-Path -LiteralPath $jpackage -PathType Leaf)) { throw 'The selected JDK does not contain jpackage.exe.' }
$jdkRelease = Get-Content -LiteralPath (Join-Path $jdkRoot 'release') -Raw
if ($jdkRelease -notmatch 'JAVA_VERSION="21\.' -or $jdkRelease -notmatch 'IMPLEMENTOR="Microsoft"') { throw 'Packaging requires the configured Microsoft JDK 21.' }

function Assert-BuildChild([string]$Candidate) {
    $absolute = [IO.Path]::GetFullPath($Candidate)
    $prefix = $buildRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $absolute.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Generated path escapes build directory: $absolute" }
    # Refuse junction/symlink ancestors before any recursive deletion or directory move.
    $cursor = $absolute
    while ($cursor.Length -ge $buildRoot.Length) {
        if (Test-Path -LiteralPath $cursor) {
            if (((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Generated path contains a reparse point: $cursor" }
        }
        $cursor = [IO.Path]::GetDirectoryName($cursor)
        if ([string]::IsNullOrEmpty($cursor)) { break }
    }
    return $absolute
}

function Get-Sha256([string]$LiteralPath) {
    $stream=[IO.File]::OpenRead($LiteralPath)
    $algorithm=[Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-','').ToLowerInvariant() }
    finally { $stream.Dispose();$algorithm.Dispose() }
}

$inputLib = Assert-BuildChild (Join-Path $buildRoot 'install/wreck-riff/lib')
$assetReportRoot = Assert-BuildChild (Join-Path $buildRoot 'reports/assets')
$assetReport = Get-Content -LiteralPath (Join-Path $assetReportRoot 'verification.json') -Raw | ConvertFrom-Json
if ($assetReport.status -ne 'TECHNICAL_PASS') { throw 'verifyAssets must pass before packaging.' }
$mainJarName = "wreck-riff-$Version.jar"
if (-not (Test-Path -LiteralPath (Join-Path $inputLib $mainJarName))) { throw "Run installDist first: missing $mainJarName" }

$staging = Assert-BuildChild (Join-Path $buildRoot 'package-windows-staging')
if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
$inputStage = Join-Path $staging 'input'
$imageStage = Join-Path $staging 'images'
[IO.Directory]::CreateDirectory($inputStage) | Out-Null
[IO.Directory]::CreateDirectory($imageStage) | Out-Null
$selectedJars = @()
foreach ($jar in (Get-ChildItem -LiteralPath $inputLib -Filter '*.jar' -File | Sort-Object Name)) {
    if ($jar.Name -match '-natives-' -and $jar.Name -notmatch '-natives-windows\.jar$') { continue }
    if ($jar.Name -match '^Libbulletjme-' -and $jar.Name -notmatch '^Libbulletjme-Windows64-') { continue }
    if ($jar.Name -match '(?i)(junit|opentest4j|apiguardian|testdata|jme3-bullet|jme3-jbullet)') { throw "Unexpected dependency in release: $($jar.Name)" }
    Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $inputStage $jar.Name)
    $selectedJars += $jar.Name
}

$jpackageArgs = @(
    '--type', 'app-image', '--name', 'WreckRiff', '--app-version', $Version,
    '--vendor', 'Wreck Riff', '--description', 'Wreck Riff single-player vehicular combat MVP',
    '--input', $inputStage, '--dest', $imageStage, '--main-jar', $mainJarName,
    '--main-class', 'game.wreckriff.Main',
    '--java-options', '-Xms128m', '--java-options', '-Xmx768m',
    '--add-modules', 'java.base,java.desktop,java.logging,java.management,jdk.unsupported,jdk.crypto.ec',
    '--jlink-options', '--strip-debug --no-man-pages --no-header-files'
)
& $jpackage @jpackageArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE" }
$image = Assert-BuildChild (Join-Path $imageStage 'WreckRiff')
foreach ($relative in @('WreckRiff.exe', 'app/WreckRiff.cfg', 'runtime/bin/server/jvm.dll', 'runtime/bin/java.exe', 'runtime/release', 'runtime/legal/java.base/LICENSE')) {
    $expected = Join-Path $image $relative
    if (-not (Test-Path -LiteralPath $expected -PathType Leaf) -or (Get-Item -LiteralPath $expected).Length -eq 0) { throw "Incomplete app-image: $relative" }
}
$runtimeRelease = Get-Content -LiteralPath (Join-Path $image 'runtime/release') -Raw
if ($runtimeRelease -notmatch 'JAVA_VERSION="21\.') { throw 'The bundled runtime is not Java 21.' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$requiredNatives = @(
    'windows/x64/org/lwjgl/lwjgl.dll', 'windows/x64/org/lwjgl/glfw/glfw.dll',
    'windows/x64/org/lwjgl/openal/OpenAL.dll', 'native/windows/x86_64/bulletjme.dll'
)
$foundNatives = @()
$jarHashes = @()
foreach ($jar in (Get-ChildItem -LiteralPath (Join-Path $image 'app') -Filter '*.jar' -File)) {
    $jarHashes += [ordered]@{ file=$jar.Name; sha256=(Get-Sha256 $jar.FullName) }
    $archive = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
    try {
        foreach ($entry in $archive.Entries) {
            if ($requiredNatives -contains $entry.FullName) {
                $stream = $entry.Open()
                try { if ($entry.Length -lt 1024 -or $stream.ReadByte() -ne 77 -or $stream.ReadByte() -ne 90) { throw "Invalid PE native: $($entry.FullName)" } }
                finally { $stream.Dispose() }
                $foundNatives += $entry.FullName
            }
            if ($jar.Name -eq $mainJarName -and $entry.FullName -match '(?i)(^|/)(\.env|credentials|id_rsa|id_ed25519|config-dir)(/|\.|$)|\.(dll|key|pfx|pem)$') {
                throw "Unexpected private/native resource in application JAR: $($entry.FullName)"
            }
        }
    } finally { $archive.Dispose() }
}
foreach ($native in $requiredNatives) { if ($foundNatives -notcontains $native) { throw "Missing Windows x64 native: $native" } }

$licenses = Join-Path $image 'licenses'
$reports = Join-Path $image 'reports'
[IO.Directory]::CreateDirectory($licenses) | Out-Null
[IO.Directory]::CreateDirectory($reports) | Out-Null
Copy-Item -Path (Join-Path $assetReportRoot 'third-party/*') -Destination $licenses -Recurse
Copy-Item -LiteralPath (Join-Path $assetReportRoot 'manifest.json') -Destination $reports
Copy-Item -LiteralPath (Join-Path $assetReportRoot 'asset-register.csv') -Destination $reports
Copy-Item -LiteralPath (Join-Path $assetReportRoot 'verification.json') -Destination $reports
Copy-Item -LiteralPath (Join-Path $projectRoot 'docs/THIRD_PARTY_NOTICES.md') -Destination $licenses
$notice = @"
Wreck Riff $Version - Windows x64 MVP

Extract the entire ZIP to one directory and run WreckRiff.exe.
Java is included. Do not run the executable from inside the ZIP viewer.
Your settings, statistics, logs, and captures are saved under LOCALAPPDATA/WreckRiff.

Default controls: WASD drive, Space handbrake, Shift turbo, LMB machine gun,
RMB selected weapon, Q/E switch weapon, F Feedback Pulse, R recovery, Esc pause.
Hold Left Ctrl, then press W for Freeze, A for Stun, or D for Shield.
Gamepad: hold R3, then D-pad up/left/right for Freeze/Stun/Shield.
The Controls screen is authoritative for current/rebound controls.

This local MVP is not digitally signed. Windows may display a warning for an
unrecognized application; no claim is made about warnings on every Windows setup.

Technical package checks are in reports/package-verification.json.
Hardware/controller/audio/feel acceptance is separate and must be recorded by the owner.
See licenses/THIRD_PARTY_NOTICES.md and runtime/legal for dependency/runtime notices.
The OpenAL Soft LGPL source-delivery/replacement review remains explicit before external distribution.
"@
[IO.File]::WriteAllText((Join-Path $image 'README.txt'), $notice, [Text.UTF8Encoding]::new($false))
$packageReport = [ordered]@{
    schemaVersion=1; version=$Version; platform='Windows x64'; status='PACKAGE_STRUCTURE_VERIFIED';
    bundledJava=([regex]::Match($runtimeRelease, 'JAVA_VERSION="([^"]+)"').Groups[1].Value);
    launcher='WreckRiff.exe'; nativeEntries=$foundNatives; jars=$jarHashes;
    graphicalLaunch='NOT_RUN_BY_PACKAGING'; controller='NOT_VERIFIED_BY_PACKAGING';
    distributionStatus=$assetReport.distributionStatus; digitalSignature='UNSIGNED_MVP'
}
[IO.File]::WriteAllText((Join-Path $reports 'package-verification.json'), ($packageReport | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))

$distributionRoot = Assert-BuildChild (Join-Path $buildRoot 'distributions')
[IO.Directory]::CreateDirectory($distributionRoot) | Out-Null
$zipName = "WreckRiff-$Version-windows-x64.zip"
$stagedZip = Join-Path $staging $zipName
# includeBaseDirectory makes extraction produce one WreckRiff folder.
[IO.Compression.ZipFile]::CreateFromDirectory($image, $stagedZip, [IO.Compression.CompressionLevel]::Optimal, $true)
$zipTarget = Assert-BuildChild (Join-Path $distributionRoot $zipName)
Move-Item -LiteralPath $stagedZip -Destination $zipTarget -Force
$checksum = Get-Sha256 $zipTarget
[IO.File]::WriteAllText(($zipTarget + '.sha256'), "$checksum  $zipName`n", [Text.UTF8Encoding]::new($false))
$imageTarget = Assert-BuildChild (Join-Path $distributionRoot 'WreckRiff')
if (Test-Path -LiteralPath $imageTarget) { Remove-Item -LiteralPath $imageTarget -Recurse -Force }
Move-Item -LiteralPath $image -Destination $imageTarget
Write-Output "Windows app-image: $imageTarget"
Write-Output "ZIP: $zipTarget"
Write-Output "SHA-256: $checksum"

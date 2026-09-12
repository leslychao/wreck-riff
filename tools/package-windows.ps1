param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+$')][string]$Version,
    [Parameter(Mandatory = $true)][string]$JdkHome,
    [string]$BuildRoot
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'release-evidence.ps1')

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw 'packageWindows requires Windows; it cannot cross-package a Windows executable.' }
if (-not [Environment]::Is64BitOperatingSystem) { throw 'The MVP package targets Windows x64.' }
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$workspaceBuild = [IO.Path]::GetFullPath((Join-Path $projectRoot 'build'))
$buildRoot = if($BuildRoot){[IO.Path]::GetFullPath($BuildRoot)}else{$workspaceBuild}
if(!$buildRoot.Equals($workspaceBuild,[StringComparison]::OrdinalIgnoreCase) -and !$buildRoot.StartsWith($workspaceBuild+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {
    throw 'Package build root must be the workspace build directory or one of its descendants.'
}
$buildAncestor=$buildRoot
while($buildAncestor.Length -ge $workspaceBuild.Length) {
    if((Test-Path -LiteralPath $buildAncestor) -and ((Get-Item -LiteralPath $buildAncestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Package build root contains a reparse point.'}
    $buildAncestor=Split-Path -Parent $buildAncestor
}
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
$sourceReportPath=Join-Path $assetReportRoot 'source-distribution.json'
$sourceReport=Read-ReleaseJson $sourceReportPath
if($sourceReport.status -ne 'SOURCE_AND_NOTICE_MATERIALS_VERIFIED' -or $sourceReport.distributionApproval -ne 'NOT_GRANTED' -or
    $sourceReport.selectedJdkReleaseSha256 -ne (Get-ReleaseSha256 (Join-Path $jdkRoot 'release')) -or
    $sourceReport.inputIndexSha256 -ne (Get-ReleaseSha256 (Join-Path $projectRoot 'src/tools/licenses/source-index.json'))) {
    throw 'Source/notice materials do not match the selected JDK and current repository index.'
}
$sourceMaterialsRoot=Assert-BuildChild (Join-Path $assetReportRoot 'source-distribution-materials')
$mainJarName = "wreck-riff-$Version.jar"
if (-not (Test-Path -LiteralPath (Join-Path $inputLib $mainJarName))) { throw "Run installDist first: missing $mainJarName" }
$mainStream=[IO.File]::OpenRead((Join-Path $inputLib $mainJarName))
try {$buildIdentity=Get-ReleaseJarInfo $mainStream} finally {$mainStream.Dispose()}
if($buildIdentity.version -ne $Version -or $buildIdentity.sourceSha256 -ne (Get-ReleaseInputHash $projectRoot -RuntimeOnly)) {
    throw 'installDist application JAR does not match the current runtime sources/version.'
}
$verificationInputs=Get-ReleaseInputHash $projectRoot

$existingLauncher=Join-Path $buildRoot 'distributions/WreckRiff/WreckRiff.exe'
if(@(Get-Process -Name WreckRiff -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $existingLauncher }).Count -gt 0) {
    throw 'Close the running build/distributions/WreckRiff game before packaging; the existing app-image has been preserved.'
}

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
    '--vendor', 'Wreck Riff', '--description', 'Wreck Riff single-player vehicular combat',
    '--input', $inputStage, '--dest', $imageStage, '--main-jar', $mainJarName,
    '--main-class', 'game.wreckriff.Main',
    '--java-options', '-Xms128m', '--java-options', '-Xmx768m',
    '--add-modules', 'java.base,java.desktop,java.logging,java.management,jdk.unsupported,jdk.crypto.ec,jdk.jfr',
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
foreach($material in Get-ChildItem -LiteralPath $sourceMaterialsRoot -Force) {
    Copy-Item -LiteralPath $material.FullName -Destination $licenses -Recurse
}
Copy-Item -LiteralPath (Join-Path $projectRoot 'src/main/resources/licenses/assets') -Destination $licenses -Recurse
Copy-Item -LiteralPath $sourceReportPath -Destination $reports
$assetNoticeEvidence=@(Get-ChildItem -LiteralPath (Join-Path $licenses 'assets') -Recurse -File | ForEach-Object {
    [ordered]@{path=('licenses/assets/'+$_.FullName.Substring((Join-Path $licenses 'assets').Length+1).Replace('\','/'));bytes=$_.Length;sha256=(Get-ReleaseSha256 $_.FullName)}
})
Copy-Item -LiteralPath (Join-Path $assetReportRoot 'manifest.json') -Destination $reports
Copy-Item -LiteralPath (Join-Path $assetReportRoot 'asset-register.csv') -Destination $reports
Copy-Item -LiteralPath (Join-Path $assetReportRoot 'verification.json') -Destination $reports
Copy-Item -LiteralPath (Join-Path $projectRoot 'docs/THIRD_PARTY_NOTICES.md') -Destination $licenses
$notice = @"
Wreck Riff $Version - Windows x64 release candidate

Extract the entire ZIP to one directory and run WreckRiff.exe.
Java is included. Do not run the executable from inside the ZIP viewer.
Your settings, statistics, logs, and captures are saved under LOCALAPPDATA/WreckRiff.

Default controls: WASD drive, Space handbrake, Shift turbo, LMB machine gun,
RMB selected weapon, 1/2/3/4/5/6 select Homing/Power/Mine/Napalm/Ballistic/Cannon, Q/E cycle,
F Shield, Z Freeze, C vehicle special, hold R recovery, V rear view, Esc pause.
Gamepad: A Shield, D-pad up Freeze, D-pad down vehicle special, D-pad left/right cycle weapons.
The Controls screen is authoritative for current/rebound controls.

This local package is not digitally signed. Windows may display a warning for an
unrecognized application; no claim is made about warnings on every Windows setup.

Technical package checks are in reports/package-verification.json.
Physical controller, another Windows installation and owner feel are separate checks.
See licenses/THIRD_PARTY_NOTICES.md and runtime/legal for dependency/runtime notices.
Corresponding OpenAL/JDK sources, notices and replacement instructions are supplied
under licenses. Their integrity report is reports/source-distribution.json.
Technical verification does not grant artistic or distribution approval.
"@
[IO.File]::WriteAllText((Join-Path $image 'README.txt'), $notice, [Text.UTF8Encoding]::new($false))
$packageReport = [ordered]@{
    schemaVersion=2; version=$Version; platform='Windows x64'; status='PACKAGE_STRUCTURE_VERIFIED';
    packagedAtUtc=[DateTime]::UtcNow.ToString('o'); sourceSha256=$buildIdentity.sourceSha256;
    mainJarSha256=$buildIdentity.mainJarSha256; verificationInputsSha256=$verificationInputs;
    sourceDistribution=[ordered]@{path='reports/source-distribution.json';sha256=(Get-ReleaseSha256 (Join-Path $reports 'source-distribution.json'));
        inputIndexSha256=$sourceReport.inputIndexSha256;assetNotices=$assetNoticeEvidence};
    bundledJava=([regex]::Match($runtimeRelease, 'JAVA_VERSION="([^"]+)"').Groups[1].Value);
    launcher='WreckRiff.exe'; nativeEntries=$foundNatives; jars=$jarHashes;
    graphicalLaunch='NOT_RUN_BY_PACKAGING'; controller='NOT_VERIFIED_BY_PACKAGING';
    distributionStatus=$assetReport.distributionStatus; digitalSignature='UNSIGNED'; releaseStatus='RELEASE_CANDIDATE'
}
[IO.File]::WriteAllText((Join-Path $reports 'package-verification.json'), ($packageReport | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))

$distributionRoot = Assert-BuildChild (Join-Path $buildRoot 'distributions')
[IO.Directory]::CreateDirectory($distributionRoot) | Out-Null
$zipName = "WreckRiff-$Version-windows-x64.zip"
$stagedZip = Join-Path $staging $zipName
if($verificationInputs -ne (Get-ReleaseInputHash $projectRoot)) {throw 'Sources or verification tools changed during packaging; retry from a stable snapshot.'}
# Explicit entry names keep the ZIP format portable under Windows PowerShell 5.1.
New-ReleaseZip $image $stagedZip
$null=Assert-ReleaseSourceDistribution $stagedZip
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

<#
.SYNOPSIS
Exports the existing release-gate evidence for one immutable final ZIP.
.DESCRIPTION
Requires successful technical checks. Pending human acceptance stays pending.
Copies original reports unchanged and maps their original paths in evidence-index.json.
Historical artifacts must be selected explicitly and are labelled history, never current.
Does not run a game, manufacture an approval, publish, or include the game runtime.
#>
param(
    [Parameter(Mandatory=$true)][string]$ZipPath,
    [string]$GatePath,
    [string]$OutputDirectory,
    [string[]]$HistoryDirectories=@()
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'release-evidence.ps1')
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$identity=Get-ReleasePackageIdentity $ZipPath
if(!$GatePath){$GatePath=Join-Path $projectRoot ('build/reports/release-gate-'+$identity.zipSha256+'.json')}
$gate=Read-ReleaseJson $GatePath
Assert-ReleaseIdentity $gate $identity
if($gate.technicalStatus -ne 'PASS' -or $gate.technicalFailures.Count -ne 0 -or $gate.status -notin @('RELEASE_CANDIDATE','RELEASE_READY')){
    throw 'Export requires an existing successful technical gate for this exact ZIP.'
}
if(($gate.status -eq 'RELEASE_READY') -ne ($gate.pendingAcceptance.Count -eq 0)){throw 'Gate status contradicts pending acceptance.'}
$name="WreckRiff-$($identity.version)-verification-$($identity.zipSha256.Substring(0,12))"
if(!$OutputDirectory){$OutputDirectory=Join-Path $projectRoot "build/distributions/$name"}
$destination=[IO.Path]::GetFullPath($OutputDirectory);$archive=$destination+'.zip'
$buildPrefix=[IO.Path]::GetFullPath((Join-Path $projectRoot 'build'))+[IO.Path]::DirectorySeparatorChar
if(!$destination.StartsWith($buildPrefix,[StringComparison]::OrdinalIgnoreCase)){throw 'Export output must be a new directory inside this workspace build directory.'}
if((Test-Path -LiteralPath $destination) -or (Test-Path -LiteralPath $archive)){throw 'Export already exists; previous evidence is preserved.'}
$cursor=Split-Path -Parent $destination
while($cursor.Length -ge $buildPrefix.Length-1) {
    if((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Export ancestor is a reparse point.'}
    $cursor=Split-Path -Parent $cursor
}
$historyFiles=@();$historyNumber=0
foreach($directory in $HistoryDirectories) {
    $historyRoot=(Resolve-Path -LiteralPath $directory).ProviderPath.TrimEnd('\','/')
    if(!(Test-Path -LiteralPath $historyRoot -PathType Container)){throw 'History must name an existing directory.'}
    if($destination.StartsWith($historyRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Export may not be nested inside its history source.'}
    $historyNumber++
    foreach($file in Get-ChildItem -LiteralPath $historyRoot -Recurse -File) {
        if($historyFiles.Count -ge 10000){throw 'Historical export exceeds 10000 files; select narrower directories.'}
        $relative=$file.FullName.Substring($historyRoot.Length+1).Replace('\','/')
        $historyFiles+=@{artifact=(Get-ReleaseArtifact $file.FullName);relative="history/$historyNumber-$([IO.Path]::GetFileName($historyRoot))/$relative"}
    }
}
$requiredReports=@('release-build-verification.json','windows-package-Smoke.json','windows-package-Soak.json',
    'windows-package-NormalNew.json','windows-package-NormalMigrated.json','windows-package-NormalContinue.json')+
    @('dead-air-yard','construction_17','neon_zero','euphoria_park','ash_necropolis','doomsday_arena' | ForEach-Object {"windows-benchmark-$_.json"})
$primaryNames=@($gate.artifacts | ForEach-Object {Split-Path -Leaf $_.path})
foreach($required in $requiredReports){if($primaryNames -notcontains $required){throw "Gate omitted current report $required"}}
$script:exportFiles=[Collections.Generic.List[object]]::new()
$script:exportPaths=[Collections.Generic.Dictionary[string,string]]::new([StringComparer]::OrdinalIgnoreCase)
function Add-ExportArtifact($Artifact,[string]$Category) {
    Assert-ReleaseArtifact $Artifact
    $source=[IO.Path]::GetFullPath($Artifact.path)
    if($script:exportPaths.ContainsKey($source)) {
        if($script:exportPaths[$source] -ne $Artifact.sha256){throw 'Evidence references conflicting versions of one file.'}
        return
    }
    if($script:exportFiles.Count -ge 10000){throw 'Current evidence exceeds the export file bound.'}
    $script:exportPaths[$source]=$Artifact.sha256
    $relative='current/evidence/{0:D4}-{1}' -f $script:exportFiles.Count,[IO.Path]::GetFileName($source)
    $script:exportFiles.Add([pscustomobject]@{originalPath=$source;path=$relative;sha256=$Artifact.sha256;category=$Category})
}
function Find-ExportReferences($Value,[int]$Depth=0) {
    if($null -eq $Value -or $Value -is [string] -or $Value -is [ValueType]){return}
    if($Depth -gt 30){throw 'Evidence references exceed the JSON depth bound.'}
    if($Value -is [System.Collections.IEnumerable] -and $Value -isnot [pscustomobject]) {
        foreach($item in $Value){Find-ExportReferences $item ($Depth+1)}
    } else {
        $properties=$Value.PSObject.Properties
        if($null -ne $properties['path'] -and $null -ne $properties['sha256']) {
            Add-ExportArtifact $Value 'referenced evidence'
        } else {foreach($property in $properties){Find-ExportReferences $property.Value ($Depth+1)}}
    }
}
Add-ExportArtifact (Get-ReleaseArtifact $GatePath) 'release gate'
foreach($artifact in $gate.artifacts) {
    Add-ExportArtifact $artifact 'gate input'
    # Build, launch and acceptance records own the referenced XML, logs, diagnostic,
    # memory, before/after profiles and human review artifacts in the current schema.
    if([IO.Path]::GetExtension($artifact.path) -eq '.json') {
        $record=Read-ReleaseJson $artifact.path
        Find-ExportReferences $record
    }
}
# No file is copied until every declared current artifact has passed its hash check.
[IO.Directory]::CreateDirectory($destination) | Out-Null
foreach($item in $script:exportFiles) {
    $target=Join-Path $destination $item.path
    [IO.Directory]::CreateDirectory((Split-Path -Parent $target)) | Out-Null
    Copy-Item -LiteralPath $item.originalPath -Destination $target
    if((Get-ReleaseSha256 $target) -ne $item.sha256){throw 'Evidence changed during export.'}
}
foreach($item in $historyFiles) {
    Assert-ReleaseArtifact $item.artifact
    $target=Join-Path $destination $item.relative
    [IO.Directory]::CreateDirectory((Split-Path -Parent $target)) | Out-Null
    Copy-Item -LiteralPath $item.artifact.path -Destination $target
    if((Get-ReleaseSha256 $target) -ne $item.artifact.sha256){throw 'Historical evidence changed during export.'}
}
# Package reports and shipped licenses are read from this ZIP, never the app-image
# or today's mutable asset-verification directory.
$package=[IO.Compression.ZipFile]::OpenRead($identity.zipPath)
try {
    foreach($entry in $package.Entries) {
        if($entry.Name.Length -eq 0 -or ($entry.FullName -notmatch '^WreckRiff/(reports/|licenses/|README\.txt$)')){continue}
        $target=Join-Path $destination ('current/package/'+$entry.FullName.Substring('WreckRiff/'.Length))
        [IO.Directory]::CreateDirectory((Split-Path -Parent $target)) | Out-Null
        $sourceStream=$entry.Open();$targetStream=[IO.File]::Create($target)
        try {$sourceStream.CopyTo($targetStream)}finally{$targetStream.Dispose();$sourceStream.Dispose()}
    }
} finally {$package.Dispose()}
$helpers=@('release-evidence.ps1','prepare-windows-release.ps1','package-windows.ps1','test-windows-release.ps1',
    'test-windows-package.ps1','test-windows-benchmark.ps1','export-verification.ps1')
[IO.Directory]::CreateDirectory((Join-Path $destination 'tools')) | Out-Null
foreach($helper in $helpers){Copy-Item -LiteralPath (Join-Path $PSScriptRoot $helper) -Destination (Join-Path $destination "tools/$helper")}
[IO.Directory]::CreateDirectory((Join-Path $destination 'tools/fixtures')) | Out-Null
foreach($fixture in @('release-acceptance.example.json','test-release-evidence.ps1')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot "fixtures/$fixture") -Destination (Join-Path $destination "tools/fixtures/$fixture")
}
$index=[ordered]@{schemaVersion=1;version=$identity.version;sourceSha256=$identity.sourceSha256;mainJarSha256=$identity.mainJarSha256;
    verificationInputsSha256=$identity.verificationInputsSha256;zipSha256=$identity.zipSha256;gameZipName=[IO.Path]::GetFileName($identity.zipPath);
    gateStatus=$gate.status;pendingAcceptance=$gate.pendingAcceptance;exportedAtUtc=[DateTime]::UtcNow.ToString('o');
    current=$script:exportFiles.ToArray();history=@($historyFiles | ForEach-Object {@{originalPath=$_.artifact.path;path=$_.relative;sha256=$_.artifact.sha256}});
    helperOrigin='Workspace tools at export time; their hashes are in manifest.json. Rerun with the matching source workspace and game ZIP.'}
Write-ReleaseJson (Join-Path $destination 'evidence-index.json') $index
[IO.File]::WriteAllText((Join-Path $destination ([IO.Path]::GetFileName($identity.zipPath)+'.sha256')),
    $identity.zipSha256+'  '+[IO.Path]::GetFileName($identity.zipPath)+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
$readme=@"
# Wreck Riff $($identity.version) verification

Game ZIP SHA-256: $($identity.zipSha256)
Source SHA-256: $($identity.sourceSha256)
Existing gate status: $($gate.status)
Pending acceptance: $($gate.pendingAcceptance.Count)

This export contains the existing current evidence and no game runtime.
evidence-index.json maps original report paths to the copied, unchanged files.
current/package contains reports and licenses from the exact game ZIP.
history contains only explicitly selected older evidence; it is not a current PASS.
manifest.json identifies every exported file by SHA-256.

Original reports retain their original paths and hashes for traceability; use the
index to resolve those paths when reading this archive on another machine.
The tools directory includes the current helpers and their shared dependency.
Executing them requires the matching source workspace and the complete game ZIP.
This export does not grant owner, controller or other-Windows acceptance, and does
not publish the game. A candidate stays a candidate while acceptance is pending.
"@
[IO.File]::WriteAllText((Join-Path $destination 'README.md'),$readme,[Text.UTF8Encoding]::new($false))
$manifest=@(Get-ChildItem -LiteralPath $destination -Recurse -File | Sort-Object FullName | ForEach-Object {
    [ordered]@{path=$_.FullName.Substring($destination.Length+1).Replace('\','/');bytes=$_.Length;sha256=(Get-ReleaseSha256 $_.FullName)}
})
Write-ReleaseJson (Join-Path $destination 'manifest.json') $manifest
if((Get-ReleaseSha256 $identity.zipPath) -ne $identity.zipSha256){throw 'Game ZIP changed during export.'}
$zipStream=[IO.File]::Open($archive,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
try {
    $outputZip=[IO.Compression.ZipArchive]::new($zipStream,[IO.Compression.ZipArchiveMode]::Create,$true)
    try {
        foreach($file in Get-ChildItem -LiteralPath $destination -Recurse -File) {
            $entry=$outputZip.CreateEntry([IO.Path]::GetFileName($destination)+'/'+$file.FullName.Substring($destination.Length+1).Replace('\','/'),[IO.Compression.CompressionLevel]::Optimal)
            $sourceStream=[IO.File]::OpenRead($file.FullName);$targetStream=$entry.Open()
            try {$sourceStream.CopyTo($targetStream)}finally{$targetStream.Dispose();$sourceStream.Dispose()}
        }
    } finally {$outputZip.Dispose()}
} finally {$zipStream.Dispose()}
$checksum=Get-ReleaseSha256 $archive
[IO.File]::WriteAllText($archive+'.sha256',$checksum+'  '+[IO.Path]::GetFileName($archive)+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
Write-Output "Verification export: $archive"
Write-Output "Existing gate status preserved: $($gate.status)"

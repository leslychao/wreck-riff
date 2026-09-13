# Synthetic ZIP contract checks. These bytes are not corresponding sources or a release.
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../release-evidence.ps1')
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$work=Join-Path $root ('build/source-package-fixtures/'+[Guid]::NewGuid().ToString('N'))
$image=Join-Path $work 'WreckRiff';$licenseRoot=Join-Path $image 'licenses'
[IO.Directory]::CreateDirectory($licenseRoot) | Out-Null
$script:count=0
function Check([bool]$Condition,[string]$Name){if(!$Condition){throw "Fixture failed: $Name"};$script:count++}
function Reject([scriptblock]$Action,[string]$Name){$rejected=$false;try{& $Action}catch{$rejected=$true};Check $rejected $Name}
function Material([string]$Path,[string]$Text){
    $file=Join-Path $licenseRoot $Path;[IO.Directory]::CreateDirectory((Split-Path -Parent $file)) | Out-Null
    [IO.File]::WriteAllText($file,$Text,[Text.UTF8Encoding]::new($false))
    return [pscustomobject]@{path=$Path;bytes=(Get-Item -LiteralPath $file).Length;sha256=(Get-ReleaseSha256 $file)}
}
$openalCommit='a'*40;$jdkCommit='b'*40
$modules=@('java.base','java.desktop','java.logging','java.management','jdk.unsupported','jdk.crypto.ec','jdk.jfr')
$runtime=[pscustomobject]@{sourceId='microsoft-openjdk';releaseFile='runtime/microsoft-jdk-release.txt';implementor='Microsoft';implementorVersion='fixture';runtimeVersion='21.0.11+10-LTS';javaVersion='21.0.11';source=$jdkCommit.Substring(0,12);jlinkModules=$modules}
$release="IMPLEMENTOR=`"Microsoft`"`nIMPLEMENTOR_VERSION=`"fixture`"`nJAVA_RUNTIME_VERSION=`"21.0.11+10-LTS`"`nJAVA_VERSION=`"21.0.11`"`nSOURCE=`".:git:$($runtime.source)`"`nMODULES=`"$($modules -join ' ')`"`n"
$documents=@('README.md','openal/COPYING','openal/BSD-3Clause','openal/LICENSE-pffft','openal/fmt-LICENSE','openal/REPLACEMENT.md','runtime/LICENSE','runtime/ADDITIONAL_LICENSE_INFO','runtime/ASSEMBLY_EXCEPTION','runtime/README.md' | ForEach-Object {Material $_ "Synthetic notice: $_"})
$documents+=Material $runtime.releaseFile $release
$runtimePath=Join-Path $image 'runtime/release';[IO.Directory]::CreateDirectory((Split-Path -Parent $runtimePath)) | Out-Null
$jlinkRelease="JAVA_VERSION=`"21.0.11`"`nMODULES=`"$($modules -join ' ')`"`n"
[IO.File]::WriteAllText($runtimePath,$jlinkRelease,[Text.UTF8Encoding]::new($false))
$jdkRoot=Join-Path $work 'selected-jdk';[IO.Directory]::CreateDirectory((Join-Path $jdkRoot 'jmods')) | Out-Null
[IO.File]::WriteAllText((Join-Path $jdkRoot 'release'),$release,[Text.UTF8Encoding]::new($false))
$jmodPath=Join-Path $jdkRoot 'jmods/java.base.jmod';$jmodZip=[IO.MemoryStream]::new()
$jmodArchive=[IO.Compression.ZipArchive]::new($jmodZip,[IO.Compression.ZipArchiveMode]::Create,$true)
try {
    foreach($mapping in @(@{entry='bin/java.exe';path='runtime/bin/java.exe'},@{entry='lib/server/jvm.dll';path='runtime/bin/server/jvm.dll'})) {
        $bytes=[Text.Encoding]::ASCII.GetBytes('MZ synthetic native '+$mapping.entry)
        $entry=$jmodArchive.CreateEntry($mapping.entry);$stream=$entry.Open();try{$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
        $nativeFile=Join-Path $image $mapping.path;[IO.Directory]::CreateDirectory((Split-Path -Parent $nativeFile)) | Out-Null;[IO.File]::WriteAllBytes($nativeFile,$bytes)
    }
}finally{$jmodArchive.Dispose()}
try {[IO.File]::WriteAllBytes($jmodPath,([byte[]](74,77,1,0)+$jmodZip.ToArray()))}finally{$jmodZip.Dispose()}
$sources=@(
    [pscustomobject]@{id='openal-soft';version='1.24.3';commit=$openalCommit;url='https://example.invalid/synthetic-openal';archive=(Material 'sources/openal-fixture.tar.gz' 'Synthetic compressed archive placeholder');archiveEntries=1;expandedBytes=10},
    [pscustomobject]@{id='microsoft-openjdk';version=$runtime.runtimeVersion;commit=$jdkCommit;url='https://example.invalid/synthetic-jdk';archive=(Material 'sources/jdk-fixture.tar.gz' 'Synthetic compressed archive placeholder');archiveEntries=1;expandedBytes=10}
)
$jarPath=Join-Path $image 'app/lwjgl-openal-3.3.6-natives-windows.jar';[IO.Directory]::CreateDirectory((Split-Path -Parent $jarPath)) | Out-Null
$nativeEntry='windows/x64/org/lwjgl/openal/OpenAL.dll';$gitEntry='META-INF/'+$nativeEntry+'.git'
$dllBytes=[Text.Encoding]::ASCII.GetBytes('MZ synthetic 1.1 ALSOFT 1.24.3'+[char]0)
$jar=[IO.Compression.ZipFile]::Open($jarPath,[IO.Compression.ZipArchiveMode]::Create)
try {
    foreach($name in @($nativeEntry,$gitEntry)) {
        $entry=$jar.CreateEntry($name);$stream=$entry.Open()
        try {$bytes=if($name -eq $nativeEntry){$dllBytes}else{[Text.Encoding]::ASCII.GetBytes($openalCommit)};$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
    }
}finally{$jar.Dispose()}
$dllStream=[IO.MemoryStream]::new($dllBytes)
try{$dllHash=Get-ReleaseStreamHash $dllStream}finally{$dllStream.Dispose()}
$native=[pscustomobject]@{sourceId='openal-soft';jar=[IO.Path]::GetFileName($jarPath);jarSha256=(Get-ReleaseSha256 $jarPath);entry=$nativeEntry;sha256=$dllHash;gitEntry=$gitEntry}
$index=[pscustomobject]@{schemaVersion=1;sources=@($sources | ForEach-Object {@{id=$_.id;version=$_.version;commit=$_.commit;url=$_.url;archive=$_.archive.path;bytes=$_.archive.bytes;sha256=$_.archive.sha256}});nativeLibrary=$native;runtime=$runtime;documents=$documents}
$indexPath=Join-Path $licenseRoot 'source-index.json';Write-ReleaseJson $indexPath $index
$report=[pscustomobject]@{schemaVersion=1;status='SOURCE_AND_NOTICE_MATERIALS_VERIFIED';distributionApproval='NOT_GRANTED';inputIndexSha256=('c'*64);
    selectedJdkReleaseSha256=(Get-ReleaseSha256 (Join-Path $jdkRoot 'release'));
    index=@{path='source-index.json';bytes=(Get-Item -LiteralPath $indexPath).Length;sha256=(Get-ReleaseSha256 $indexPath)};sources=$sources;documents=$documents;nativeLibrary=$native;runtime=$runtime}
$runtimeBinding=Get-ReleaseRuntimeBinding $jdkRoot $image $report
Check ($runtimeBinding.binaries.Count -eq 2 -and $runtimeBinding.jmod.sha256 -eq (Get-ReleaseSha256 $jmodPath)) 'actual selected JMOD header and two native entry hashes'
$originalJmod=[IO.File]::ReadAllBytes($jmodPath);$wrongJmod=[byte[]]$originalJmod.Clone();$wrongJmod[0]=0
[IO.File]::WriteAllBytes($jmodPath,$wrongJmod)
Reject {Get-ReleaseRuntimeBinding $jdkRoot $image $report} 'invalid JMOD prefix rejected'
[IO.File]::WriteAllBytes($jmodPath,$originalJmod)
$runtimeJvm=Join-Path $image 'runtime/bin/server/jvm.dll';$originalJvm=[IO.File]::ReadAllBytes($runtimeJvm)
[IO.File]::AppendAllText($runtimeJvm,'changed')
Reject {Get-ReleaseRuntimeBinding $jdkRoot $image $report} 'runtime JVM differs from selected JMOD before packaging'
[IO.File]::WriteAllBytes($runtimeJvm,$originalJvm)
$report.selectedJdkReleaseSha256='d'*64
Reject {Get-ReleaseRuntimeBinding $jdkRoot $image $report} 'selected JDK metadata differs from corresponding source'
$report.selectedJdkReleaseSha256=Get-ReleaseSha256 (Join-Path $jdkRoot 'release')
$reportPath=Join-Path $image 'reports/source-distribution.json';Write-ReleaseJson $reportPath $report
$notices=@('CC-BY-4.0.txt','CC0-1.0.txt','Roboto-OFL.txt' | ForEach-Object {$item=Material "assets/$_" "Synthetic notice $_";@{path=('licenses/'+$item.path);bytes=$item.bytes;sha256=$item.sha256}})
$packageMetadata=@{runtimeBinding=$runtimeBinding;sourceDistribution=@{path='reports/source-distribution.json';sha256=(Get-ReleaseSha256 $reportPath);inputIndexSha256=$report.inputIndexSha256;assetNotices=$notices}}
Write-ReleaseJson (Join-Path $image 'reports/package-verification.json') $packageMetadata
$zipPath=Join-Path $work 'synthetic-materials-only.zip';New-ReleaseZip $image $zipPath
$verified=Assert-ReleaseSourceDistribution $zipPath
Check ($verified.status -eq 'SOURCE_AND_NOTICE_MATERIALS_VERIFIED') 'full source/notice ZIP with real two-key jlink release shape'
function Alter-Zip([string]$Name,[string]$EntryName,[string]$Replacement,[bool]$Delete=$false) {
    $path=Join-Path $work $Name;Copy-Item -LiteralPath $zipPath -Destination $path
    $zip=[IO.Compression.ZipFile]::Open($path,[IO.Compression.ZipArchiveMode]::Update)
    try {
        $entry=$zip.GetEntry($EntryName);if(!$entry){throw 'Fixture entry missing before mutation'};$entry.Delete()
        if(!$Delete){$entry=$zip.CreateEntry($EntryName);$writer=[IO.StreamWriter]::new($entry.Open());try{$writer.Write($Replacement)}finally{$writer.Dispose()}}
    }finally{$zip.Dispose()}
    return $path
}
$bad=Alter-Zip 'missing-source.zip' 'WreckRiff/licenses/sources/jdk-fixture.tar.gz' '' $true
Reject {Assert-ReleaseSourceDistribution $bad} 'missing full archive'
$bad=Alter-Zip 'tampered-source.zip' 'WreckRiff/licenses/sources/openal-fixture.tar.gz' 'tampered'
Reject {Assert-ReleaseSourceDistribution $bad} 'tampered source bytes'
$bad=Alter-Zip 'missing-notice.zip' 'WreckRiff/licenses/openal/REPLACEMENT.md' '' $true
Reject {Assert-ReleaseSourceDistribution $bad} 'missing replacement instructions'
$bad=Alter-Zip 'missing-asset-notice.zip' 'WreckRiff/licenses/assets/CC0-1.0.txt' '' $true
Reject {Assert-ReleaseSourceDistribution $bad} 'missing external asset notice'
$bad=Alter-Zip 'wrong-jvm.zip' 'WreckRiff/runtime/release' $jlinkRelease.Replace('21.0.11','21.0.12')
Reject {Assert-ReleaseSourceDistribution $bad} 'bundled JDK version differs from corresponding source'
$bad=Alter-Zip 'missing-jfr.zip' 'WreckRiff/runtime/release' $jlinkRelease.Replace(' jdk.jfr','')
Reject {Assert-ReleaseSourceDistribution $bad} 'bundled runtime omitted profiling module'
$bad=Alter-Zip 'wrong-native.zip' ('WreckRiff/app/'+$native.jar) 'tampered native JAR'
Reject {Assert-ReleaseSourceDistribution $bad} 'native JAR differs from verified source binding'
$bad=Alter-Zip 'missing-java-version.zip' 'WreckRiff/runtime/release' ('MODULES="'+($modules -join ' ')+'"')
Reject {Assert-ReleaseSourceDistribution $bad} 'missing jlink Java version rejected'
$bad=Alter-Zip 'missing-modules.zip' 'WreckRiff/runtime/release' 'JAVA_VERSION="21.0.11"'
Reject {Assert-ReleaseSourceDistribution $bad} 'missing jlink module list rejected'
$bad=Alter-Zip 'contradictory-vendor.zip' 'WreckRiff/runtime/release' ($jlinkRelease+'IMPLEMENTOR="Other"')
Reject {Assert-ReleaseSourceDistribution $bad} 'optional jlink metadata cannot contradict source identity'
foreach($nativePath in @('runtime/bin/java.exe','runtime/bin/server/jvm.dll')) {
    $bad=Alter-Zip (([IO.Path]::GetFileName($nativePath))+'.zip') ('WreckRiff/'+$nativePath) 'modified runtime binary'
    Reject {Assert-ReleaseSourceDistribution $bad} 'bundled binary changed after selected JMOD comparison'
}
foreach($mutation in @('missing','duplicate','wrong-entry','wrong-source-release')) {
    $changed=$packageMetadata | ConvertTo-Json -Depth 30 | ConvertFrom-Json
    switch($mutation) {
        'missing' {$changed.PSObject.Properties.Remove('runtimeBinding')}
        'duplicate' {$changed.runtimeBinding.binaries[1]=$changed.runtimeBinding.binaries[0]}
        'wrong-entry' {$changed.runtimeBinding.binaries[0].entry='bin/unrelated.exe'}
        'wrong-source-release' {$changed.runtimeBinding.selectedJdkReleaseSha256='d'*64}
    }
    $bad=Alter-Zip ("runtime-binding-$mutation.zip") 'WreckRiff/reports/package-verification.json' ($changed | ConvertTo-Json -Depth 30)
    Reject {Assert-ReleaseSourceDistribution $bad} "invalid selected JMOD binding: $mutation"
}
$tokens=$null;$parseErrors=$null;$null=[Management.Automation.Language.Parser]::ParseFile($PSCommandPath,[ref]$tokens,[ref]$parseErrors)
Check ($parseErrors.Count -eq 0) 'PowerShell source-package fixture parser'
Write-Output "Synthetic source-package fixtures PASS: $script:count checks. No actual sources, game or release approval exercised. $work"

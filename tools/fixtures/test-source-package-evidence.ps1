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
[IO.File]::WriteAllText($runtimePath,$release,[Text.UTF8Encoding]::new($false))
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
    index=@{path='source-index.json';bytes=(Get-Item -LiteralPath $indexPath).Length;sha256=(Get-ReleaseSha256 $indexPath)};sources=$sources;documents=$documents;nativeLibrary=$native;runtime=$runtime}
$reportPath=Join-Path $image 'reports/source-distribution.json';Write-ReleaseJson $reportPath $report
$notices=@('CC-BY-4.0.txt','CC0-1.0.txt','Roboto-OFL.txt' | ForEach-Object {$item=Material "assets/$_" "Synthetic notice $_";@{path=('licenses/'+$item.path);bytes=$item.bytes;sha256=$item.sha256}})
Write-ReleaseJson (Join-Path $image 'reports/package-verification.json') @{sourceDistribution=@{path='reports/source-distribution.json';sha256=(Get-ReleaseSha256 $reportPath);inputIndexSha256=$report.inputIndexSha256;assetNotices=$notices}}
$zipPath=Join-Path $work 'synthetic-materials-only.zip';New-ReleaseZip $image $zipPath
$verified=Assert-ReleaseSourceDistribution $zipPath
Check ($verified.status -eq 'SOURCE_AND_NOTICE_MATERIALS_VERIFIED') 'full source/notice ZIP contract'
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
$bad=Alter-Zip 'wrong-jvm.zip' 'WreckRiff/runtime/release' $release.Replace('21.0.11','21.0.12')
Reject {Assert-ReleaseSourceDistribution $bad} 'bundled JDK version differs from corresponding source'
$bad=Alter-Zip 'missing-jfr.zip' 'WreckRiff/runtime/release' $release.Replace(' jdk.jfr','')
Reject {Assert-ReleaseSourceDistribution $bad} 'bundled runtime omitted profiling module'
$bad=Alter-Zip 'wrong-native.zip' ('WreckRiff/app/'+$native.jar) 'tampered native JAR'
Reject {Assert-ReleaseSourceDistribution $bad} 'native JAR differs from verified source binding'
Write-Output "Synthetic source-package fixtures PASS: $script:count checks. No actual sources, game or release approval exercised. $work"

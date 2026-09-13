# Synthetic contract tests only. No application/GUI is launched and no release is approved.
param([string]$JdkHome='C:\Users\vitalii\.jdks\ms-21.0.11')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../release-evidence.ps1')
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$work=Join-Path $root ('build/release-tool-fixtures/'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($work) | Out-Null
$script:checks=0
function Check([bool]$Condition,[string]$Name) {if(!$Condition){throw "Fixture failed: $Name"};$script:checks++}
function Reject([scriptblock]$Action,[string]$Name) {$rejected=$false;try {& $Action} catch {$rejected=$true};Check $rejected $Name}
function Clone($Value){
    $json=$Value | ConvertTo-Json -Depth 30
    # PowerShell 7.5 otherwise silently changes ISO strings to DateTime during a
    # clone, letting unrelated process-identity failures mask a broken gate.
    if((Get-Command ConvertFrom-Json).Parameters.ContainsKey('DateKind')){return ConvertFrom-Json -InputObject $json -DateKind String}
    return ConvertFrom-Json -InputObject $json
}
$hashFixture=Join-Path $work 'hash [literal].bin'
[IO.File]::WriteAllBytes($hashFixture,[Text.Encoding]::ASCII.GetBytes('abc'))
& {
    function Get-FileHash {throw 'Simulated unavailable PowerShell file-hash command'}
    Check ((Get-ReleaseSha256 $hashFixture) -ceq 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad') 'release SHA-256 works without command autoload and treats brackets literally'
}
$exclusiveHash=[IO.File]::Open($hashFixture,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try {Check ($exclusiveHash.Length -eq 3) 'hashing releases its file handle'} finally {$exclusiveHash.Dispose()}
Reject {Get-ReleaseSha256 (Join-Path $work 'missing-hash.bin')} 'missing hash input is rejected'
$nativeFixture=Join-Path $work 'native stderr fixture.cmd'
[IO.File]::WriteAllText($nativeFixture,"@echo off`r`necho stdout-before`r`necho INFO: ordinary native stderr 1>&2`r`necho stdout-after`r`nexit /b %1`r`n",[Text.UTF8Encoding]::new($false))
$nativeLog=Join-Path $work 'native-success.log'
Check ((Invoke-ReleaseNativeCommand $nativeFixture @('0') $nativeLog) -eq 0) 'native stderr with exit zero succeeds under Stop'
$nativeText=[IO.File]::ReadAllText($nativeLog)
Check ($nativeText.Contains('stdout-before') -and $nativeText.Contains('ordinary native stderr') -and $nativeText.Contains('stdout-after')) 'native success preserves both streams through process completion'
Check ($ErrorActionPreference -eq 'Stop') 'native success restores caller error handling'
$nativeLog=Join-Path $work 'native-failure.log';$nativeFailure=$null
try {$null=Invoke-ReleaseNativeCommand $nativeFixture @('23') $nativeLog} catch {$nativeFailure=$_}
Check ($null -ne $nativeFailure -and $nativeFailure.Exception.Data['exitCode'] -eq 23) 'native nonzero exit rejects and retains exact exit code'
$nativeText=[IO.File]::ReadAllText($nativeLog)
Check ($nativeText.Contains('ordinary native stderr') -and $nativeText.Contains('stdout-after')) 'native failure preserves complete stderr and stdout log'
Check ($ErrorActionPreference -eq 'Stop') 'native failure restores caller error handling'
$peakChildScript=Join-Path $work 'peak-child.ps1';$peakReady=Join-Path $work 'peak-ready';$peakRelease=Join-Path $work 'peak-release'
[IO.File]::WriteAllText($peakChildScript,@'
param([string]$Ready,[string]$Release)
[byte[]]$bytes=New-Object byte[] (32MB)
for($i=0;$i -lt $bytes.Length;$i+=4096){$bytes[$i]=42}
[IO.File]::WriteAllText($Ready,'ready')
$deadline=[DateTime]::UtcNow.AddSeconds(15)
while(!(Test-Path -LiteralPath $Release) -and [DateTime]::UtcNow -lt $deadline){Start-Sleep -Milliseconds 50}
'@,[Text.UTF8Encoding]::new($false))
$peakChild=Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList @('-NoProfile','-File',$peakChildScript,$peakReady,$peakRelease) -WindowStyle Hidden -PassThru
try {
    $peakHandle=$peakChild.Handle;$deadline=[DateTime]::UtcNow.AddSeconds(15)
    while(!(Test-Path -LiteralPath $peakReady) -and !$peakChild.HasExited -and [DateTime]::UtcNow -lt $deadline){Start-Sleep -Milliseconds 50}
    Check (Test-Path -LiteralPath $peakReady) 'owned headless memory fixture allocated and touched its pages'
    $livePeak=Get-ReleaseProcessPeakWorkingSet $peakHandle
    Check ($livePeak -ge 32MB) 'native sampler observes allocated process working-set peak'
    [IO.File]::WriteAllText($peakRelease,'exit')
    Check ($peakChild.WaitForExit(15000) -and $peakChild.ExitCode -eq 0) 'owned memory fixture exits normally'
    Check ((Get-ReleaseProcessPeakWorkingSet $peakHandle) -ge $livePeak) 'retained process handle preserves OS peak after exit'
} finally {if(!$peakChild.HasExited){$peakChild.Kill();$null=$peakChild.WaitForExit(15000)}}
Reject {Get-ReleaseProcessPeakWorkingSet ([IntPtr]::Zero)} 'unavailable final OS counter cannot be silently treated as zero'
$unicodeName=([char]0x041f).ToString()+[char]0x0440+[char]0x043e+[char]0x0432+[char]0x0435+[char]0x0440+[char]0x043a+[char]0x0430
$unicodePath=Join-Path $work "$unicodeName final ZIP/nested/$unicodeName.json"
Write-ReleaseJson $unicodePath ([ordered]@{name=$unicodeName;nested=@{path=$unicodePath;captures=@(@{path=$unicodePath;label=$unicodeName})}})
$unicodeJson=Read-ReleaseJson $unicodePath
Check ($unicodeJson.name -ceq $unicodeName -and $unicodeJson.nested.path -ceq $unicodePath -and $unicodeJson.nested.captures[0].label -ceq $unicodeName) 'UTF8 JSON preserves nested Cyrillic strings and paths'
Check (Test-Path -LiteralPath $unicodeJson.nested.captures[0].path -PathType Leaf) 'decoded Cyrillic JSON path resolves to the real file'
$encodingJava=Join-Path $work 'EncodingProbe.java'
[IO.File]::WriteAllText($encodingJava,@'
public class EncodingProbe {
    public static void main(String[] args) {
        System.out.println("DIAGNOSTIC_REPORT: "+args[0]);
        System.err.println("stdout="+System.out.charset()+";stderr="+System.err.charset()+";native="+System.getProperty("native.encoding"));
    }
}
'@,[Text.UTF8Encoding]::new($false))
foreach($encodingMode in @('default','utf8')) {
    $encodingOut=Join-Path $work "java-$encodingMode-stdout.log";$encodingErr=Join-Path $work "java-$encodingMode-stderr.log"
    $encodingArgs=@(('"'+$encodingJava+'"'),('"'+$unicodePath+'"'))
    if($encodingMode -eq 'utf8'){$encodingArgs=@('-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8')+$encodingArgs}
    $encodingProcess=Start-Process -FilePath (Join-Path $JdkHome 'bin/java.exe') -ArgumentList $encodingArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput $encodingOut -RedirectStandardError $encodingErr
    $null=$encodingProcess.Handle
    try {
        if(!$encodingProcess.WaitForExit(15000)){throw 'Java encoding fixture timed out.'}
        Check ($encodingProcess.ExitCode -eq 0) "real Java $encodingMode encoding probe exits successfully"
        if($encodingMode -eq 'utf8') {
            $encodingLine=Get-Content -LiteralPath $encodingOut -Encoding UTF8 | Where-Object {$_.StartsWith('DIAGNOSTIC_REPORT: ')} | Select-Object -First 1
            $decodedPath=$encodingLine.Substring('DIAGNOSTIC_REPORT: '.Length).Trim()
            Check ($decodedPath -ceq $unicodePath -and (Test-Path -LiteralPath $decodedPath -PathType Leaf)) 'forced UTF8 Java stdout resolves the exact Cyrillic diagnostic path'
            Check ((Get-Content -LiteralPath $encodingErr -Raw -Encoding UTF8).Contains('stdout=UTF-8;stderr=UTF-8')) 'packaged JVM options establish both UTF8 streams'
        }
    } finally {
        if(!$encodingProcess.HasExited){$encodingProcess.Kill();$null=$encodingProcess.WaitForExit(5000)}
        $encodingProcess.Dispose()
    }
}
$source='a'*64;$inputs='b'*64
$jarRoot=Join-Path $work 'jar';[IO.Directory]::CreateDirectory($jarRoot) | Out-Null
[IO.File]::WriteAllText((Join-Path $jarRoot 'build-info.properties'),"version=0.0.1`ncommit=synthetic-fixture`nsourceSha256=$source`n",[Text.UTF8Encoding]::new($false))
$image=Join-Path $work 'WreckRiff';[IO.Directory]::CreateDirectory((Join-Path $image 'app')) | Out-Null
$jarPath=Join-Path $image 'app/wreck-riff-0.0.1.jar'
[IO.Compression.ZipFile]::CreateFromDirectory($jarRoot,$jarPath)
$jarHash=Get-ReleaseSha256 $jarPath
[IO.Directory]::CreateDirectory((Join-Path $image 'reports')) | Out-Null
[IO.Directory]::CreateDirectory((Join-Path $image 'licenses')) | Out-Null
[IO.File]::WriteAllText((Join-Path $image 'README.txt'),'Wreck Riff 0.0.1 - SYNTHETIC FIXTURE ONLY')
[IO.File]::WriteAllText((Join-Path $image 'reports/asset-register.csv'),"path,sha256`nsynthetic,none`n")
Copy-Item -LiteralPath (Join-Path $root 'docs/THIRD_PARTY_NOTICES.md') -Destination (Join-Path $image 'licenses/THIRD_PARTY_NOTICES.md')
$documentation=New-ReleaseDocumentation $root $image '0.0.1'
Check ($documentation.files.Count -eq 8 -and $documentation.scope -eq 'PACKAGE_CANDIDATE_SNAPSHOT') 'portable guide has the complete bounded candidate document set'
Check (@($documentation.sources | Where-Object {$_.path -eq 'README.md' -and $_.sha256 -eq (Get-ReleaseSha256 (Join-Path $root 'README.md'))}).Count -eq 1) 'guide records current source README hash'
$portableControls=[IO.File]::ReadAllText((Join-Path $image 'CONTROLS.txt'))
$portableNotes=[IO.File]::ReadAllText((Join-Path $image 'RELEASE_NOTES.txt'))
Check ($portableControls.Contains('Grinder') -and $portableControls.Contains('Spark') -and $portableNotes.Contains('Rivet') -and $portableNotes -notmatch '400\s*HP') 'portable guides retain current three-chassis content without obsolete 400 HP contract'
Check ($portableControls -notmatch '\[[^\]]+\]\([^)]+\)' -and $portableNotes -notmatch '\[[^\]]+\]\([^)]+\)') 'portable guides contain no broken repository Markdown links'
$packageMetadata=@{schemaVersion=2;status='PACKAGE_STRUCTURE_VERIFIED';version='0.0.1';sourceSha256=$source;mainJarSha256=$jarHash;verificationInputsSha256=$inputs;packagedAtUtc=[DateTime]::UtcNow.ToString('o');documentation=$documentation}
Write-ReleaseJson (Join-Path $image 'reports/package-verification.json') $packageMetadata
$zipPath=Join-Path $work 'synthetic-not-a-release.zip'
New-ReleaseZip $image $zipPath
$identity=Get-ReleasePackageIdentity $zipPath
Check ($identity.mainJarSha256 -eq $jarHash -and $identity.sourceSha256 -eq $source) 'nested JAR identity'
function Change-DocumentFixture([string]$Name,[string]$EntryName,[string]$Text,[bool]$Delete=$false) {
    $path=Join-Path $work $Name;Copy-Item -LiteralPath $zipPath -Destination $path
    $archive=[IO.Compression.ZipFile]::Open($path,[IO.Compression.ZipArchiveMode]::Update)
    try {
        $entry=$archive.GetEntry('WreckRiff/'+$EntryName);if(!$entry){throw 'Missing initial document fixture'};$entry.Delete()
        if(!$Delete){$entry=$archive.CreateEntry('WreckRiff/'+$EntryName);$writer=[IO.StreamWriter]::new($entry.Open(),[Text.UTF8Encoding]::new($false));try{$writer.Write($Text)}finally{$writer.Dispose()}}
    }finally{$archive.Dispose()}
    return $path
}
$badDocument=Change-DocumentFixture 'missing-controls.zip' 'CONTROLS.txt' '' $true
Reject {Get-ReleasePackageIdentity $badDocument} 'ZIP missing current control guide'
$badDocument=Change-DocumentFixture 'tampered-notes.zip' 'RELEASE_NOTES.txt' 'modified after packaging'
Reject {Get-ReleasePackageIdentity $badDocument} 'ZIP altered release notes'
$badMetadata=Clone $packageMetadata;$badMetadata.documentation.version='0.0.2'
$badDocument=Change-DocumentFixture 'stale-doc-version.zip' 'reports/package-verification.json' ($badMetadata | ConvertTo-Json -Depth 30)
Reject {Get-ReleasePackageIdentity $badDocument} 'document version differs from packaged JAR'
$badMetadata=Clone $packageMetadata;$badMetadata.documentation.finalEvidence='FINAL_ACCEPTED'
$badDocument=Change-DocumentFixture 'invented-acceptance.zip' 'reports/package-verification.json' ($badMetadata | ConvertTo-Json -Depth 30)
Reject {Get-ReleasePackageIdentity $badDocument} 'package-time document cannot substitute future release acceptance'
$badMetadata=Clone $packageMetadata;$badMetadata.documentation.files+=@($badMetadata.documentation.files[0])
$badDocument=Change-DocumentFixture 'duplicate-doc-binding.zip' 'reports/package-verification.json' ($badMetadata | ConvertTo-Json -Depth 30)
Reject {Get-ReleasePackageIdentity $badDocument} 'duplicate documentation evidence'
$inputFixture=Join-Path $work 'input-hash-fixture'
foreach($directory in @('src/main','src/tools','tools','gradle','docs')){[IO.Directory]::CreateDirectory((Join-Path $inputFixture $directory)) | Out-Null}
[IO.File]::WriteAllText((Join-Path $inputFixture 'README.md'),'first user guide')
$firstInputs=Get-ReleaseInputHash $inputFixture;$firstRuntime=Get-ReleaseInputHash $inputFixture -RuntimeOnly
[IO.File]::WriteAllText((Join-Path $inputFixture 'README.md'),'updated user guide')
Check ((Get-ReleaseInputHash $inputFixture) -ne $firstInputs) 'README changes invalidate verification inputs'
Check ((Get-ReleaseInputHash $inputFixture -RuntimeOnly) -eq $firstRuntime) 'documentation does not change runtime source identity'
Assert-ReleaseIdentity $identity $identity;$script:checks++
$other=Clone $identity;$other.zipSha256='c'*64
Reject {Assert-ReleaseIdentity $other $identity} 'ZIP mismatch'
$other=Clone $identity;$other.sourceSha256='c'*64
Reject {Assert-ReleaseIdentity $other $identity} 'source mismatch'
$normal=[pscustomobject]@{schemaVersion=2;sourceSha256=$source;version='0.0.1';mode='normal';pid=123;
    javaHome=(Join-Path $image 'runtime');jdk='21.0.11';status='CLOSED';dev=$false;windowVisible=$true;audioEnabled=$true;errors=@();
    renderedFrames=240;shutdownComplete=$true;progressFlushed=$true;undrawableSeconds=25;
    startedAtUtc=[DateTime]::UtcNow.AddMinutes(-1).ToString('o');closedAtUtc=[DateTime]::UtcNow.ToString('o')}
Assert-ReleaseDiagnostic $normal $identity $image 'normal';$script:checks++
$invalid=Clone $normal;$invalid.status='STARTED'
Reject {Assert-ReleaseDiagnostic $invalid $identity $image 'normal'} 'normal incomplete startup'
$invalid=Clone $normal;$invalid.progressFlushed=$false
Reject {Assert-ReleaseDiagnostic $invalid $identity $image 'normal'} 'normal progress not flushed'
$invalid=Clone $normal;$invalid.renderedFrames=0
Reject {Assert-ReleaseDiagnostic $invalid $identity $image 'normal'} 'normal window never rendered'
$invalid=Clone $normal;$invalid.dev=$true
Reject {Assert-ReleaseDiagnostic $invalid $identity $image 'normal'} 'normal dev launch'
$invalid=Clone $normal;$invalid.javaHome=Join-Path $work 'other/runtime'
Reject {Assert-ReleaseDiagnostic $invalid $identity $image 'normal'} 'different bundled runtime'
$oldProfile=[pscustomobject]@{stats=[pscustomobject]@{schemaVersion=3};settings=[pscustomobject]@{schemaVersion=4}}
$newProfile=[pscustomobject]@{stats=[pscustomobject]@{schemaVersion=4;layoutRevision=2};settings=[pscustomobject]@{schemaVersion=4}}
Assert-ReleaseProfileMigration $oldProfile $newProfile;$script:checks++
$invalid=Clone $newProfile;$invalid.stats.schemaVersion=3
Reject {Assert-ReleaseProfileMigration $oldProfile $invalid} 'old statistics schema cannot pass map migration'
$invalid=Clone $newProfile;$invalid.stats.layoutRevision=1
Reject {Assert-ReleaseProfileMigration $oldProfile $invalid} 'old layout cannot pass map migration'
Reject {Assert-ReleaseProfileMigration $newProfile $newProfile} 'already-current profile cannot claim migration evidence'
# Automatic verification must allow normal GLFW minimization, while rejecting
# any actual interval in which its measured/captured window was not drawable.
foreach($automaticMode in @('graphics-smoke','benchmark','soak','ui-review')) {
    $automatic=Clone $normal;$automatic.mode=$automaticMode;$automatic.dev=$true;$automatic.undrawableSeconds=0
    $automatic | Add-Member -NotePropertyName autoIconify -NotePropertyValue $true
    Assert-ReleaseDiagnostic $automatic $identity $image $automaticMode;$script:checks++
    $invalid=Clone $automatic;$invalid.autoIconify=$false
    Reject {Assert-ReleaseDiagnostic $invalid $identity $image $automaticMode} "$automaticMode cannot disable automatic iconification"
    $invalid=Clone $automatic;$invalid.autoIconify='true'
    Reject {Assert-ReleaseDiagnostic $invalid $identity $image $automaticMode} "$automaticMode requires a boolean iconification observation"
    $invalid=Clone $automatic;$invalid.undrawableSeconds=.001
    Reject {Assert-ReleaseDiagnostic $invalid $identity $image $automaticMode} "$automaticMode cannot count minimized time as valid evidence"
}
$extracted=Expand-ReleasePackage $zipPath (Join-Path $work 'extracted')
Assert-ReleaseImage $zipPath $extracted.image;$script:checks++
Reject {Expand-ReleasePackage $zipPath (Join-Path $work 'extracted')} 'previous extraction preserved'
[IO.File]::AppendAllText((Join-Path $extracted.image 'app/wreck-riff-0.0.1.jar'),'tampered')
Reject {Assert-ReleaseImage $zipPath $extracted.image} 'changed extracted JAR'
$badZip=Join-Path $work 'unsafe.zip';Copy-Item -LiteralPath $zipPath -Destination $badZip
$archive=[IO.Compression.ZipFile]::Open($badZip,[IO.Compression.ZipArchiveMode]::Update)
try {$null=$archive.CreateEntry('WreckRiff/../escape')} finally {$archive.Dispose()}
Reject {Get-ReleasePackageIdentity $badZip} 'ZIP traversal'
$badZip=Join-Path $work 'duplicate.zip';Copy-Item -LiteralPath $zipPath -Destination $badZip
$archive=[IO.Compression.ZipFile]::Open($badZip,[IO.Compression.ZipArchiveMode]::Update)
try {$null=$archive.CreateEntry('WreckRiff/APP/wreck-riff-0.0.1.jar')} finally {$archive.Dispose()}
Reject {Get-ReleasePackageIdentity $badZip} 'Windows case-insensitive duplicate'
$started=[DateTime]::UtcNow.AddSeconds(-5);$xml=Join-Path $work 'TEST-fixture.xml'
[IO.File]::WriteAllText($xml,'<testsuite tests="1" failures="0" errors="0" skipped="0"/>')
Check ((Assert-ReleaseTestXml $xml $started) -eq 1) 'successful fresh XML'
$xmlArtifact=Get-ReleaseArtifact $xml
Assert-ReleaseArtifact $xmlArtifact;$script:checks++
Reject {Assert-ReleaseTestXml $xml ([DateTime]::UtcNow.AddMinutes(1))} 'stale XML'
[IO.File]::WriteAllText($xml,'<testsuite tests="1" failures="0" errors="0" skipped="1"/>')
Reject {Assert-ReleaseArtifact $xmlArtifact} 'changed evidence hash'
Reject {Assert-ReleaseTestXml $xml $started} 'skipped XML'
$processLog=Join-Path $work 'process.log'
[IO.File]::WriteAllText($processLog,"WARNING: Native library already loaded.`nWARNING: Unrecognized mouse button.`n")
Assert-ReleaseProcessLogs @($processLog);$script:checks++
foreach($crash in @('Exception in thread "decoder" java.lang.IllegalStateException','java.lang.AssertionError: audio stream closed','A fatal error has been detected by the Java Runtime Environment:','EXCEPTION_ACCESS_VIOLATION (0xc0000005)')) {
    [IO.File]::AppendAllText($processLog,$crash+"`n")
    Reject {Assert-ReleaseProcessLogs @($processLog)} 'process failure with a successful launcher exit'
    [IO.File]::WriteAllText($processLog,'')
}
$at=[DateTime]::UtcNow;$processAt=$at.AddSeconds(-1).ToString('o')
$samples=@(0..1801 | ForEach-Object {[pscustomobject]@{seconds=$_+1;observedAtUtc=$at.AddSeconds($_).ToString('o');workingSetBytes=1000000;peakWorkingSetBytes=1000000;handles=100}})
$memory=[pscustomobject]@{pid=123;processExited=$true;processStartTimeUtc=$processAt;samples=$samples;peakWorkingSetBytes=1000000;finalPeakWorkingSetBytes=1000000;finalPeakObservedAfterExit=$true}
$report=[pscustomobject]@{status='PASS';gamePid=123;processStartTimeUtc=$processAt}
$diagnostic=[pscustomobject]@{status='BENCHMARK_MEASURED';mode='benchmark';arenaId='construction_17';pid=123;releaseEligible=$true;
    width=1920;height=1080;msaaSamples=4;vsync=$false;audioEnabled=$true;windowVisible=$true;autoIconify=$true;detailedProfiling=$false;videoRecording=$false;requestedRenderFps=0;invalidBenchmarkWindowObserved=$false;
    requestedSeconds=600;warmupActiveSeconds=30;measuredActiveSeconds=600;undrawableSeconds=0;
    activeCombatFrames=[pscustomobject]@{sampleSeconds=600;frames=36000;p95FrameMs=16;p99FrameMs=20;maxFrameMs=80;framesOver100ms=0};
    phaseMetrics=[pscustomobject]@{droppedSimulationSeconds=0};measuredCoverage=[pscustomobject]@{arenaCombatSeconds=300;bossCombatSeconds=300;maximumEffects=1;maximumLaunchingVehicles=1}}
Assert-ReleaseBenchmark $report $diagnostic $memory 'construction_17';$script:checks++
$profileEnvironment=Clone $diagnostic;$profileEnvironment.detailedProfiling=$true
Assert-ReleaseCombatEnvironment $profileEnvironment $true;$script:checks++
Reject {Assert-ReleaseCombatEnvironment $diagnostic $true} 'explicit short profile must actually enable its profiler'
$profileEnvironment.videoRecording=$true
Reject {Assert-ReleaseCombatEnvironment $profileEnvironment $true} 'profiling permission does not permit video in performance evidence'
$validPeak=Clone $memory;foreach($sample in $validPeak.samples){$sample.peakWorkingSetBytes=1200000};$validPeak.peakWorkingSetBytes=1300000;$validPeak.finalPeakWorkingSetBytes=1300000
Assert-ReleaseBenchmark $report $diagnostic $validPeak 'construction_17';$script:checks++
$invalid=Clone $memory;foreach($sample in $invalid.samples){$sample.peakWorkingSetBytes=2GB};$invalid.peakWorkingSetBytes=2GB
Reject {Assert-ReleaseBenchmark $report $diagnostic $invalid 'construction_17'} 'loading peak before the first sample exceeds the budget despite low current RSS'
$invalid=Clone $memory;foreach($sample in $invalid.samples | Select-Object -Skip 500){$sample.peakWorkingSetBytes=2GB};$invalid.peakWorkingSetBytes=2GB
Reject {Assert-ReleaseBenchmark $report $diagnostic $invalid 'construction_17'} 'OS peak catches a transient spike between current working-set samples'
$invalid=Clone $memory;$invalid.samples[500].PSObject.Properties.Remove('peakWorkingSetBytes')
Reject {Assert-ReleaseBenchmark $report $diagnostic $invalid 'construction_17'} 'working-set snapshots alone do not establish process peak'
$invalid=Clone $memory;$invalid.samples[500].peakWorkingSetBytes=999999
Reject {Assert-ReleaseBenchmark $report $diagnostic $invalid 'construction_17'} 'process peak cannot decrease or be below current RSS'
$invalid=Clone $memory;$invalid.finalPeakWorkingSetBytes=2GB;$invalid.peakWorkingSetBytes=2GB
Reject {Assert-ReleaseBenchmark $report $diagnostic $invalid 'construction_17'} 'spike after the last live sample is caught by the post-exit OS peak'
$invalid=Clone $memory;$invalid.finalPeakObservedAfterExit=$false
Reject {Assert-ReleaseBenchmark $report $diagnostic $invalid 'construction_17'} 'missing post-exit peak is not a memory PASS'
$invalid=Clone $memory;$invalid.samples[0].seconds=0
Reject {Assert-ReleaseBenchmark $report $diagnostic $invalid 'construction_17'} 'sample time is relative to actual process start, not sampler start'
$invalid=Clone $diagnostic;$invalid.videoRecording=$true
Reject {Assert-ReleaseBenchmark $report $invalid $memory 'construction_17'} 'recording cannot supply a release benchmark'
$invalid=Clone $diagnostic;$invalid.detailedProfiling=$true
Reject {Assert-ReleaseBenchmark $report $invalid $memory 'construction_17'} 'profile cannot supply a release benchmark'
$invalid=Clone $diagnostic;$invalid.requestedRenderFps=60
Reject {Assert-ReleaseBenchmark $report $invalid $memory 'construction_17'} 'capped frame pacing cannot supply a release benchmark'
$invalid=Clone $diagnostic;$invalid.autoIconify=$false
Reject {Assert-ReleaseBenchmark $report $invalid $memory 'construction_17'} 'final benchmark cannot disable automatic iconification'
$invalid=Clone $diagnostic;$invalid.autoIconify='true'
Reject {Assert-ReleaseBenchmark $report $invalid $memory 'construction_17'} 'final benchmark iconification observation must be boolean'
$invalid=Clone $diagnostic;$invalid.undrawableSeconds=.001
Reject {Assert-ReleaseBenchmark $report $invalid $memory 'construction_17'} 'minimized final benchmark remains invalid'
$invalid=Clone $diagnostic;$invalid.invalidBenchmarkWindowObserved=$true
Reject {Assert-ReleaseBenchmark $report $invalid $memory 'construction_17'} 'interrupted benchmark window remains invalid'
$invalid=Clone $diagnostic;$invalid.width=1280
Reject {Assert-ReleaseBenchmark $report $invalid $memory 'construction_17'} 'final benchmark keeps the exact framebuffer requirement'
$invalid=Clone $diagnostic;$invalid.phaseMetrics.droppedSimulationSeconds=.001
Reject {Assert-ReleaseBenchmark $report $invalid $memory 'construction_17'} 'interrupted benchmark cannot hide dropped simulation time'
$invalid=Clone $diagnostic;$invalid.measuredActiveSeconds=599
Reject {Assert-ReleaseBenchmark $report $invalid $memory 'construction_17'} 'short measured run'
$invalid=Clone $diagnostic;$invalid.requestedSeconds=900
Reject {Assert-ReleaseBenchmark $report $invalid $memory 'construction_17'} '600 active seconds do not satisfy a requested 900-second run'
$invalid=Clone $diagnostic;$invalid.measuredCoverage.bossCombatSeconds=0
Reject {Assert-ReleaseBenchmark $report $invalid $memory 'construction_17'} 'missing boss coverage'
$invalid=Clone $diagnostic;$invalid.activeCombatFrames.p95FrameMs=$null
Reject {Assert-ReleaseBenchmark $report $invalid $memory 'construction_17'} 'missing frame percentile'
$invalid=Clone $diagnostic;$invalid.activeCombatFrames.maxFrameMs=101
Reject {Assert-ReleaseBenchmark $report $invalid $memory 'construction_17'} 'over-100ms frame'
$invalid=Clone $memory;$invalid.samples[500].workingSetBytes=2GB
Reject {Assert-ReleaseBenchmark $report $diagnostic $invalid 'construction_17'} 'native working-set excess'
$invalid=Clone $memory;$invalid.pid=124
Reject {Assert-ReleaseBenchmark $report $diagnostic $invalid 'construction_17'} 'unrelated PID'
$invalid=Clone $memory;$invalid.samples=@($invalid.samples | Where-Object {$_.seconds -lt 500 -or $_.seconds -gt 510})
Reject {Assert-ReleaseBenchmark $report $diagnostic $invalid 'construction_17'} 'memory sampling gap'
$arenaIds=@('dead-air-yard','construction_17','neon_zero','euphoria_park')
$loadedModes=@('dead-air-yard/LEGACY')+@($arenaIds | Where-Object {$_ -ne 'dead-air-yard'} | ForEach-Object {"$_/ARENA";"$_/BOSS_DUEL"})
$snapshots=@(0..30 | ForEach-Object {
    $index=$_;$seconds=10+$index*50;$visit=[int][Math]::Floor($index/3);$arena=$arenaIds[$visit%4]
    $mode=if($arena -eq 'dead-air-yard'){'LEGACY'}elseif(([int][Math]::Floor($visit/4))%2 -eq 0){'ARENA'}else{'BOSS_DUEL'}
    $pair=@($arena,$mode)
    foreach($stage in @('LOAD','UNLOAD')) {
        [pscustomobject]@{stage=$stage;elapsedSeconds=$seconds;observedAtEpochMillis=([DateTimeOffset]$at.AddSeconds($seconds)).ToUnixTimeMilliseconds();
            arenaId=$pair[0];mode=$pair[1];phase='ARENA_COMBAT';profileId='rivet';topology='fixture';
            bodies=$(if($stage -eq 'LOAD'){5}else{0});listeners=0;tickListeners=0;projectiles=0;trackers=12;directBufferBytes=1024;textures=4;voices=0;
            saveQueueDepth=0;saveWorkerScheduled=$false;revision=1;persistedRevision=1;collectionRequested=($stage -eq 'UNLOAD')}
        $seconds++
    }
})
$soak=[pscustomobject]@{pid=123;status='SOAK_MEASURED';mode='soak';requestedSeconds=1800;
    width=1920;height=1080;msaaSamples=4;vsync=$false;audioEnabled=$true;windowVisible=$true;autoIconify=$true;detailedProfiling=$false;videoRecording=$false;requestedRenderFps=0;undrawableSeconds=0;
    phaseMetrics=[pscustomobject]@{droppedSimulationSeconds=0};
    soakCoverage=[pscustomobject]@{measuredSeconds=1800;mapChanges=10;retries=20;completedLoads=31;arenaIds=$arenaIds;loadedModes=$loadedModes;resourcesWarmed=$true;coverageComplete=$true};
    resourceChecks=[pscustomobject]@{status='PASS';errors=@();unloadComparisons=9;minimumUnloadComparisons=3};resourceSnapshots=$snapshots}
Assert-ReleaseSoak $report $soak $memory;$script:checks++
$invalid=Clone $soak;$invalid.width=1280
Reject {Assert-ReleaseSoak $report $invalid $memory} 'soak must exercise the required framebuffer'
$invalid=Clone $soak;$invalid.detailedProfiling=$true
Reject {Assert-ReleaseSoak $report $invalid $memory} 'profile cannot supply a release soak'
$invalid=Clone $soak;$invalid.videoRecording=$true
Reject {Assert-ReleaseSoak $report $invalid $memory} 'recording cannot supply a release soak'
$invalid=Clone $soak;$invalid.phaseMetrics.droppedSimulationSeconds=.001
Reject {Assert-ReleaseSoak $report $invalid $memory} 'soak must not lose simulation time'
$invalid=Clone $soak;$invalid.soakCoverage.mapChanges=9
Reject {Assert-ReleaseSoak $report $invalid $memory} 'nine actual map changes are insufficient'
$invalid=Clone $soak;$invalid.soakCoverage.measuredSeconds=1799
Reject {Assert-ReleaseSoak $report $invalid $memory} 'short elapsed soak cannot be accepted'
$invalid=Clone $soak;$invalid.requestedSeconds=2400
Reject {Assert-ReleaseSoak $report $invalid $memory} '1800 seconds do not satisfy a requested 2400-second soak'
$invalid=Clone $soak;$invalid.soakCoverage.retries=21
Reject {Assert-ReleaseSoak $report $invalid $memory} 'claimed Retry count must equal completed reconstructions'
$invalid=Clone $soak;$invalid.soakCoverage.mapChanges=11
Reject {Assert-ReleaseSoak $report $invalid $memory} 'claimed map changes must equal actual load transitions'
$invalid=Clone $soak;$invalid.soakCoverage.completedLoads=32
Reject {Assert-ReleaseSoak $report $invalid $memory} 'claimed completed loads must equal LOAD observations'
$invalid=Clone $soak;$invalid.resourceSnapshots[1].arenaId='construction_17'
Reject {Assert-ReleaseSoak $report $invalid $memory} 'UNLOAD must identify its corresponding LOAD'
$invalid=Clone $soak;$invalid.resourceSnapshots[2].stage='UNLOAD'
Reject {Assert-ReleaseSoak $report $invalid $memory} 'duplicate UNLOAD cannot supply reconstruction evidence'
$invalid=Clone $soak;$invalid.resourceSnapshots=@($invalid.resourceSnapshots | Select-Object -SkipLast 1)
Reject {Assert-ReleaseSoak $report $invalid $memory} 'final loaded match must be unloaded and measured'
$invalid=Clone $soak;$invalid.soakCoverage.arenaIds=@($arenaIds | Where-Object {$_ -ne 'euphoria_park'})
Reject {Assert-ReleaseSoak $report $invalid $memory} 'soak must cover every current map'
$invalid=Clone $soak;$invalid.soakCoverage.arenaIds+=@('historical-map')
Reject {Assert-ReleaseSoak $report $invalid $memory} 'historical extra map cannot be presented as current soak coverage'
$invalid=Clone $soak;$invalid.soakCoverage.retries=19
Reject {Assert-ReleaseSoak $report $invalid $memory} 'nineteen Retry is insufficient'
$invalid=Clone $memory;$invalid.samples[1511].handles=117
Reject {Assert-ReleaseSoak $report $soak $invalid} 'post-unload handle growth'
$invalid=Clone $soak;$invalid.resourceSnapshots[3].directBufferBytes=$null
Reject {Assert-ReleaseSoak $report $invalid $memory} 'unavailable native metric'
$invalid=Clone $soak;$invalid.resourceChecks.status='FAIL'
Reject {Assert-ReleaseSoak $report $invalid $memory} 'failed resource comparison'
$invalid=Clone $soak;$invalid.resourceSnapshots[61].bodies=1
Reject {Assert-ReleaseSoak $report $invalid $memory} 'forged PASS cannot hide retained body'
$invalid=Clone $soak;$invalid.resourceSnapshots[61].directBufferBytes=8MB
Reject {Assert-ReleaseSoak $report $invalid $memory} 'forged PASS cannot hide growing native memory'
$invalid=Clone $soak;$invalid.resourceSnapshots[61].persistedRevision=0
Reject {Assert-ReleaseSoak $report $invalid $memory} 'forged PASS cannot hide unflushed progress'
$invalid=Clone $soak;$invalid.resourceSnapshots[61].textures=-1
Reject {Assert-ReleaseSoak $report $invalid $memory} 'missing GPU texture observation'
$candidateReports=Join-Path $work 'candidate-reports'
Reject {& (Join-Path $PSScriptRoot '../test-windows-release.ps1') -ZipPath $zipPath -ReportsDirectory $candidateReports *> (Join-Path $work 'candidate-gate.log')} 'missing release checks cannot approve candidate'
$gate=Read-ReleaseJson (Join-Path $candidateReports ('release-gate-'+$identity.zipSha256+'.json'))
Check ($gate.status -eq 'RELEASE_CANDIDATE' -and $gate.technicalStatus -eq 'FAIL' -and $gate.pendingAcceptance.Count -eq 10 -and $gate.published -eq $false) 'failed checks and absent owner/controller/Windows acceptance remain explicit'
$exportGate=Join-Path $work 'synthetic-export-gate.json'
$exportRecordNames=@('release-build-verification.json','windows-package-Smoke.json','windows-package-Soak.json','windows-package-NormalNew.json','windows-package-NormalMigrated.json','windows-package-NormalContinue.json')+
    @($arenaIds | ForEach-Object {"windows-benchmark-$_.json"})
$exportDiagnostic=Join-Path $work 'synthetic-current-diagnostic.json';Write-ReleaseJson $exportDiagnostic $normal
$exportMemory=Join-Path $work 'synthetic-current-memory.json';Write-ReleaseJson $exportMemory $memory
$gate.technicalStatus='PASS';$gate.technicalFailures=@();$gate.artifacts=@()
foreach($name in $exportRecordNames) {
    $recordPath=Join-Path $work "synthetic-current/$name"
    Write-ReleaseJson $recordPath @{status='SYNTHETIC_EXPORT_CONTRACT_ONLY';diagnostic=(Get-ReleaseArtifact $exportDiagnostic);memory=(Get-ReleaseArtifact $exportMemory)}
    $gate.artifacts+=Get-ReleaseArtifact $recordPath
}
Write-ReleaseJson $exportGate $gate
$history=Join-Path $work 'old-evidence';[IO.Directory]::CreateDirectory($history) | Out-Null
[IO.File]::WriteAllText((Join-Path $history 'old-pass.json'),'{"status":"PASS","version":"historical-only"}')
$exportOutput=Join-Path $work 'synthetic-evidence-export'
& (Join-Path $PSScriptRoot '../export-verification.ps1') -ZipPath $zipPath -GatePath $exportGate -OutputDirectory $exportOutput -HistoryDirectories @($history) *> (Join-Path $work 'export.log')
$exportIndex=Read-ReleaseJson (Join-Path $exportOutput 'evidence-index.json')
Check ($exportIndex.gateStatus -eq 'RELEASE_CANDIDATE' -and $exportIndex.pendingAcceptance.Count -eq 10) 'export does not grant pending acceptance'
Check (@($exportIndex.current | Where-Object {$_.originalPath -eq $exportDiagnostic}).Count -eq 1) 'export resolves current diagnostic.path and deduplicates references'
Check ($exportIndex.history.Count -eq 1 -and $exportIndex.history[0].path.StartsWith('history/')) 'history stays separate from current proof'
Check ((Test-Path -LiteralPath (Join-Path $exportOutput 'tools/release-evidence.ps1')) -and (Test-Path -LiteralPath (Join-Path $exportOutput 'current/package/reports/package-verification.json'))) 'shared helpers and exact ZIP package report exported'
Check ((Get-ReleaseSha256 (Join-Path $exportOutput 'current/package/VERIFICATION_STATUS.txt')) -eq (Get-ReleaseSha256 (Join-Path $image 'VERIFICATION_STATUS.txt'))) 'export preserves exact packaged candidate status separately from later gate'
[IO.File]::AppendAllText($exportDiagnostic,'tampered')
Reject {& (Join-Path $PSScriptRoot '../export-verification.ps1') -ZipPath $zipPath -GatePath $exportGate -OutputDirectory (Join-Path $work 'rejected-export')} 'changed nested diagnostic cannot be exported as current'
$preserved=Join-Path $work 'old-native-evidence.txt';[IO.File]::WriteAllText($preserved,'preserved fixture')
$planPath=& (Join-Path $PSScriptRoot '../prepare-windows-release.ps1') -PlanOnly
$plan=Read-ReleaseJson $planPath
Check ($plan.status -eq 'PLANNED' -and $plan.buildRoot.StartsWith((Join-Path $root 'build/release-output/'),[StringComparison]::OrdinalIgnoreCase)) 'release clean targets a dedicated new build root'
Check ($plan.command -contains 'clean' -and $plan.command -contains '-I' -and $plan.packageCommand -contains 'packageWindows' -and $plan.packageCommand -contains $plan.buildInit.path) 'clean and package share the same Gradle init'
Check ((Test-Path -LiteralPath $preserved) -and !(Test-Path -LiteralPath $plan.buildRoot)) 'plan-only creates no build and preserves older evidence'
$review=[pscustomobject]@{status='ACCEPTED';reviewerType='agent';reviewedBy='SYNTHETIC FIXTURE';summary='Contract fixture only, no real acceptance';
    reviewedAtUtc=[DateTime]::UtcNow.ToString('o');artifacts=@((Get-ReleaseArtifact $preserved));
    observations=[pscustomobject]@{mode='NormalNew';newProfile=$true;campaignStarted=$true;saveAndExit=$true}}
Assert-ReleaseReview 'normalNewProfile' $review $identity;$script:checks++
$invalid=Clone $review;$invalid.observations.campaignStarted=$false
Reject {Assert-ReleaseReview 'normalNewProfile' $invalid $identity} 'agent review still requires actual scenario observations'
$invalid=Clone $review;$invalid.observations.saveAndExit='true'
Reject {Assert-ReleaseReview 'normalNewProfile' $invalid $identity} 'string is not a confirmed boolean observation'
$review.observations=[pscustomobject]@{handling=$true;ui=$true;visuals=$true;sound=$true}
Reject {Assert-ReleaseReview 'ownerFeel' $review $identity} 'agent cannot substitute owner feel approval'
$review.reviewerType='human';Assert-ReleaseReview 'ownerFeel' $review $identity;$script:checks++
$review.reviewerType='agent';$review.observations=[pscustomobject]@{documents=@('installation','controls','release-notes','known-limitations','hardware','resource-register','licenses','acceptance-report')}
Assert-ReleaseReview 'releaseDocumentation' $review $identity;$script:checks++
$review.observations=[pscustomobject]@{resolutions=@('640x480','1280x720','1920x1080','2560x1440','3440x1440','3840x2160');windowModes=@('windowed','fullscreen');checks=@('resize','ui-scale','long-strings','notifications','empty-ammo','all-abilities','focus','mouse','keyboard');openBlockingDefects=0}
Assert-ReleaseReview 'graphicalUxMatrix' $review $identity;$script:checks++
$invalid=Clone $review;$invalid.observations.openBlockingDefects=1
Reject {Assert-ReleaseReview 'graphicalUxMatrix' $invalid $identity} 'factual review cannot hide a blocking UX defect'
$review.observations=[pscustomobject]@{deviceModel='fixture controller';connection='USB';physicalDeviceObserved=$false;checks=@('menus','focus','remapping','combat','disconnect','reconnect')}
Reject {Assert-ReleaseReview 'physicalController' $review $identity} 'virtual input does not prove physical hardware'
$review.observations=[pscustomobject]@{machineDescription='fixture second host';osVersion='Windows fixture';separateInstallation=$false;cleanInstallation=$true;bundledJavaVerified=$true;audioVerified=$true;normalExit=$true}
Reject {Assert-ReleaseReview 'otherWindows' $review $identity} 'same Windows installation does not prove a separate host'
$review.observations=[pscustomobject]@{materialsVerified=$true;sourceReplacementInstructionsReviewed=$true;assetNoticesPresent=$true;distributionApproval='NOT_GRANTED'}
Assert-ReleaseReview 'distributionLicenses' $review $identity;$script:checks++
$invalid=Clone $review;$invalid.observations.distributionApproval='GRANTED'
Reject {Assert-ReleaseReview 'distributionLicenses' $invalid $identity} 'integrity evidence grants no distribution approval'
$review.observations=[pscustomobject]@{arenas=@(foreach($arena in $arenaIds) {
    $routeChecks=if($arena -eq 'dead-air-yard'){@('lower','upper-combat','pickup','descent')}else{
        @('district-connections','through-interiors','elevation-transitions','destructible-shortcuts','pickup','boss-routes','full-map','all-three-chassis')
    }
    [pscustomobject]@{id=$arena;checks=$routeChecks}
})}
Assert-ReleaseReview 'arenaRoutes' $review $identity;$script:checks++
$invalid=Clone $review;$invalid.observations.arenas[1].checks=@('lower','upper-combat','pickup','descent','launch','boss-lower','boss-upper')
Reject {Assert-ReleaseReview 'arenaRoutes' $invalid $identity} 'old arena route checklist does not prove large-map districts and interiors'
$invalid=Clone $review;$invalid.observations.arenas=@($invalid.observations.arenas | Where-Object {$_.id -ne 'neon_zero'})
Reject {Assert-ReleaseReview 'arenaRoutes' $invalid $identity} 'all four current maps need route observations'
# UI collection fixtures contain actual generated PNG files, but never launch the game.
Add-Type -AssemblyName System.Drawing
$uiDirectory=Join-Path $work 'ui-collection';$uiCaptureDirectory=Join-Path $uiDirectory 'captures'
[IO.Directory]::CreateDirectory($uiCaptureDirectory) | Out-Null
$uiTemplate=Join-Path $uiDirectory 'synthetic.png'
$bitmap=[Drawing.Bitmap]::new(640,480)
try{$bitmap.Save($uiTemplate,[Drawing.Imaging.ImageFormat]::Png)}finally{$bitmap.Dispose()}
$uiRequest=Get-ReleaseUiReviewRequest '640x480' 1 $true
Check ($uiRequest.windowMode -eq 'windowed' -and $uiRequest.width -eq 640) 'UI request uses bounded Main resolution and explicit window mode'
Check ((Get-ReleaseUiReviewRequest '1080p' 1 $false).fullscreen -eq $true) 'UI alias uses Main fullscreen semantics'
Reject {Get-ReleaseUiReviewRequest '1600x900' 1 $false} 'unsupported UI resolution'
Reject {Get-ReleaseUiReviewRequest '640x480' ([double]::NaN) $false} 'non-finite UI scale'
$uiRequiredCases=@(Get-ReleaseUiReviewCaseIds)
$uiActualCases=@('actual-launch-menu';foreach($profile in @('rivet','grinder','spark')) {
    foreach($action in @('start','repeat-accept','retry','leave')){"actual-$action-$profile"}
};'actual-campaign-start';'actual-campaign-pause';'actual-campaign-quit-confirm';'actual-campaign-quit-cancel';
    'actual-map-open';'actual-map-zoom-pan';'actual-map-height';
    'actual-map-fit';'actual-map-return';'actual-campaign-resume';'actual-campaign-menu';'actual-campaign-continue';'actual-campaign-finished-review')
Check (@($uiActualCases | Where-Object {$uiRequiredCases -cnotcontains $_}).Count -eq 0) 'UI evidence requires the real launch, repeated accept, retry, leave, map interaction and checkpoint continuation cases'
Check ($uiRequiredCases -ccontains 'pause-quit-confirm' -and $uiRequiredCases -ccontains 'pause-quit-cancel') 'UI evidence requires confirmation and cancellation of the new pause exit action'
$uiCaptureCount=@($uiRequiredCases | Where-Object {$_ -cne 'hardware-controller'}).Count
$uiCases=@(foreach($id in $uiRequiredCases) {
    $hardware=$id -eq 'hardware-controller';$relative=if($hardware){''}else{"captures/$id.png"}
    if(!$hardware){Copy-Item -LiteralPath $uiTemplate -Destination (Join-Path $uiDirectory $relative)}
    [pscustomobject]@{caseId=$id;status=$(if($hardware){'PENDING'}else{'CAPTURED'});reason=$(if($hardware){'Physical controller not operated'}else{'Visual review pending'});
        captureFile=$relative;settledDrawFrames=$(if($hardware){0}else{2});
        observed=[pscustomobject]@{width=640;height=480;scale=1;visible=$true;state=[pscustomobject]@{fullscreen=$false;storedWins=0;storedMatches=0}}}
})
$uiManifest=[pscustomobject]@{schemaVersion=1;mode='ui-review';status='CAPTURES_COMPLETE_HUMAN_REVIEW_PENDING';ownerAcceptance='NOT_GRANTED';releaseEligible=$false;
    requestedFramebuffer=[pscustomobject]@{width=640;height=480;uiScale=1};plannedCases=$uiRequiredCases.Count;cases=$uiCases;remainingCaseIds=@()}
$uiManifestPath=Join-Path $uiDirectory 'ui-review-manifest.json';Write-ReleaseJson $uiManifestPath $uiManifest
$uiDiagnostic=[pscustomobject]@{schemaVersion=2;sourceSha256=$source;version='0.0.1';mode='ui-review';pid=123;javaHome=(Join-Path $image 'runtime');
    windowVisible=$true;audioEnabled=$true;errors=@();autoIconify=$true;undrawableSeconds=0;
    status='CAPTURES_COMPLETE_HUMAN_REVIEW_PENDING';uiReviewCaptureStatus='CAPTURES_COMPLETE_HUMAN_REVIEW_PENDING';uiReviewManifest='ui-review-manifest.json';
    releaseEligible=$false;hardwareController='PENDING_MANUAL';feelApproval='PENDING_MANUAL';width=640;height=480}
Assert-ReleaseDiagnostic $uiDiagnostic $identity $image 'ui-review';$script:checks++
$uiEvidence=Get-ReleaseUiReviewEvidence $uiManifestPath $uiDiagnostic $uiRequest
Check ($uiEvidence.captures.Count -eq $uiCaptureCount -and $uiEvidence.status -eq 'CAPTURE_COLLECTION_COMPLETE' -and !$uiEvidence.releaseEligible) 'UI collection hashes every required physical PNG without release approval'
$invalid=Clone $uiDiagnostic;$invalid.audioEnabled=$false
Reject {Assert-ReleaseDiagnostic $invalid $identity $image 'ui-review'} 'UI capture requires actual audio'
$invalid=Clone $uiDiagnostic;$invalid.mode='graphics-smoke'
Reject {Assert-ReleaseDiagnostic $invalid $identity $image 'ui-review'} 'UI startup cannot be another diagnostic mode'
$invalid=Clone $uiDiagnostic;$invalid.width=1280
Reject {Assert-ReleaseUiReview $uiEvidence $invalid $uiRequest} 'UI diagnostic wrong dimensions'
$invalid=Clone $uiEvidence;$invalid.captures[0].sha256='d'*64
Reject {Assert-ReleaseUiReview $invalid $uiDiagnostic $uiRequest} 'mutated PNG SHA binding'
$invalid=Clone $uiEvidence;$invalid.manifest.sha256='d'*64
Reject {Assert-ReleaseUiReview $invalid $uiDiagnostic $uiRequest} 'mutated manifest SHA binding'
$firstPng=$uiEvidence.captures[0].path
Move-Item -LiteralPath $firstPng -Destination ($firstPng+'.unavailable')
try{Reject {Assert-ReleaseUiReview $uiEvidence $uiDiagnostic $uiRequest} 'missing physical PNG file'}
finally{Move-Item -LiteralPath ($firstPng+'.unavailable') -Destination $firstPng}
$pngBytes=[IO.File]::ReadAllBytes($firstPng);$pngBytes[19]=129;[IO.File]::WriteAllBytes($firstPng,$pngBytes)
try {
    $invalid=Clone $uiEvidence;$invalid.captures[0].sha256=Get-ReleaseSha256 $firstPng
    Reject {Assert-ReleaseUiReview $invalid $uiDiagnostic $uiRequest} 'PNG wrong dimensions even with a fresh matching hash'
}finally{Copy-Item -LiteralPath $uiTemplate -Destination $firstPng -Force}
$invalid=Clone $uiEvidence;$invalid.hardwareController='PASS'
Reject {Assert-ReleaseUiReview $invalid $uiDiagnostic $uiRequest} 'collection cannot claim hardware PASS'
$invalid=Clone $uiEvidence;$invalid.releaseEligible=$true
Reject {Assert-ReleaseUiReview $invalid $uiDiagnostic $uiRequest} 'UI captures cannot become release eligible'
foreach($mutation in @('manifest-dimensions','observed-dimensions','scale','window-mode','visibility','stored-wins','stored-matches','hardware-pass','duplicate-case','short-case','missing-case','missing-map-case','string-visibility','string-release-eligible','string-frames')) {
    $changed=Clone $uiManifest
    switch($mutation) {
        'manifest-dimensions' {$changed.requestedFramebuffer.height=481}
        'observed-dimensions' {$changed.cases[0].observed.width=641}
        'scale' {$changed.cases[0].observed.scale=.8}
        'window-mode' {$changed.cases[0].observed.state.fullscreen=$true}
        'visibility' {$changed.cases[0].observed.visible=$false}
        'stored-wins' {$changed.cases[0].observed.state.storedWins=1}
        'stored-matches' {$changed.cases[0].observed.state.storedMatches=1}
        'hardware-pass' {($changed.cases | Where-Object {$_.caseId -ceq 'hardware-controller'}).status='PASS'}
        'duplicate-case' {$changed.cases[1].caseId=$changed.cases[0].caseId}
        'short-case' {$changed.cases[0].settledDrawFrames=1}
        'missing-case' {$changed.cases=@($changed.cases | Where-Object {$_.caseId -cne 'actual-campaign-continue'})}
        'missing-map-case' {$changed.cases=@($changed.cases | Where-Object {$_.caseId -cne 'actual-map-zoom-pan'})}
        'string-visibility' {$changed.cases[0].observed.visible='true'}
        'string-release-eligible' {$changed.releaseEligible='false'}
        'string-frames' {$changed.cases[0].settledDrawFrames='2'}
    }
    Write-ReleaseJson $uiManifestPath $changed
    Reject {Get-ReleaseUiReviewEvidence $uiManifestPath $uiDiagnostic $uiRequest} "invalid UI manifest: $mutation"
}
Write-ReleaseJson $uiManifestPath $uiManifest
$uiEvidence=Get-ReleaseUiReviewEvidence $uiManifestPath $uiDiagnostic $uiRequest;$script:checks++
foreach($scriptPath in @('tools/release-evidence.ps1','tools/prepare-windows-release.ps1','tools/package-windows.ps1','tools/test-windows-package.ps1','tools/test-windows-benchmark.ps1','tools/test-windows-release.ps1','tools/test-local-world.ps1','tools/fixtures/test-release-evidence.ps1')) {
    $tokens=$null;$parseErrors=$null
    $null=[Management.Automation.Language.Parser]::ParseFile((Join-Path $root $scriptPath),[ref]$tokens,[ref]$parseErrors)
    Check ($parseErrors.Count -eq 0) "PowerShell parser: $scriptPath"
}

Write-Output "Synthetic release evidence fixtures PASS: $script:checks assertions. No game or release acceptance was exercised. $work"

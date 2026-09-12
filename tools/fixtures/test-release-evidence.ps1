# Synthetic contract tests only. No application/GUI is launched and no release is approved.
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../release-evidence.ps1')
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$work=Join-Path $root ('build/release-tool-fixtures/'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($work) | Out-Null
$script:checks=0
function Check([bool]$Condition,[string]$Name) {if(!$Condition){throw "Fixture failed: $Name"};$script:checks++}
function Reject([scriptblock]$Action,[string]$Name) {$rejected=$false;try {& $Action} catch {$rejected=$true};Check $rejected $Name}
function Clone($Value){return $Value | ConvertTo-Json -Depth 30 | ConvertFrom-Json}
$source='a'*64;$inputs='b'*64
$jarRoot=Join-Path $work 'jar';[IO.Directory]::CreateDirectory($jarRoot) | Out-Null
[IO.File]::WriteAllText((Join-Path $jarRoot 'build-info.properties'),"version=0.0.1`ncommit=synthetic-fixture`nsourceSha256=$source`n",[Text.UTF8Encoding]::new($false))
$image=Join-Path $work 'WreckRiff';[IO.Directory]::CreateDirectory((Join-Path $image 'app')) | Out-Null
$jarPath=Join-Path $image 'app/wreck-riff-0.0.1.jar'
[IO.Compression.ZipFile]::CreateFromDirectory($jarRoot,$jarPath)
$jarHash=Get-ReleaseSha256 $jarPath
Write-ReleaseJson (Join-Path $image 'reports/package-verification.json') @{schemaVersion=2;status='PACKAGE_STRUCTURE_VERIFIED';version='0.0.1';sourceSha256=$source;mainJarSha256=$jarHash;verificationInputsSha256=$inputs;packagedAtUtc=[DateTime]::UtcNow.ToString('o')}
$zipPath=Join-Path $work 'synthetic-not-a-release.zip'
New-ReleaseZip $image $zipPath
$identity=Get-ReleasePackageIdentity $zipPath
Check ($identity.mainJarSha256 -eq $jarHash -and $identity.sourceSha256 -eq $source) 'nested JAR identity'
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
$samples=@(0..1801 | ForEach-Object {[pscustomobject]@{seconds=$_;observedAtUtc=$at.AddSeconds($_).ToString('o');workingSetBytes=1000000;handles=100}})
$memory=[pscustomobject]@{pid=123;processExited=$true;processStartTimeUtc=$processAt;samples=$samples;peakWorkingSetBytes=1000000}
$report=[pscustomobject]@{status='PASS';gamePid=123;processStartTimeUtc=$processAt}
$diagnostic=[pscustomobject]@{status='BENCHMARK_MEASURED';mode='benchmark';arenaId='construction_17';pid=123;releaseEligible=$true;
    width=1920;height=1080;msaaSamples=4;vsync=$false;audioEnabled=$true;windowVisible=$true;autoIconify=$false;detailedProfiling=$false;invalidBenchmarkWindowObserved=$false;
    requestedSeconds=600;warmupActiveSeconds=30;measuredActiveSeconds=600;undrawableSeconds=0;
    activeCombatFrames=[pscustomobject]@{sampleSeconds=600;frames=36000;p95FrameMs=16;p99FrameMs=20;maxFrameMs=80;framesOver100ms=0};
    phaseMetrics=[pscustomobject]@{droppedSimulationSeconds=0};measuredCoverage=[pscustomobject]@{arenaCombatSeconds=300;bossCombatSeconds=300;maximumEffects=1;maximumLaunchingVehicles=1}}
Assert-ReleaseBenchmark $report $diagnostic $memory 'construction_17';$script:checks++
$invalid=Clone $diagnostic;$invalid.measuredActiveSeconds=599
Reject {Assert-ReleaseBenchmark $report $invalid $memory 'construction_17'} 'short measured run'
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
$arenaIds=@('dead-air-yard','construction_17','neon_zero','euphoria_park','ash_necropolis','doomsday_arena')
$loadedModes=@('dead-air-yard/LEGACY')+@($arenaIds | Where-Object {$_ -ne 'dead-air-yard'} | ForEach-Object {"$_/ARENA";"$_/BOSS_DUEL"})
$snapshots=@(0..14 | ForEach-Object {
    $index=$_;$seconds=10+$index*100;$pair=$loadedModes[[Math]::Min($index,10)].Split('/')
    foreach($stage in @('LOAD','UNLOAD')) {
        [pscustomobject]@{stage=$stage;elapsedSeconds=$seconds;observedAtEpochMillis=([DateTimeOffset]$at.AddSeconds($seconds)).ToUnixTimeMilliseconds();
            arenaId=$pair[0];mode=$pair[1];phase='ARENA_COMBAT';profileId='rivet';topology='fixture';
            bodies=$(if($stage -eq 'LOAD'){5}else{0});listeners=0;tickListeners=0;projectiles=0;trackers=12;directBufferBytes=1024;textures=4;voices=0;
            saveQueueDepth=0;saveWorkerScheduled=$false;revision=1;persistedRevision=1;collectionRequested=($stage -eq 'UNLOAD')}
        $seconds++
    }
})
$soak=[pscustomobject]@{pid=123;status='SOAK_MEASURED';mode='soak';requestedSeconds=1800;
    soakCoverage=[pscustomobject]@{measuredSeconds=1800;mapChanges=10;retries=20;arenaIds=$arenaIds;loadedModes=$loadedModes;resourcesWarmed=$true;coverageComplete=$true};
    resourceChecks=[pscustomobject]@{status='PASS';errors=@();unloadComparisons=4;minimumUnloadComparisons=3};resourceSnapshots=$snapshots}
Assert-ReleaseSoak $report $soak $memory;$script:checks++
$invalid=Clone $soak;$invalid.soakCoverage.retries=19
Reject {Assert-ReleaseSoak $report $invalid $memory} 'nineteen Retry is insufficient'
$invalid=Clone $memory;$invalid.samples[1411].handles=117
Reject {Assert-ReleaseSoak $report $soak $invalid} 'post-unload handle growth'
$invalid=Clone $soak;$invalid.resourceSnapshots[3].directBufferBytes=$null
Reject {Assert-ReleaseSoak $report $invalid $memory} 'unavailable native metric'
$invalid=Clone $soak;$invalid.resourceChecks.status='FAIL'
Reject {Assert-ReleaseSoak $report $invalid $memory} 'failed resource comparison'
$invalid=Clone $soak;$invalid.resourceSnapshots[29].bodies=1
Reject {Assert-ReleaseSoak $report $invalid $memory} 'forged PASS cannot hide retained body'
$invalid=Clone $soak;$invalid.resourceSnapshots[29].directBufferBytes=8MB
Reject {Assert-ReleaseSoak $report $invalid $memory} 'forged PASS cannot hide growing native memory'
$invalid=Clone $soak;$invalid.resourceSnapshots[29].persistedRevision=0
Reject {Assert-ReleaseSoak $report $invalid $memory} 'forged PASS cannot hide unflushed progress'
$invalid=Clone $soak;$invalid.resourceSnapshots[29].textures=-1
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
[IO.File]::AppendAllText($exportDiagnostic,'tampered')
Reject {& (Join-Path $PSScriptRoot '../export-verification.ps1') -ZipPath $zipPath -GatePath $exportGate -OutputDirectory (Join-Path $work 'rejected-export')} 'changed nested diagnostic cannot be exported as current'
$preserved=Join-Path $work 'old-native-evidence.txt';[IO.File]::WriteAllText($preserved,'preserved fixture')
$planPath=& (Join-Path $PSScriptRoot '../prepare-windows-release.ps1') -PlanOnly
$plan=Read-ReleaseJson $planPath
Check ($plan.status -eq 'PLANNED' -and $plan.buildRoot.StartsWith((Join-Path $root 'build/release-output/'),[StringComparison]::OrdinalIgnoreCase)) 'release clean targets a dedicated new build root'
Check ($plan.command -contains 'clean' -and $plan.command -contains '-I' -and $plan.packageCommand -contains 'packageWindows' -and $plan.packageCommand -contains $plan.buildInit.path) 'clean and package share the same Gradle init'
Check ((Test-Path -LiteralPath $preserved) -and !(Test-Path -LiteralPath $plan.buildRoot)) 'plan-only creates no build and preserves older evidence'
Write-Output "Synthetic release evidence fixtures PASS: $script:checks assertions. No game or release acceptance was exercised. $work"

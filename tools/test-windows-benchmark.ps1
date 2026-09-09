param(
    [ValidateRange(60,3600)][int]$Seconds=600,
    [ValidateSet('dead-air-yard','construction_17','neon_zero','euphoria_park','ash_necropolis','doomsday_arena')]
    [string]$Arena='construction_17'
)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$image=Join-Path $projectRoot 'build/distributions/WreckRiff'
$runRoot=Join-Path $projectRoot ('build/benchmark-runs/'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($runRoot) | Out-Null
$started=[DateTime]::UtcNow
$launcher=Start-Process -FilePath (Join-Path $image 'WreckRiff.exe') -WorkingDirectory $image -WindowStyle Hidden -PassThru `
    -ArgumentList @('--dev','--seed=42','--ai-player',"--arena=$Arena",'--resolution=1080p',"--benchmark-seconds=$Seconds") `
    -RedirectStandardOutput (Join-Path $runRoot 'stdout.log') -RedirectStandardError (Join-Path $runRoot 'stderr.log')
$null=$launcher.Handle
$reportPath=$null
while (([DateTime]::UtcNow-$started).TotalSeconds -lt 30) {
    $line=Get-Content -LiteralPath (Join-Path $runRoot 'stdout.log') | Where-Object { $_.StartsWith('DIAGNOSTIC_REPORT: ') } | Select-Object -First 1
    if($line) {$reportPath=[IO.Path]::GetFullPath($line.Substring(19).Trim());break}
    Start-Sleep -Milliseconds 250
}
if(!$reportPath) {throw "No diagnostic startup report. See $runRoot"}
$diagnostics=[IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'WreckRiff/diagnostics'))
if(!$reportPath.StartsWith($diagnostics+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {throw 'Unexpected diagnostic path'}
if((Get-Item -LiteralPath $reportPath).LastWriteTimeUtc -lt $started) {throw 'Diagnostic evidence predates launch'}
$initial=Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
if($initial.schemaVersion -lt 2 -or $initial.mode -ne 'benchmark' -or $initial.arenaId -ne $Arena -or $initial.requestedSeconds -ne $Seconds -or $initial.pid -le 0) {throw 'Diagnostic identity mismatch'}
Write-Output "Benchmark game PID: $($initial.pid); report: $reportPath"
$timeout=30+$Seconds+[Math]::Max(120,[Math]::Floor($Seconds/2))+30
& (Join-Path $PSScriptRoot 'watch-process-memory.ps1') -ProcessId $initial.pid -Output (Join-Path $runRoot 'memory.json') -MaximumSeconds $timeout
if(!( $launcher.WaitForExit(15000))) {throw "Benchmark did not stop; see $runRoot"}
$launcherExitCode=$launcher.ExitCode
$evidence=Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
$memory=Get-Content -LiteralPath (Join-Path $runRoot 'memory.json') -Raw | ConvertFrom-Json
Copy-Item -LiteralPath $reportPath -Destination (Join-Path $runRoot 'diagnostic-result.json')
$frames=$evidence.activeCombatFrames
$fullTenMinutes=$Seconds -ge 600 -and $evidence.warmupActiveSeconds -ge 30 -and $evidence.measuredActiveSeconds -ge 600 -and $frames.sampleSeconds -ge 600
$timing=$frames.frames -gt 0 -and $null -ne $frames.p95FrameMs -and $frames.p95FrameMs -le 16.7 `
    -and $null -ne $frames.p99FrameMs -and $frames.p99FrameMs -le 25 -and $null -ne $frames.framesOver100ms -and $frames.framesOver100ms -eq 0
$noDropped=$null -ne $evidence.phaseMetrics.droppedSimulationSeconds -and $evidence.phaseMetrics.droppedSimulationSeconds -eq 0
$memoryMet=$memory.pid -eq $initial.pid -and $memory.samples.Count -gt 0 -and $memory.peakWorkingSetBytes -gt 0 `
    -and $memory.peakWorkingSetBytes -le 1.5GB -and $memory.within1_5GiB -and $memory.processExited
$coverage=$evidence.measuredCoverage
$coverageMet=$Arena -eq 'dead-air-yard' -or ($coverage.arenaCombatSeconds -gt 0 -and $coverage.bossCombatSeconds -gt 0 `
    -and $coverage.maximumEffects -gt 0 -and $coverage.maximumLaunchingVehicles -gt 0)
$passed=$launcherExitCode -eq 0 -and $evidence.status -eq 'BENCHMARK_MEASURED' -and $evidence.releaseEligible -and $fullTenMinutes `
    -and $evidence.width -eq 1920 -and $evidence.height -eq 1080 `
    -and !$evidence.vsync -and $evidence.audioEnabled -and $evidence.windowVisible -and !$evidence.autoIconify `
    -and $evidence.msaaSamples -eq 4 -and !$evidence.detailedProfiling -and !$evidence.invalidBenchmarkWindowObserved `
    -and $evidence.renderTargetMet -and $timing -and $noDropped -and $memoryMet -and $coverageMet
$result=[ordered]@{
    schemaVersion=2;status=$(if($passed){'PASS'}elseif($Seconds -lt 600 -and $launcherExitCode -eq 0){'DIAGNOSTIC_COMPLETE'}else{'FAIL'});requestedSeconds=$Seconds
    arenaId=$Arena;releaseEligible=$evidence.releaseEligible
    sourceSha256=$evidence.sourceSha256;diagnosticPath=$reportPath;runRoot=$runRoot
    launcherExitCode=$launcherExitCode;gamePid=$initial.pid
    activeCombatFrames=$frames;phaseMetrics=$evidence.phaseMetrics;peakWorkingSetBytes=$memory.peakWorkingSetBytes
    within1_5GiB=$memoryMet;fullTenMinutes=$fullTenMinutes;timingTargetMet=$timing;noDroppedSimulationTime=$noDropped
    warmupActiveSeconds=$evidence.warmupActiveSeconds;measuredActiveSeconds=$evidence.measuredActiveSeconds
    measuredCoverage=$coverage;requiredCoverageMet=$coverageMet
}
$reports=Join-Path $projectRoot 'build/reports'
[IO.Directory]::CreateDirectory($reports) | Out-Null
$json=$result|ConvertTo-Json -Depth 12
[IO.File]::WriteAllText((Join-Path $reports 'windows-benchmark.json'),$json,[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $reports "windows-benchmark-$Arena.json"),$json,[Text.UTF8Encoding]::new($false))
if($Seconds -lt 600 -and $launcherExitCode -eq 0 -and $evidence.status -eq 'DIAGNOSTIC_COMPLETE') {
    Write-Output "Short diagnostic complete; not release eligible: $runRoot"
    return
}
if(!$passed) {throw "Benchmark failed; see $runRoot"}
Write-Output "Packaged 1080p benchmark PASS ($Arena, $($evidence.measuredActiveSeconds) measured active seconds): $runRoot"

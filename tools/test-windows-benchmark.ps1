param([ValidateRange(60,600)][int]$Seconds=600)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$image=Join-Path $projectRoot 'build/distributions/WreckRiff'
$runRoot=Join-Path $projectRoot ('build/benchmark-runs/'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($runRoot) | Out-Null
$started=[DateTime]::UtcNow
$launcher=Start-Process -FilePath (Join-Path $image 'WreckRiff.exe') -WorkingDirectory $image -WindowStyle Hidden -PassThru `
    -ArgumentList @('--dev','--seed=42','--ai-player',"--benchmark-seconds=$Seconds") `
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
if($initial.mode -ne 'benchmark' -or $initial.requestedSeconds -ne $Seconds -or $initial.pid -le 0) {throw 'Diagnostic identity mismatch'}
Write-Output "Benchmark game PID: $($initial.pid); report: $reportPath"
& (Join-Path $PSScriptRoot 'watch-process-memory.ps1') -ProcessId $initial.pid -Output (Join-Path $runRoot 'memory.json') -MaximumSeconds ($Seconds+75)
if(!( $launcher.WaitForExit(15000))) {throw "Benchmark did not stop; see $runRoot"}
$launcherExitCode=$launcher.ExitCode
$evidence=Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
$memory=Get-Content -LiteralPath (Join-Path $runRoot 'memory.json') -Raw | ConvertFrom-Json
Copy-Item -LiteralPath $reportPath -Destination (Join-Path $runRoot 'diagnostic-result.json')
$passed=$launcherExitCode -eq 0 -and $evidence.status -eq 'PASS' -and $evidence.width -eq 1920 -and $evidence.height -eq 1080 `
    -and !$evidence.vsync -and $evidence.audioEnabled -and $evidence.windowVisible -and !$evidence.autoIconify `
    -and $evidence.msaaSamples -ge 4 -and $evidence.warmupSeconds -eq 30 -and $evidence.renderTargetMet `
    -and $memory.within1_5GiB -and $memory.processExited
$result=[ordered]@{
    schemaVersion=1;status=$(if($passed){'PASS'}else{'FAIL'});requestedSeconds=$Seconds
    sourceSha256=$evidence.sourceSha256;diagnosticPath=$reportPath;runRoot=$runRoot
    launcherExitCode=$launcherExitCode;gamePid=$initial.pid
    activeCombatFrames=$evidence.activeCombatFrames;peakWorkingSetBytes=$memory.peakWorkingSetBytes
    within1_5GiB=$memory.within1_5GiB;fullTenMinutes=($Seconds -eq 600 -and $evidence.elapsedSeconds -ge 630)
}
[IO.File]::WriteAllText((Join-Path $projectRoot 'build/reports/windows-benchmark.json'),($result|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
if(!$passed) {throw "Benchmark failed; see $runRoot"}
Write-Output "Packaged 1080p benchmark PASS: $runRoot"

param(
    [Parameter(Mandatory=$true)][string]$Image,
    [ValidateSet('benchmark','soak')][string]$Mode='benchmark',
    [ValidateSet('construction_17','neon_zero','euphoria_park','dead-air-yard')][string]$Arena='construction_17',
    [ValidateRange(60,3600)][int]$Seconds=600,
    [switch]$ShortReview,
    [switch]$Profile
)
$ErrorActionPreference='Stop'
if($ShortReview -and ($Mode -ne 'benchmark' -or $Seconds -gt 90)){throw 'ShortReview requires benchmark mode and 60..90 measured seconds.'}
if($Profile -and !$ShortReview){throw 'Detailed profiling belongs to the short diagnostic, not the full benchmark.'}
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$imagePath=(Resolve-Path -LiteralPath $Image).Path
$library=Join-Path $imagePath 'lib'
$mainJar=Get-ChildItem -LiteralPath $library -Filter 'wreck-riff-*.jar'
if(@($mainJar).Count -ne 1){throw 'Expected exactly one local installDist application JAR.'}
$jarHash=(Get-FileHash -LiteralPath $mainJar.FullName -Algorithm SHA256).Hash
$run=Join-Path $root ('build/local-world-runs/'+$Mode+'-'+$Arena+'-'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($run)|Out-Null
$java='C:\Users\vitalii\.jdks\ms-21.0.11\bin\java.exe'
$stdout=Join-Path $run 'stdout.log';$stderr=Join-Path $run 'stderr.log'
$samples=[Collections.Generic.List[object]]::new();$failures=[Collections.Generic.List[string]]::new()
$started=[DateTime]::UtcNow;$previousLocal=$env:LOCALAPPDATA;$game=$null;$report=$null;$nextProgress=60
try {
    $env:LOCALAPPDATA=Join-Path $run 'user-data'
    $arguments=@('-Xms128m','-Xmx768m','-cp',('"'+$library+'\*"'),'game.wreckriff.Main','--dev','--seed=42','--ai-player',"--arena=$Arena",'--resolution=1080p',"--$Mode-seconds=$Seconds")
    if($Profile){$arguments+='--profile'}
    $game=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $null=$game.Handle;$processStart=$game.StartTime.ToUniversalTime()
    while(!$game.HasExited) {
        $game.Refresh();if($game.HasExited){break};$now=[DateTime]::UtcNow
        if(($now-$started).TotalSeconds -gt $Seconds*1.5+240){throw 'Local graphical verification timed out.'}
        $samples.Add([ordered]@{seconds=($now-$started).TotalSeconds;observedAtUtc=$now.ToString('o');workingSetBytes=$game.WorkingSet64;peakWorkingSetBytes=$game.PeakWorkingSet64;privateBytes=$game.PrivateMemorySize64;handles=$game.HandleCount})
        if(($now-$started).TotalSeconds -ge $nextProgress) {
            Write-Output ('RUN {0}/{1}: {2:N0}s; working set {3:N0} MiB; PID {4}' -f $Mode,$Arena,($now-$started).TotalSeconds,($game.WorkingSet64/1MB),$game.Id)
            $nextProgress+=60
        }
        Start-Sleep -Seconds 1
    }
    $game.WaitForExit();if($game.ExitCode -ne 0){$failures.Add("Game exited with code $($game.ExitCode).")}
    $line=Get-Content -LiteralPath $stdout | Where-Object {$_.StartsWith('DIAGNOSTIC_REPORT: ')} | Select-Object -Last 1
    if(!$line){throw 'No diagnostic report produced.'}
    $reportPath=[IO.Path]::GetFullPath($line.Substring(19).Trim())
    $allowed=[IO.Path]::GetFullPath($env:LOCALAPPDATA)+[IO.Path]::DirectorySeparatorChar
    if(!$reportPath.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)){throw 'Diagnostic report is outside this isolated run.'}
    $report=Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
    if($report.pid -ne $game.Id -or $report.mode -ne $Mode){throw 'Diagnostic identity differs from the launched process.'}
    Copy-Item -LiteralPath $reportPath -Destination (Join-Path $run 'diagnostic-result.json')
    if($report.errors.Count -gt 0){$failures.Add('Game reported errors: '+($report.errors -join ', '))}
    if($report.width -ne 1920 -or $report.height -ne 1080 -or $report.msaaSamples -ne 4 -or $report.vsync -or !$report.audioEnabled -or !$report.windowVisible -or $report.undrawableSeconds -ne 0){$failures.Add('Required real-window 1080p/MSAA4/audio/VSync configuration was not maintained.')}
    if($report.phaseMetrics.droppedSimulationSeconds -ne 0){$failures.Add('Simulation time was dropped.')}
    if($Mode -eq 'benchmark') {
        $frames=$report.activeCombatFrames
        $requiredSeconds=if($ShortReview){$Seconds}else{[Math]::Max(600,$Seconds)}
        $requiredStatus=if($ShortReview){'DIAGNOSTIC_COMPLETE'}else{'BENCHMARK_MEASURED'}
        if($report.status -ne $requiredStatus -or $report.warmupActiveSeconds -lt 30 -or $report.measuredActiveSeconds -lt $requiredSeconds){$failures.Add("Required 30+$requiredSeconds active-second measurement was not completed.")}
        if(($report.detailedProfiling -and !$Profile) -or $report.invalidBenchmarkWindowObserved){$failures.Add('Unexpected profiling or a changed/hidden framebuffer invalidated the measurement.')}
        if($Profile -and !$report.detailedProfiling){$failures.Add('Requested CPU/GPU/JFR profiling was not enabled.')}
        if($frames.frames -le 0 -or $frames.p95FrameMs -gt 16.7 -or $frames.p99FrameMs -gt 25 -or $frames.framesOver100ms -ne 0){$failures.Add('Active frame thresholds exceeded.')}
    } elseif($report.status -ne 'SOAK_MEASURED' -or !$report.soakCoverage.coverageComplete -or $report.resourceChecks.status -ne 'PASS'){$failures.Add('Full soak coverage or retained-resource check failed.')}
    if((Get-FileHash -LiteralPath $mainJar.FullName -Algorithm SHA256).Hash -ne $jarHash){$failures.Add('Application JAR changed during verification.')}
} catch {$failures.Add($_.Exception.Message)}
finally {
    if($game -and !$game.HasExited){$game.Kill();$game.WaitForExit()}
    $env:LOCALAPPDATA=$previousLocal
    $peak=if($samples.Count){($samples | ForEach-Object { $_['peakWorkingSetBytes'] } | Measure-Object -Maximum).Maximum}else{0}
    if($peak -le 0 -or $peak -gt 1.5GB){$failures.Add('External process working set is unavailable or exceeds 1.5 GiB.')}
    $memory=[ordered]@{pid=$(if($game){$game.Id}else{0});processStartTimeUtc=$(if($game){$processStart.ToString('o')}else{''});counter='Windows process PeakWorkingSet64 and WorkingSet64 sampled each second';peakWorkingSetBytes=$peak;samples=$samples.ToArray()}
    [IO.File]::WriteAllText((Join-Path $run 'memory.json'),($memory|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
    $result=[ordered]@{status=$(if($failures.Count){'FAIL'}else{'PASS'});scope='Local installDist build, not a Windows release ZIP or owner acceptance';shortReview=[bool]$ShortReview;longStabilityEvidence=(!$ShortReview -and !$Profile -and $failures.Count -eq 0);detailedProfiling=[bool]$Profile;arenaId=$Arena;mode=$Mode;mainJar=$mainJar.FullName;mainJarSha256=$jarHash;startedAtUtc=$started.ToString('o');completedAtUtc=[DateTime]::UtcNow.ToString('o');failures=$failures.ToArray();diagnostic=$report;memoryPeakBytes=$peak}
    [IO.File]::WriteAllText((Join-Path $run 'verification.json'),($result|ConvertTo-Json -Depth 25),[Text.UTF8Encoding]::new($false))
    Write-Output "$($result.status): $run"
}
if($failures.Count){throw ($failures -join '; ')}

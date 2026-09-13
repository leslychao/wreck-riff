param(
    [Parameter(Mandatory=$true)][string]$Image,
    [ValidateSet('benchmark','soak')][string]$Mode='benchmark',
    [ValidateSet('construction_17','neon_zero','euphoria_park','dead-air-yard')][string]$Arena='construction_17',
    [ValidateRange(60,3600)][int]$Seconds=600,
    [switch]$ShortReview,
    [switch]$Profile
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'release-evidence.ps1')
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
$started=[DateTime]::UtcNow;$previousLocal=$env:LOCALAPPDATA;$game=$null;$gameHandle=[IntPtr]::Zero;$processStart=$null;$report=$null;$nextProgress=60
try {
    $env:LOCALAPPDATA=Join-Path $run 'user-data'
    $arguments=@('-Xms128m','-Xmx768m','-cp',('"'+$library+'\*"'),'game.wreckriff.Main','--dev','--seed=42','--ai-player',"--arena=$Arena",'--resolution=1080p',"--$Mode-seconds=$Seconds")
    if($Profile){$arguments+='--profile'}
    $game=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $gameHandle=$game.Handle;$processStart=$game.StartTime.ToUniversalTime()
    while(!$game.HasExited) {
        $game.Refresh();if($game.HasExited){break};$now=[DateTime]::UtcNow
        if(($now-$started).TotalSeconds -gt $Seconds*1.5+240){throw 'Local graphical verification timed out.'}
        $samples.Add([ordered]@{seconds=($now-$processStart).TotalSeconds;observedAtUtc=$now.ToString('o');workingSetBytes=$game.WorkingSet64;peakWorkingSetBytes=$game.PeakWorkingSet64;privateBytes=$game.PrivateMemorySize64;handles=$game.HandleCount})
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
    if($report.pid -ne $game.Id -or $report.mode -ne $Mode -or $report.arenaId -ne $Arena -or $report.requestedSeconds -ne $Seconds){throw 'Diagnostic identity or requested scenario differs from the launched process.'}
    Copy-Item -LiteralPath $reportPath -Destination (Join-Path $run 'diagnostic-result.json')
    if($report.errors.Count -gt 0){$failures.Add('Game reported errors: '+($report.errors -join ', '))}
    if($ShortReview) {
        Assert-ReleaseCombatEnvironment $report $Profile.IsPresent
        $frames=$report.activeCombatFrames
        if($report.status -ne 'DIAGNOSTIC_COMPLETE'){throw 'Short diagnostic did not complete.'}
        Assert-ReleaseNumber $report.warmupActiveSeconds 30 7200 'active warmup seconds'
        Assert-ReleaseNumber $report.measuredActiveSeconds $Seconds 7200 'measured active seconds'
        Assert-ReleaseNumber $frames.sampleSeconds $Seconds 7200 'active sample seconds'
        if([Math]::Abs($frames.sampleSeconds-$report.measuredActiveSeconds) -gt .001){throw 'Active sample duration differs from the diagnostic clock.'}
        if($report.invalidBenchmarkWindowObserved -isnot [bool] -or $report.invalidBenchmarkWindowObserved){throw 'A changed/hidden framebuffer invalidated the measurement.'}
        Assert-ReleaseNumber $frames.frames 1 ([double]::MaxValue) 'active frame count'
        Assert-ReleaseNumber $frames.p95FrameMs 0 16.7 'active p95'
        Assert-ReleaseNumber $frames.p99FrameMs 0 25 'active p99'
        Assert-ReleaseNumber $frames.maxFrameMs 0 100 'maximum active frame'
        Assert-ReleaseNumber $frames.framesOver100ms 0 0 'frames above 100 ms'
    }
    if((Get-FileHash -LiteralPath $mainJar.FullName -Algorithm SHA256).Hash -ne $jarHash){$failures.Add('Application JAR changed during verification.')}
} catch {$failures.Add($_.Exception.Message)}
finally {
    if($game -and !$game.HasExited){$game.Kill();$game.WaitForExit()}
    $env:LOCALAPPDATA=$previousLocal
    $finalPeak=0;$finalPeakObserved=$false
    if($gameHandle -ne [IntPtr]::Zero -and $game.HasExited) {
        try {$finalPeak=Get-ReleaseProcessPeakWorkingSet $gameHandle;$finalPeakObserved=$true}
        catch {$failures.Add('Final OS memory peak unavailable: '+$_.Exception.Message)}
    }
    $samplePeak=if($samples.Count){($samples | ForEach-Object { $_['peakWorkingSetBytes'] } | Measure-Object -Maximum).Maximum}else{0}
    $peak=[Math]::Max($samplePeak,$finalPeak)
    if($peak -le 0 -or $peak -gt 1.5GB){$failures.Add('External process working set is unavailable or exceeds 1.5 GiB.')}
    $memory=[ordered]@{schemaVersion=3;pid=$(if($game){$game.Id}else{0});processStartTimeUtc=$(if($processStart){$processStart.ToString('o')}else{''});processExited=($null -ne $game -and $game.HasExited);
        counter='Windows lifetime PeakWorkingSetSize with final post-exit handle query; current working set and handles sampled each second';
        peakWorkingSetBytes=$peak;finalPeakWorkingSetBytes=$finalPeak;finalPeakObservedAfterExit=$finalPeakObserved;samples=$samples.ToArray()}
    if($null -ne $report) {
        try {
            $binding=[pscustomobject]@{status='PASS';gamePid=$memory.pid;processStartTimeUtc=$memory.processStartTimeUtc}
            if(!$ShortReview) {
                # A local image is not a release ZIP, but its long-run numeric,
                # environment and lifecycle gates must be equally strict.
                if($Mode -eq 'benchmark'){Assert-ReleaseBenchmark $binding $report $memory $Arena}
                else {Assert-ReleaseSoak $binding $report $memory}
            } else {Assert-ReleaseMemory $binding $report $memory $Seconds}
        } catch {$failures.Add($_.Exception.Message)}
    }
    [IO.File]::WriteAllText((Join-Path $run 'memory.json'),($memory|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
    # The WDDM provider can stall for seconds during startup. Optional VRAM
    # collection must not interrupt the mandatory one-second process sampler.
    $gpuMemory=[ordered]@{status=$(if($Profile){'UNAVAILABLE'}else{'NOT_REQUESTED'});measurement='NOT_MEASURED';
        reason=$(if($Profile){'Synchronous WDDM queries can block the one-second process memory sampler; no WDDM query was issued.'}else{'Detailed profiling was not requested.'});
        scope='VRAM unavailable; GPU timer queries remain separately recorded by the game profiler.';peakDedicatedBytes=$null;samples=@();errors=@()}
    [IO.File]::WriteAllText((Join-Path $run 'gpu-memory.json'),($gpuMemory|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
    $result=[ordered]@{status=$(if($failures.Count){'FAIL'}else{'PASS'});scope='Local installDist build, not a Windows release ZIP or owner acceptance';shortReview=[bool]$ShortReview;longStabilityEvidence=(!$ShortReview -and !$Profile -and $failures.Count -eq 0);detailedProfiling=[bool]$Profile;arenaId=$Arena;mode=$Mode;mainJar=$mainJar.FullName;mainJarSha256=$jarHash;startedAtUtc=$started.ToString('o');completedAtUtc=[DateTime]::UtcNow.ToString('o');failures=$failures.ToArray();diagnostic=$report;memoryPeakBytes=$peak}
    [IO.File]::WriteAllText((Join-Path $run 'verification.json'),($result|ConvertTo-Json -Depth 25),[Text.UTF8Encoding]::new($false))
    Write-Output "$($result.status): $run"
}
if($failures.Count){throw ($failures -join '; ')}

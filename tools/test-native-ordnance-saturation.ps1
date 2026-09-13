param(
    [Parameter(Mandatory=$true)][string]$ImageRoot,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedSourceSha256,
    [string]$ReportDirectory='',
    [string]$ClassesDirectory='build/native-ordnance-saturation/classes',
    [ValidateScript({$_ -eq 0 -or ($_ -ge 60 -and $_ -le 90)})][int]$PerformanceSeconds=0,
    [string]$JdkHome='C:\Users\vitalii\.jdks\ms-21.0.11'
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'release-evidence.ps1')
$workspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if ([string]::IsNullOrWhiteSpace($ReportDirectory)) {
    $runName = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
    $ReportDirectory = Join-Path $workspace ("build/reports/native-ordnance-saturation/" + $runName)
}
$ReportDirectory = [System.IO.Path]::GetFullPath($ReportDirectory)
[System.IO.Directory]::CreateDirectory($ReportDirectory) | Out-Null
$image = (Resolve-Path -LiteralPath $ImageRoot).Path
$classes = if ([System.IO.Path]::IsPathRooted($ClassesDirectory)) { $ClassesDirectory } else { Join-Path $workspace $ClassesDirectory }
$runner = Join-Path $classes 'game/wreckriff/diagnostics/NativeOrdnanceSaturationReview.class'
if (-not (Test-Path -LiteralPath $runner -PathType Leaf)) { throw 'Compile physicsTest classes in an allocated verification slot before launching this runner.' }
if (-not (Test-Path -LiteralPath (Join-Path $image 'lib') -PathType Container)) { throw 'ImageRoot must contain the immutable application lib directory.' }
$java = Join-Path $JdkHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw 'Microsoft JDK 21 is required.' }
$classpath = (Join-Path $image 'lib/*') + ';' + $classes
$reviewArgs=@($ReportDirectory,$ExpectedSourceSha256)
if($PerformanceSeconds -gt 0){$reviewArgs+=([string]$PerformanceSeconds)}
$mainJars=@(Get-ChildItem -LiteralPath (Join-Path $image 'lib') -Filter 'wreck-riff-*.jar')
if($mainJars.Count -ne 1){throw 'Expected one immutable installDist application JAR.'}
$mainJar=$mainJars[0].FullName
$jarHash=(Get-FileHash -LiteralPath $mainJar -Algorithm SHA256).Hash
$stdout=Join-Path $ReportDirectory 'execution.log'
$stderr=Join-Path $ReportDirectory 'stderr.log'
$samples=[Collections.Generic.List[object]]::new()
$process=$null;$processHandle=[IntPtr]::Zero;$processStart=$null;$started=[DateTime]::UtcNow;$peak=0;$nextProgress=60
try {
    # Windows paths cannot contain a double quote; quote each path argument so spaces
    # remain inside its argument. No shell or command-string evaluation is involved.
    $arguments=@('-Xms128m','-Xmx768m','-cp',('"'+$classpath+'"'),'game.wreckriff.diagnostics.NativeOrdnanceSaturationReview')
    $arguments+=@($reviewArgs | ForEach-Object {'"'+$_+'"'})
    $process=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $workspace -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $processHandle=$process.Handle;$processStart=$process.StartTime.ToUniversalTime()
    while(!$process.HasExited) {
        $process.Refresh();if($process.HasExited){break}
        $elapsed=([DateTime]::UtcNow-$started).TotalSeconds
        if($elapsed -gt [Math]::Max(240,$PerformanceSeconds*2+180)){throw 'Native saturation window exceeded its bounded review duration.'}
        $peak=[Math]::Max($peak,$process.PeakWorkingSet64)
        $samples.Add([ordered]@{seconds=$elapsed;workingSetBytes=$process.WorkingSet64;peakWorkingSetBytes=$process.PeakWorkingSet64;privateBytes=$process.PrivateMemorySize64})
        if($elapsed -ge $nextProgress){Write-Output ('SATURATION: {0:N0}s; RSS {1:N0} MiB; PID {2}' -f $elapsed,($process.WorkingSet64/1MB),$process.Id);$nextProgress+=60}
        Start-Sleep -Seconds 1
    }
    $process.WaitForExit()
    if($process.ExitCode -ne 0){throw "Native ordnance saturation exited with code $($process.ExitCode); inspect $stderr"}
} finally {
    if($process -and !$process.HasExited){$process.Kill();$process.WaitForExit()}
    $finalPeak=0;$finalPeakObserved=$false
    if($processHandle -ne [IntPtr]::Zero -and $process.HasExited) {
        $finalPeak=Get-ReleaseProcessPeakWorkingSet $processHandle
        $finalPeakObserved=$true
        $peak=[Math]::Max($peak,$finalPeak)
    }
    $memory=[ordered]@{pid=$(if($process){$process.Id}else{0});processStartTimeUtc=$(if($processStart){$processStart.ToString('o')}else{''});counter='Windows lifetime PeakWorkingSetSize with final post-exit handle query; current working set sampled each second';peakWorkingSetBytes=$peak;finalPeakWorkingSetBytes=$finalPeak;finalPeakObservedAfterExit=$finalPeakObserved;limitBytes=1.5GB;status=$(if($finalPeakObserved -and $peak -gt 0 -and $peak -le 1.5GB){'PASS'}else{'FAIL'});mainJar=$mainJar;mainJarSha256=$jarHash;applicationJarUnchanged=((Get-FileHash -LiteralPath $mainJar -Algorithm SHA256).Hash -eq $jarHash);samples=$samples.ToArray()}
    [IO.File]::WriteAllText((Join-Path $ReportDirectory 'memory.json'),($memory|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
}
if(!$memory.applicationJarUnchanged){throw 'Application JAR changed during native saturation review.'}
if($memory.status -ne 'PASS'){throw 'Native saturation process RSS is unavailable or exceeds 1.5 GiB.'}
$report = Join-Path $ReportDirectory $(if($PerformanceSeconds -gt 0){'performance.json'}else{'review.json'})
if (-not (Test-Path -LiteralPath $report -PathType Leaf)) { throw 'The real-window run did not produce current verification evidence.' }
$evidence = Get-Content -LiteralPath $report -Raw | ConvertFrom-Json
if($PerformanceSeconds -gt 0) {
    if($evidence.status -ne 'COMPLETE' -or $evidence.sourceSha256 -ne $ExpectedSourceSha256 -or -not $evidence.retryCleared){throw 'Active saturation measurement is incomplete or invalid.'}
    if($evidence.frameTimeGate -ne 'PASS'){throw "Active saturation frame-time thresholds failed; inspect $report"}
} elseif ($evidence.status -ne 'PASS' -or $evidence.sourceSha256 -ne $ExpectedSourceSha256 -or -not $evidence.retryCleared) { throw 'Native ordnance saturation evidence did not pass.' }
Write-Output "Native ordnance saturation verified: $report"

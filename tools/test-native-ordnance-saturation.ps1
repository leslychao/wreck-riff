param(
    [Parameter(Mandatory=$true)][string]$ImageRoot,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedSourceSha256,
    [string]$ReportDirectory='',
    [string]$ClassesDirectory='build/native-ordnance-saturation/classes',
    [ValidateScript({$_ -eq 0 -or ($_ -ge 60 -and $_ -le 90)})][int]$PerformanceSeconds=0,
    [string]$JdkHome='C:\Users\vitalii\.jdks\ms-21.0.11'
)
$ErrorActionPreference='Stop'
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
& $java '-Xms128m' '-Xmx768m' '-cp' $classpath 'game.wreckriff.diagnostics.NativeOrdnanceSaturationReview' @reviewArgs 2>&1 | Tee-Object -FilePath (Join-Path $ReportDirectory 'execution.log')
if ($LASTEXITCODE -ne 0) { throw "Native ordnance saturation exited with code $LASTEXITCODE" }
$report = Join-Path $ReportDirectory $(if($PerformanceSeconds -gt 0){'performance.json'}else{'review.json'})
if (-not (Test-Path -LiteralPath $report -PathType Leaf)) { throw 'The real-window run did not produce current verification evidence.' }
$evidence = Get-Content -LiteralPath $report -Raw | ConvertFrom-Json
if($PerformanceSeconds -gt 0) {
    if($evidence.status -ne 'COMPLETE' -or $evidence.sourceSha256 -ne $ExpectedSourceSha256 -or -not $evidence.retryCleared){throw 'Active saturation measurement is incomplete or invalid.'}
    if($evidence.frameTimeGate -ne 'PASS'){throw "Active saturation frame-time thresholds failed; inspect $report"}
} elseif ($evidence.status -ne 'PASS' -or $evidence.sourceSha256 -ne $ExpectedSourceSha256 -or -not $evidence.retryCleared) { throw 'Native ordnance saturation evidence did not pass.' }
Write-Output "Native ordnance saturation verified: $report"

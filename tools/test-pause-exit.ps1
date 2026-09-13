param(
    [Parameter(Mandatory=$true)][string]$ImageRoot,
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedSourceSha256,
    [string]$ReportDirectory='',
    [string]$ClassesDirectory='build/classes/java/physicsTest',
    [string]$JdkHome='C:\Users\vitalii\.jdks\ms-21.0.11'
)
$ErrorActionPreference='Stop'
$workspace=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if([string]::IsNullOrWhiteSpace($ReportDirectory)) {
    $ReportDirectory=Join-Path $workspace ('build/reports/native-pause-exit/'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8))
}
$ReportDirectory=[IO.Path]::GetFullPath($ReportDirectory)
if(Test-Path -LiteralPath $ReportDirectory){throw 'Use a fresh report directory to preserve prior launch evidence and profile data.'}
$image=(Resolve-Path -LiteralPath $ImageRoot).Path
$classes=if([IO.Path]::IsPathRooted($ClassesDirectory)){$ClassesDirectory}else{Join-Path $workspace $ClassesDirectory}
$runner=Join-Path $classes 'game/wreckriff/diagnostics/NativePauseExitReview.class'
if(-not (Test-Path -LiteralPath $runner -PathType Leaf)){throw 'Run compilePhysicsTestJava and installDist in the allocated build slot before this native smoke.'}
if(-not (Test-Path -LiteralPath (Join-Path $image 'lib') -PathType Container)){throw 'ImageRoot must be an installed application image containing lib.'}
$java=Join-Path $JdkHome 'bin/java.exe'
if(-not (Test-Path -LiteralPath $java -PathType Leaf)){throw 'Microsoft JDK 21 is required.'}
[IO.Directory]::CreateDirectory($ReportDirectory) | Out-Null
$classpath=(Join-Path $image 'lib/*')+';'+$classes
$arguments=@('-Xms128m','-Xmx1024m','-cp',$classpath,'game.wreckriff.diagnostics.NativePauseExitReview',$ReportDirectory,$ExpectedSourceSha256)
$log=Join-Path $ReportDirectory 'execution.log'
$previousErrorAction=$ErrorActionPreference
try {
    # JVM owns a 170-second scenario deadline plus at most 8 seconds to stop on failure.
    # This foreground invocation creates a real GLFW window and requires an actual audio device.
    $ErrorActionPreference='Continue'
    & $java @arguments *> $log
    $code=$LASTEXITCODE
} finally {$ErrorActionPreference=$previousErrorAction}
if($code -ne 0){throw "Native pause exit failed with exit code $code; see $log"}
$report=Join-Path $ReportDirectory 'review.json'
if(-not (Test-Path -LiteralPath $report -PathType Leaf)){throw 'The native application did not produce current shutdown verification.'}
$evidence=Get-Content -LiteralPath $report -Raw | ConvertFrom-Json
if($evidence.status -ne 'PASS' -or $evidence.sourceSha256 -ne $ExpectedSourceSha256 -or $evidence.contextAliveAfterShutdown -or
   -not $evidence.shutdownComplete -or -not $evidence.progressFlushed -or -not $evidence.cancelPreservedSession -or
   -not $evidence.cancelPreservedWorld -or -not $evidence.exitViaPauseConfirmation -or @($evidence.captures).Count -ne 3) {
    throw 'Native pause exit evidence did not satisfy the scenario and normal-shutdown requirements.'
}
Write-Output "Native pause exit verified: $report"

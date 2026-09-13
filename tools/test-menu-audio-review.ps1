param(
    [ValidateSet('rivet','grinder','spark','music')][string]$Mode='rivet',
    [string]$ImageRoot='build/install/wreck-riff',
    [string]$ClassesDirectory='build/classes/java/physicsTest',
    [string]$ReportDirectory='',
    [ValidateRange(0,40)][double]$Seconds=0,
    [string]$JdkHome='C:\Users\vitalii\.jdks\ms-21.0.11'
)
$ErrorActionPreference='Stop'
$workspace=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$image=if([IO.Path]::IsPathRooted($ImageRoot)){$ImageRoot}else{Join-Path $workspace $ImageRoot}
$classes=if([IO.Path]::IsPathRooted($ClassesDirectory)){$ClassesDirectory}else{Join-Path $workspace $ClassesDirectory}
if([string]::IsNullOrWhiteSpace($ReportDirectory)){
    $ReportDirectory=Join-Path $workspace ('build/reports/menu-audio-review/'+$Mode+'-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8))
}
$report=[IO.Path]::GetFullPath($ReportDirectory)
if(Test-Path -LiteralPath (Join-Path $report 'review.json')){throw 'Use a fresh review directory; prior evidence is preserved.'}
if(-not(Test-Path -LiteralPath (Join-Path $classes 'game/wreckriff/diagnostics/NativeMenuAudioReview.class'))){throw 'Compile physicsTest classes in an available verification slot before running this script.'}
if(-not(Test-Path -LiteralPath (Join-Path $image 'lib'))){throw 'ImageRoot must be a current installDist image with its lib directory.'}
$java=Join-Path $JdkHome 'bin/java.exe'
if(-not(Test-Path -LiteralPath $java)){throw 'Microsoft JDK 21 is required.'}
[IO.Directory]::CreateDirectory($report)|Out-Null
$arguments=@('-ea','-Xms128m','-Xmx1024m','-cp',((Join-Path $image 'lib/*')+';'+$classes),'game.wreckriff.diagnostics.NativeMenuAudioReview',('--output='+$report))
$arguments+=if($Mode -eq 'music'){'--music'}else{'--profile='+$Mode}
if($Seconds -gt 0){$arguments+='--seconds='+$Seconds.ToString([Globalization.CultureInfo]::InvariantCulture)}
$priorError=$ErrorActionPreference
try {$ErrorActionPreference='Continue'; & $java @arguments *> (Join-Path $report 'execution.log'); $reviewExit=$LASTEXITCODE}
finally {$ErrorActionPreference=$priorError}
if($reviewExit -ne 0){throw "Real-window review exited with code $reviewExit; see $report"}
$manifest=Join-Path $report 'review.json'
if(-not(Test-Path -LiteralPath $manifest)){throw 'The run did not produce current real-window evidence.'}
$evidence=Get-Content -LiteralPath $manifest -Raw|ConvertFrom-Json
if($evidence.status -ne 'PASS' -or -not $evidence.windowVisible -or -not $evidence.audioEnabled){throw "Real-window review failed; see $manifest"}
Write-Output "Review PASS: $manifest"
Write-Output 'review.avi is the real framebuffer recording. audio.wav reconstructs source events; it is not native device output or a microphone capture.'

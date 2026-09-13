param()
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$image=Join-Path $projectRoot 'build/distributions/WreckRiff'
$runRoot=Join-Path $projectRoot ('build/showcase-runs/'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($runRoot)|Out-Null
$started=[DateTime]::UtcNow
$launcher=Start-Process -FilePath (Join-Path $image 'WreckRiff.exe') -WorkingDirectory $image -WindowStyle Hidden -PassThru `
    -ArgumentList @('--dev','--seed=42','--showcase') `
    -RedirectStandardOutput (Join-Path $runRoot 'stdout.log') -RedirectStandardError (Join-Path $runRoot 'stderr.log')
$null=$launcher.Handle # Retain the native handle so Windows PowerShell preserves ExitCode after exit.
Write-Output "Showcase launcher PID: $($launcher.Id); logs: $runRoot"
if(!$launcher.WaitForExit(250000)) {throw "Showcase did not finish within 250 seconds; see $runRoot"}
$launcherExitCode=$launcher.ExitCode
$line=Get-Content -LiteralPath (Join-Path $runRoot 'stdout.log') | Where-Object {$_.StartsWith('DIAGNOSTIC_REPORT: ')} | Select-Object -Last 1
if(!$line) {throw "No showcase report; see $runRoot"}
$reportPath=[IO.Path]::GetFullPath($line.Substring(19).Trim())
$diagnostics=[IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'WreckRiff/diagnostics'))
if(!$reportPath.StartsWith($diagnostics+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {throw 'Unexpected diagnostic path'}
if((Get-Item -LiteralPath $reportPath).LastWriteTimeUtc -lt $started) {throw 'Showcase evidence predates launch'}
$evidence=Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
$directory=Split-Path -Parent $reportPath
$captures=@(Get-ChildItem -LiteralPath (Join-Path $directory 'captures') -Filter '*.png' -File)
$requiredComboCaptures=@('combo-freeze-launch','combo-freeze-flight','combo-freeze-hit','combo-ballistic-launch',
    'combo-ballistic-warning','combo-ballistic-hit-1','combo-ballistic-hit-2','combo-ballistic-hit-3','combo-ballistic-hit-4','combo-freeze-ended')
$missingComboCaptures=@($requiredComboCaptures | Where-Object {
    $capturePattern='-'+[regex]::Escape($_)+'-'
    !($captures | Where-Object {$_.Name -match $capturePattern -and $_.Length -gt 0})
})
$artifacts=@('showcase.avi','audio.wav','audio-events.json','AUDIO_README.txt')
$artifactsPresent=$true
foreach($artifact in $artifacts) {
    $file=Join-Path $directory $artifact
    if(!(Test-Path -LiteralPath $file) -or (Get-Item -LiteralPath $file).Length -eq 0) {$artifactsPresent=$false}
}
$passed=$launcherExitCode -eq 0 -and $evidence.status -eq 'PASS' -and $evidence.mode -eq 'showcase' `
    -and $evidence.audioEnabled -and $evidence.windowVisible -and $evidence.showcase.seconds -ge 49 `
    -and $evidence.showcase.freezeBallisticCombo.damagingCharges -eq 4 `
    -and $evidence.showcase.freezeBallisticCombo.allHitsWhileFrozen -and $evidence.showcase.freezeBallisticCombo.controlEnded `
    -and $captures.Count -ge 17 -and $missingComboCaptures.Count -eq 0 -and $artifactsPresent
$result=[ordered]@{
    status=$(if($passed){'PASS'}else{'FAIL'});sourceSha256=$evidence.sourceSha256
    runRoot=$runRoot;diagnosticPath=$reportPath;artifactDirectory=$directory
    launcherExitCode=$launcherExitCode;captureCount=$captures.Count;artifactsPresent=$artifactsPresent
    missingComboCaptures=$missingComboCaptures
    audio='Reconstructed allocated game voices; not native OpenAL/HRTF recording'
}
Copy-Item -LiteralPath $reportPath -Destination (Join-Path $runRoot 'diagnostic-result.json')
[IO.Directory]::CreateDirectory((Join-Path $projectRoot 'build/reports'))|Out-Null
[IO.File]::WriteAllText((Join-Path $projectRoot 'build/reports/showcase.json'),($result|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
if(!$passed) {throw "Showcase failed; see $runRoot and $reportPath"}
Write-Output "Showcase PASS: $directory"

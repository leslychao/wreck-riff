param(
    [Parameter(Mandatory=$true)][string]$Image,
    [ValidateSet('construction_17','neon_zero','euphoria_park','dead-air-yard')][string]$Arena='dead-air-yard',
    [ValidateSet(0,30,60,120)][int]$RenderFps=0,
    [ValidateRange(10,30)][int]$RecordSeconds=30
)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$imagePath=(Resolve-Path -LiteralPath $Image).Path
$library=Join-Path $imagePath 'lib'
$mainJar=@(Get-ChildItem -LiteralPath $library -Filter 'wreck-riff-*.jar')
if($mainJar.Count -ne 1){throw 'Expected one immutable installDist application JAR.'}
$ffmpeg=Join-Path $projectRoot 'build/asset-tooling/imageio_ffmpeg/binaries/ffmpeg-win-x86_64-v7.1.exe'
if(!(Test-Path -LiteralPath $ffmpeg)){throw 'Local FFmpeg is required for explicit capture; no download is performed.'}
$output=Join-Path $projectRoot ('build/combat-graphics-work/moving-captures/'+$Arena+'-'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($output)|Out-Null
$game=$null;$capture=$null;$previousLocal=$env:LOCALAPPDATA
$result=[ordered]@{status='FAIL';scope='Visual capture of real AI combat; recording invalidates performance evidence';arenaId=$Arena;seed=42;renderFps=$RenderFps;recordSeconds=$RecordSeconds;mainJarSha256=(Get-FileHash -LiteralPath $mainJar[0].FullName -Algorithm SHA256).Hash;audio='Silent desktop video; no unrelated system audio is recorded';releaseEligible=$false}
try {
    $env:LOCALAPPDATA=Join-Path $output 'user-data'
    $arguments=@('-Xms128m','-Xmx768m','-cp',('"'+$library+'\*"'),'game.wreckriff.Main','--dev','--seed=42','--ai-player',"--arena=$Arena",'--resolution=1080p','--benchmark-seconds=60')
    if($RenderFps){$arguments+="--render-fps=$RenderFps"}
    $game=Start-Process -FilePath 'C:\Users\vitalii\.jdks\ms-21.0.11\bin\java.exe' -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $output 'game-stdout.log') -RedirectStandardError (Join-Path $output 'game-stderr.log')
    $started=[DateTime]::UtcNow
    # The ordinary route includes 30 active seconds of warmup. Capture an interior segment,
    # while the engine retains its real chase camera, audio and normal AI/physics owners.
    while(([DateTime]::UtcNow-$started).TotalSeconds -lt 40){if($game.HasExited){throw 'Game exited before moving capture'};Start-Sleep -Milliseconds 500}
    $game.Refresh();$title=$game.MainWindowTitle
    if(!$title.StartsWith('Wreck Riff | ')){throw 'The owned process has no visible Wreck Riff window'}
    $video=Join-Path $output 'moving-combat.mp4'
    $captureArguments=@('-hide_banner','-nostdin','-y','-f','gdigrab','-framerate','60','-draw_mouse','0','-i',('"title='+$title+'"'),'-t',"$RecordSeconds",'-an','-c:v','libx264','-preset','veryfast','-crf','20','-pix_fmt','yuv420p','-movflags','+faststart',('"'+$video+'"'))
    $capture=Start-Process -FilePath $ffmpeg -ArgumentList $captureArguments -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $output 'capture-stdout.log') -RedirectStandardError (Join-Path $output 'capture-stderr.log')
    if(!$capture.WaitForExit(($RecordSeconds+20)*1000)){throw 'Bounded video capture timed out'}
    if($capture.ExitCode -ne 0 -or !(Test-Path -LiteralPath $video)){throw 'FFmpeg window capture failed'}
    if(!$game.WaitForExit(120000)){throw 'Ordinary combat route timed out'}
    if($game.ExitCode -ne 0){throw 'Ordinary combat route reported a failure'}
    $line=Get-Content -LiteralPath (Join-Path $output 'game-stdout.log')|Where-Object {$_.StartsWith('DIAGNOSTIC_REPORT: ')}|Select-Object -Last 1
    if(!$line){throw 'Game did not write its diagnostic identity'}
    $reportPath=[IO.Path]::GetFullPath($line.Substring(19).Trim())
    if(!$reportPath.StartsWith([IO.Path]::GetFullPath($env:LOCALAPPDATA)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Diagnostic path escaped this capture'}
    $report=Get-Content -LiteralPath $reportPath -Raw|ConvertFrom-Json
    if($report.pid -ne $game.Id -or $report.errors.Count -gt 0 -or !$report.windowVisible){throw 'Game identity/window check failed'}
    $result.diagnostic=$reportPath;$result.video=$video;$result.videoSha256=(Get-FileHash -LiteralPath $video -Algorithm SHA256).Hash
    if((Get-FileHash -LiteralPath $mainJar[0].FullName -Algorithm SHA256).Hash -ne $result.mainJarSha256){throw 'Immutable image changed while capturing'}
    $result.status='CAPTURED_REQUIRES_VISUAL_REVIEW'
} catch {$result.failure=$_.Exception.Message;throw}
finally {
    if($capture -and !$capture.HasExited){$capture.Kill();$capture.WaitForExit()}
    if($game -and !$game.HasExited){$game.Kill();$game.WaitForExit()}
    $env:LOCALAPPDATA=$previousLocal
    [IO.File]::WriteAllText((Join-Path $output 'capture.json'),($result|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
    Write-Output "$($result.status): $output"
}

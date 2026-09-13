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
$game=$null;$encode=$null;$verify=$null;$previousLocal=$env:LOCALAPPDATA
$result=[ordered]@{status='FAIL';scope='Visual capture of real AI combat; recording invalidates performance evidence';arenaId=$Arena;seed=42;renderFps=$RenderFps;recordSeconds=$RecordSeconds;mainJarSha256=(Get-FileHash -LiteralPath $mainJar[0].FullName -Algorithm SHA256).Hash;audio='Silent engine video; no system audio is recorded';releaseEligible=$false}
try {
    $env:LOCALAPPDATA=Join-Path $output 'user-data'
    $arguments=@('-Xms128m','-Xmx768m','-cp',('"'+$library+'\*"'),'game.wreckriff.Main','--dev','--seed=42','--ai-player',"--arena=$Arena",'--resolution=1080p','--benchmark-seconds=60',"--combat-video-seconds=$RecordSeconds")
    if($RenderFps){$arguments+="--render-fps=$RenderFps"}
    $game=Start-Process -FilePath 'C:\Users\vitalii\.jdks\ms-21.0.11\bin\java.exe' -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $output 'game-stdout.log') -RedirectStandardError (Join-Path $output 'game-stderr.log')
    $started=[DateTime]::UtcNow
    # The game records its own final framebuffer after 30 actual active warmup seconds.
    # Fullscreen OpenGL can leave a stale GDI image even while gameplay continues.
    while(!$game.HasExited){if(([DateTime]::UtcNow-$started).TotalSeconds -gt 210){throw 'Ordinary combat capture route timed out'};Start-Sleep -Milliseconds 500}
    $game.WaitForExit()
    if($game.ExitCode -ne 0){throw 'Ordinary combat route reported a failure'}
    $line=Get-Content -LiteralPath (Join-Path $output 'game-stdout.log')|Where-Object {$_.StartsWith('DIAGNOSTIC_REPORT: ')}|Select-Object -Last 1
    if(!$line){throw 'Game did not write its diagnostic identity'}
    $reportPath=[IO.Path]::GetFullPath($line.Substring(19).Trim())
    if(!$reportPath.StartsWith([IO.Path]::GetFullPath($env:LOCALAPPDATA)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Diagnostic path escaped this capture'}
    $report=Get-Content -LiteralPath $reportPath -Raw|ConvertFrom-Json
    if($report.pid -ne $game.Id -or $report.errors.Count -gt 0 -or !$report.windowVisible){throw 'Game identity/window check failed'}
    if(!$report.videoRecording -or $report.movingVideo.status -ne 'CAPTURED_REQUIRES_VISUAL_REVIEW' -or $report.movingVideo.seconds -lt $RecordSeconds){throw 'Engine did not capture the requested moving-combat frames'}
    $sourceVideo=Join-Path (Split-Path -Parent $reportPath) 'moving-combat.avi'
    if(!(Test-Path -LiteralPath $sourceVideo)){throw 'Engine AVI is missing'}
    $progress=Join-Path $output 'decode-progress.txt'
    $verifyArguments=@('-hide_banner','-nostdin','-nostats','-xerror','-i',('"'+$sourceVideo+'"'),'-map','0:v:0','-an','-fps_mode','passthrough','-progress',('"'+$progress+'"'),'-f','null','NUL')
    $verify=Start-Process -FilePath $ffmpeg -ArgumentList $verifyArguments -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $output 'decode-stdout.log') -RedirectStandardError (Join-Path $output 'decode-stderr.log')
    if(!$verify.WaitForExit(60000)){throw 'Bounded engine AVI verification timed out'}
    if($verify.ExitCode -ne 0 -or !(Test-Path -LiteralPath $progress)){throw 'Engine AVI decoding failed'}
    $decoded=Get-Content -LiteralPath $progress
    if(($decoded|Select-Object -Last 1) -ne 'progress=end'){throw 'Engine AVI decoding did not complete'}
    $frameLine=$decoded|Where-Object {$_.StartsWith('frame=')}|Select-Object -Last 1
    $timeLine=$decoded|Where-Object {$_.StartsWith('out_time_us=')}|Select-Object -Last 1
    if(!$frameLine -or !$timeLine){throw 'Engine AVI decoding omitted frame or duration evidence'}
    $decodedFrames=[long]$frameLine.Substring(6);$decodedSeconds=[long]$timeLine.Substring(12)/1000000.0
    $fps=[int]$report.movingVideo.fps;$expectedFrames=$RecordSeconds*$fps
    if($fps -le 0 -or $decodedFrames -ne $expectedFrames -or $report.movingVideo.renderedFrames -ne $expectedFrames){throw "Engine AVI frame count mismatch: decoded=$decodedFrames expected=$expectedFrames reported=$($report.movingVideo.renderedFrames)"}
    if([Math]::Abs($decodedSeconds-$RecordSeconds) -gt 1.0/$fps+.01){throw "Engine AVI duration mismatch: decoded=$decodedSeconds expected=$RecordSeconds"}
    $result.decodedFrames=$decodedFrames;$result.decodedSeconds=$decodedSeconds;$result.decodeProgress=$progress
    $video=Join-Path $output 'moving-combat.mp4'
    $encodeArguments=@('-hide_banner','-nostdin','-y','-i',('"'+$sourceVideo+'"'),'-an','-c:v','libx264','-threads','4','-preset','fast','-crf','18','-pix_fmt','yuv420p','-movflags','+faststart',('"'+$video+'"'))
    $encode=Start-Process -FilePath $ffmpeg -ArgumentList $encodeArguments -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $output 'encode-stdout.log') -RedirectStandardError (Join-Path $output 'encode-stderr.log')
    if(!$encode.WaitForExit(60000)){throw 'Bounded video conversion timed out'}
    if($encode.ExitCode -ne 0 -or !(Test-Path -LiteralPath $video)){throw 'Engine video conversion failed'}
    $result.sourceVideo=$sourceVideo;$result.sourceVideoSha256=(Get-FileHash -LiteralPath $sourceVideo -Algorithm SHA256).Hash
    $result.capture='jME VideoRecorderAppState final framebuffer; no desktop capture';$result.renderedFrames=$report.movingVideo.renderedFrames;$result.videoFps=$report.movingVideo.fps
    $result.diagnostic=$reportPath;$result.video=$video;$result.videoSha256=(Get-FileHash -LiteralPath $video -Algorithm SHA256).Hash
    if((Get-FileHash -LiteralPath $mainJar[0].FullName -Algorithm SHA256).Hash -ne $result.mainJarSha256){throw 'Immutable image changed while capturing'}
    $result.status='CAPTURED_REQUIRES_VISUAL_REVIEW'
} catch {$result.failure=$_.Exception.Message;throw}
finally {
    if($verify -and !$verify.HasExited){$verify.Kill();$verify.WaitForExit()}
    if($encode -and !$encode.HasExited){$encode.Kill();$encode.WaitForExit()}
    if($game -and !$game.HasExited){$game.Kill();$game.WaitForExit()}
    $env:LOCALAPPDATA=$previousLocal
    [IO.File]::WriteAllText((Join-Path $output 'capture.json'),($result|ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
    Write-Output "$($result.status): $output"
}

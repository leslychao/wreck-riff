# Narrow showcase media validation. No capture, build, download or GPU operations.
Set-StrictMode -Version Latest

function Get-ShowcaseCaptureLabels {
    return @('hp-100','hp-75','hp-50','hp-25','hp-0','hp-repaired','machine-gun','homing-hit','power-hit','mine-hit',
        'freeze','shield','napalm','ballistic-warning','ballistic-hit','cannon-hit','cannon-ricochet','cannon-lethal',
        'wreck-removed','combo-freeze-launch','combo-freeze-flight','combo-freeze-hit','combo-ballistic-launch',
        'combo-ballistic-warning','combo-ballistic-hit-1','combo-ballistic-hit-2','combo-ballistic-hit-3','combo-ballistic-hit-4','combo-freeze-ended')
}
function Resolve-ShowcaseArtifacts($Report) {
    $captures=@{};$artifacts=@{};$labels=@(Get-ShowcaseCaptureLabels)
    foreach($entry in $Report.captures) {
        if($labels -cnotcontains $entry.label -or $captures.ContainsKey($entry.label) -or
           (Split-Path -Leaf $entry.path) -notmatch ('^WreckRiff-'+[regex]::Escape($Report.version)+'-'+[regex]::Escape($entry.label)+'-\d{12,}-\d+\.png$')){throw 'Unexpected, duplicated or incorrectly bound showcase capture label.'}
        Assert-ReleaseArtifact $entry;Assert-ReleaseUiPng $entry.path 1600 900
        $captures[$entry.label]=$entry
    }
    if($captures.Count -ne $labels.Count){throw 'Exactly 29 verified showcase captures are required.'}
    $required=@('showcase.avi','audio.wav','audio-events.json','AUDIO_README.txt','diagnostic-result.json')
    foreach($entry in $Report.artifacts) {
        $name=Split-Path -Leaf $entry.path
        if($required -cnotcontains $name -or $artifacts.ContainsKey($name)){throw 'Unexpected or duplicated verified showcase artifact.'}
        Assert-ReleaseArtifact $entry;$artifacts[$name]=$entry
    }
    if($artifacts.Count -ne $required.Count){throw 'All five verified showcase artifacts are required.'}
    if($Report.diagnostic.sha256 -ne $artifacts['diagnostic-result.json'].sha256 -or
       [IO.Path]::GetFullPath($Report.diagnostic.path) -ne [IO.Path]::GetFullPath($artifacts['diagnostic-result.json'].path)){throw 'Showcase diagnostic artifact binding differs.'}
    return [pscustomobject]@{captures=$captures;artifacts=$artifacts}
}
function Copy-ShowcaseArtifact($Artifact,[string]$Destination) {
    Assert-ReleaseArtifact $Artifact
    Copy-Item -LiteralPath $Artifact.path -Destination $Destination
    $copy=Get-ReleaseArtifact $Destination
    if($copy.sha256 -ne $Artifact.sha256){throw 'Exported copy differs from the verified showcase artifact.'}
    return $copy
}
function Invoke-ShowcaseFfmpeg([string]$Executable,[string[]]$Arguments,[string]$LogPrefix,[int]$TimeoutSeconds=120) {
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($LogPrefix)))|Out-Null
    $quoted=foreach($argument in $Arguments) {
        if($argument -match '["\r\n]' -or $argument.EndsWith('\')){throw 'Unsupported FFmpeg argument; expected a plain file path or fixed option.'}
        '"'+$argument+'"'
    }
    $process=$null
    try {
        $process=Start-Process -FilePath $Executable -ArgumentList $quoted -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput ($LogPrefix+'-stdout.log') -RedirectStandardError ($LogPrefix+'-stderr.log')
        $null=$process.Handle
        if(!$process.WaitForExit($TimeoutSeconds*1000)){throw "Showcase FFmpeg exceeded $TimeoutSeconds seconds; $LogPrefix"}
        $process.WaitForExit()
        if($process.ExitCode -ne 0){throw "Showcase FFmpeg failed ($($process.ExitCode)); $LogPrefix"}
    } finally {if($process -and !$process.HasExited){$process.Kill();$process.WaitForExit()}}
}
function Read-ShowcaseDecodeProgress([string]$Path,[bool]$Video) {
    if(!(Test-Path -LiteralPath $Path -PathType Leaf)){throw 'FFmpeg did not write decode progress.'}
    $lines=@(Get-Content -LiteralPath $Path)
    if($lines.Count -eq 0 -or $lines[-1] -ne 'progress=end'){throw 'FFmpeg did not decode the complete media stream.'}
    $time=@($lines|Where-Object {$_.StartsWith('out_time_us=')})
    if($time.Count -eq 0){throw 'FFmpeg omitted decoded duration.'}
    $seconds=[long]$time[-1].Substring(12)/1000000.0
    if($Video) {
        $frames=@($lines|Where-Object {$_.StartsWith('frame=')})
        if($frames.Count -eq 0){throw 'FFmpeg omitted decoded frame count.'}
        return [pscustomobject]@{seconds=$seconds;frames=[long]$frames[-1].Substring(6)}
    }
    return [pscustomobject]@{seconds=$seconds}
}
function Test-ShowcaseVideo([string]$Ffmpeg,[string]$Path,[int]$Fps,[string]$LogPrefix,[long]$SourceFrames=0) {
    if($Fps -notin @(30,60,120)){throw 'Unsupported showcase frame rate.'}
    $progress=$LogPrefix+'-progress.txt'
    Invoke-ShowcaseFfmpeg $Ffmpeg @('-hide_banner','-nostdin','-nostats','-xerror','-threads','2','-i',$Path,
        '-map','0:v:0','-an','-fps_mode','passthrough','-progress',$progress,'-f','null','NUL') $LogPrefix
    $decoded=Read-ShowcaseDecodeProgress $progress $true
    # Only input metadata is inspected; the null output cannot conceal wrong input dimensions/FPS.
    $inputHeader=((Get-Content -LiteralPath ($LogPrefix+'-stderr.log') -Raw) -split 'Stream mapping:',2)[0]
    $videoLines=@($inputHeader -split "`r?`n"|Where-Object {$_ -match '^\s*Stream #0:\d+.*Video:'})
    if($videoLines.Count -ne 1 -or $videoLines[0] -notmatch '(?:,|\s)1600x900(?:\s|,)' -or
       $videoLines[0] -notmatch '\b(?<fps>[0-9]+(?:\.[0-9]+)?) fps\b'){throw 'Expected one actual 1600x900 showcase video stream.'}
    $actualFps=[double]::Parse($Matches['fps'],[Globalization.CultureInfo]::InvariantCulture)
    $codec=if($SourceFrames -gt 0){'Video: h264\b'}else{'Video: mjpeg\b'}
    if($videoLines[0] -notmatch $codec){throw 'Showcase video codec differs from expected source MJPEG / exported H264.'}
    if([Math]::Abs($actualFps-$Fps) -gt .001 -or [Math]::Abs($decoded.frames-49*$Fps) -gt 2 -or
       [Math]::Abs($decoded.seconds-49) -gt 2.0/$Fps+.001){throw "Showcase video is incomplete or differs from selected FPS: frames=$($decoded.frames), seconds=$($decoded.seconds), fps=$actualFps"}
    if($SourceFrames -gt 0 -and $decoded.frames -ne $SourceFrames){throw 'MP4 changed the decoded source AVI frame count.'}
    return [ordered]@{status='FULLY_DECODED';width=1600;height=900;fps=$actualFps;frames=$decoded.frames;seconds=$decoded.seconds;progress=(Get-ReleaseArtifact $progress)}
}
function Test-ShowcaseAudio([string]$Ffmpeg,[string]$Path,[int]$Fps,[string]$LogPrefix,[bool]$SourceWav=$false) {
    $progress=$LogPrefix+'-progress.txt'
    Invoke-ShowcaseFfmpeg $Ffmpeg @('-hide_banner','-nostdin','-nostats','-xerror','-threads','2','-i',$Path,
        '-map','0:a:0','-vn','-progress',$progress,'-f','null','NUL') $LogPrefix
    $decoded=Read-ShowcaseDecodeProgress $progress $false
    $inputHeader=((Get-Content -LiteralPath ($LogPrefix+'-stderr.log') -Raw) -split 'Stream mapping:',2)[0]
    $audioLines=@($inputHeader -split "`r?`n"|Where-Object {$_ -match '^\s*Stream #0:\d+.*Audio:'})
    if($audioLines.Count -ne 1 -or $audioLines[0] -notmatch '\b48000 Hz, stereo\b' -or
       ($SourceWav -and $audioLines[0] -notmatch 'Audio: pcm_s16le\b') -or
       (!$SourceWav -and $audioLines[0] -notmatch 'Audio: aac\b')){throw 'Expected one 48kHz stereo showcase audio stream (source PCM16 / exported AAC).'}
    $tolerance=if($SourceWav){2.0/48000+.000001}else{2.0/$Fps+.001}
    if([Math]::Abs($decoded.seconds-49) -gt $tolerance){throw "Showcase audio is incomplete: $($decoded.seconds) seconds"}
    return [ordered]@{status='FULLY_DECODED';sampleRate=48000;channels=2;seconds=$decoded.seconds;progress=(Get-ReleaseArtifact $progress)}
}

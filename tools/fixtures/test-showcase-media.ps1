# CPU-only media/identity regressions; existing showcase evidence is read-only.
param([Parameter(Mandatory=$true)][string]$SourceDirectory,[string]$Ffmpeg)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../release-evidence.ps1')
. (Join-Path $PSScriptRoot '../showcase-media.ps1')
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if(!$Ffmpeg){$Ffmpeg=Join-Path $root 'build/asset-tooling/imageio_ffmpeg/binaries/ffmpeg-win-x86_64-v7.1.exe'}
$source=[IO.Path]::GetFullPath($SourceDirectory)
$work=Join-Path $root ('build/showcase-media-fixtures/'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($work)|Out-Null
$checks=[Collections.Generic.List[string]]::new();$result=[ordered]@{status='FAIL';work=$work}
function Check([bool]$Value,[string]$Name){if(!$Value){throw "Fixture failed: $Name"};$checks.Add($Name)}
function Reject([scriptblock]$Action,[string]$Message,[string]$Name){$rejected=$false;try{& $Action}catch{if(!$_.Exception.Message.Contains($Message)){throw};$rejected=$true};Check $rejected $Name}
try {
    $parseTokens=$null;$parseErrors=$null
    $null=[Management.Automation.Language.Parser]::ParseFile((Join-Path $root 'tools/export-showcase.ps1'),[ref]$parseTokens,[ref]$parseErrors)
    Check ($parseErrors.Count -eq 0) 'export entrypoint parses in Windows PowerShell 5.1 without a BOM'
    $captions=Read-ReleaseJson (Join-Path $root 'tools/showcase-captions-ru.json')
    $labels=@(Get-ShowcaseCaptureLabels)
    Check ($captions.Count -eq $labels.Count -and @($captions|Where-Object {$_[0] -notin $labels}).Count -eq 0 -and @($captions|ForEach-Object {$_[0]}|Select-Object -Unique).Count -eq $labels.Count) 'all current captures have one presentation caption'
    Check ([int][char]$captions[0][1][0] -eq 0x0426) 'external UTF8 captions retain Cyrillic in Windows PowerShell'
    $avi=Join-Path $source 'showcase.avi';$wav=Join-Path $source 'audio.wav'
    $short=Join-Path $work 'truncated-valid.avi'
    Invoke-ShowcaseFfmpeg $Ffmpeg @('-hide_banner','-nostdin','-xerror','-i',$avi,'-t','1','-map','0:v:0','-an','-c:v','copy',$short) (Join-Path $work 'make-truncated')
    Check ((Get-Item -LiteralPath $short).Length -gt 0) 'RED witness: the former nonempty AVI gate accepts a valid one-second recording'
    Reject {Test-ShowcaseVideo $Ffmpeg $short 30 (Join-Path $work 'truncated')} 'Showcase video is incomplete' 'GREEN: complete decode rejects the truncated AVI'
    $shortWav=Join-Path $work 'truncated-valid.wav'
    Invoke-ShowcaseFfmpeg $Ffmpeg @('-hide_banner','-nostdin','-xerror','-i',$wav,'-t','1','-c:a','pcm_s16le',$shortWav) (Join-Path $work 'make-truncated-audio')
    Reject {Test-ShowcaseAudio $Ffmpeg $shortWav 30 (Join-Path $work 'truncated-audio') $true} 'Showcase audio is incomplete' 'GREEN: complete decode rejects truncated source audio'
    $video=Test-ShowcaseVideo $Ffmpeg $avi 30 (Join-Path $work 'source-video')
    $audio=Test-ShowcaseAudio $Ffmpeg $wav 30 (Join-Path $work 'source-audio') $true
    Check ($video.frames -eq 1469 -and $video.width -eq 1600 -and $video.height -eq 900 -and $video.fps -eq 30) 'Sixth real AVI boundary is accepted:1469frames,1600x900,30fps'
    Check ([Math]::Abs($audio.seconds-49) -lt .00005) 'Actual reconstructed48k stereo source audio fully decodes for49seconds'
    $entries=@();$version=(Read-ReleaseJson (Join-Path $source 'diagnostic-result.json')).version
    $ambiguous=@(Get-ChildItem -LiteralPath (Join-Path $source 'captures') -Filter '*.png'|Where-Object {$_.Name -match '-ballistic-warning-\d{12,}-\d+\.png$'})
    Check ($ambiguous.Count -eq 2) 'RED witness: suffix-only ballistic-warning matcher also selects combo warning'
    foreach($label in Get-ShowcaseCaptureLabels) {
        $matching=@(Get-ChildItem -LiteralPath (Join-Path $source 'captures') -File -Filter '*.png'|Where-Object {$_.Name -match ('^WreckRiff-'+[regex]::Escape($version)+'-'+[regex]::Escape($label)+'-\d{12,}-\d+\.png$')})
        if($matching.Count -ne 1){throw "Missing original fixture capture $label"}
        $entry=Get-ReleaseArtifact $matching[0].FullName;$entry.label=$label;$entries+=$entry
    }
    $artifacts=foreach($name in @('showcase.avi','audio.wav','audio-events.json','AUDIO_README.txt','diagnostic-result.json')){Get-ReleaseArtifact (Join-Path $source $name)}
    $report=[pscustomobject]@{version=$version;captures=$entries;artifacts=$artifacts;diagnostic=(Get-ReleaseArtifact (Join-Path $source 'diagnostic-result.json'));artifactDirectory=(Join-Path $work 'UNVERIFIED-OTHER-RUN')}
    $verified=Resolve-ShowcaseArtifacts $report
    Check ($verified.captures.Count -eq 29 -and (Split-Path -Leaf $verified.captures['ballistic-warning'].path).StartsWith('WreckRiff-'+$version+'-ballistic-warning-')) 'GREEN: all29 exact versioned labels resolve without combo ambiguity'
    Check ($verified.artifacts['showcase.avi'].path -eq [IO.Path]::GetFullPath($avi)) 'Export uses the verified AVI path independently of an incorrect artifactDirectory'
    $copy=Copy-ShowcaseArtifact $verified.captures['hp-100'] (Join-Path $work 'verified-hp100.png')
    Check ($copy.sha256 -eq $verified.captures['hp-100'].sha256) 'Exported PNG copy matches its recorded source hash'
    $report.captures=@($entries)+@($entries[0])
    Reject {Resolve-ShowcaseArtifacts $report} 'duplicated' 'Duplicate capture label cannot select a newer unverified picture'
    $movie=Join-Path $work 'verified-full.mp4'
    Invoke-ShowcaseFfmpeg $Ffmpeg @('-hide_banner','-nostdin','-xerror','-threads','2','-i',$avi,'-i',$wav,'-map','0:v:0','-map','1:a:0',
        '-c:v','libx264','-threads','4','-preset','fast','-crf','19','-pix_fmt','yuv420p','-fps_mode','passthrough','-c:a','aac','-b:a','192k','-shortest','-movflags','+faststart',$movie) (Join-Path $work 'encode') 180
    $outputVideo=Test-ShowcaseVideo $Ffmpeg $movie 30 (Join-Path $work 'output-video') $video.frames
    $outputAudio=Test-ShowcaseAudio $Ffmpeg $movie 30 (Join-Path $work 'output-audio')
    Check ($outputVideo.frames -eq $video.frames) 'H264 MP4 preserves every decoded source frame'
    Check ([Math]::Abs($outputAudio.seconds-49) -lt 2.0/30+.001) 'AAC output preserves the complete49second stereo track'
    $result.sourceVideo=$video;$result.sourceAudio=$audio;$result.outputVideo=$outputVideo;$result.outputAudio=$outputAudio;$result.mp4=Get-ReleaseArtifact $movie
    $result.status='PASS'
}catch{$result.failure=$_.Exception.Message;throw}
finally{$result.checks=$checks.ToArray();Write-ReleaseJson (Join-Path $work 'fixture.json') $result;Write-Output "$($result.status): $work"}

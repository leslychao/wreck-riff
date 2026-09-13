param(
    [Parameter(Mandatory=$true)][string]$Ffmpeg,
    [Parameter(Mandatory=$true)][string]$ReportPath,
    [Parameter(Mandatory=$true)][string]$OutputDirectory
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'release-evidence.ps1')
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $PSScriptRoot 'showcase-media.ps1')
$report=Read-ReleaseJson $ReportPath
if($report.status -ne 'TECHNICAL_PASS_REQUIRES_VISUAL_REVIEW') {throw 'A complete immutable-package showcase is required'}
$sourceReport=Get-ReleaseArtifact $ReportPath
$identity=Get-ReleasePackageIdentity $report.zipPath
Assert-ReleaseIdentity $report $identity
$verified=Resolve-ShowcaseArtifacts $report
$destination=[IO.Path]::GetFullPath($OutputDirectory)
$allowed=[IO.Path]::GetFullPath((Join-Path $projectRoot 'build'))+[IO.Path]::DirectorySeparatorChar
if(!$destination.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)){throw 'Showcase export must stay inside workspace build/'}
if(Test-Path -LiteralPath $destination) {throw 'Preserve the earlier demo before exporting a new one'}
[IO.Directory]::CreateDirectory($destination)|Out-Null
[IO.Directory]::CreateDirectory((Join-Path $destination 'captures'))|Out-Null
$media=[ordered]@{schemaVersion=2;status='FAIL';sourceShowcase=$sourceReport;zipSha256=$report.zipSha256;
    sourceSha256=$report.sourceSha256;mainJarSha256=$report.mainJarSha256;renderFps=$report.renderFps;
    msaaSamples=$report.msaaSamples;glow=$report.glow;performance='NOT_MEASURED';feelApproval='PENDING_OWNER';audio=$report.audio}
try {
    $copies=@()
    foreach($label in Get-ShowcaseCaptureLabels){$copy=Copy-ShowcaseArtifact $verified.captures[$label] (Join-Path $destination ('captures/'+$label+'.png'));$copy.label=$label;$copies+=$copy}
    foreach($name in @('audio-events.json','AUDIO_README.txt','diagnostic-result.json')){$copies+=Copy-ShowcaseArtifact $verified.artifacts[$name] (Join-Path $destination $name)}
    $media.copiedShowcase=Copy-ShowcaseArtifact $sourceReport (Join-Path $destination 'showcase-verification.json')
    $media.copies=$copies
    $decodePrefix=Join-Path $destination 'decode'
    $sourceVideo=Test-ShowcaseVideo $Ffmpeg $verified.artifacts['showcase.avi'].path $report.renderFps ($decodePrefix+'-avi')
    $sourceAudio=Test-ShowcaseAudio $Ffmpeg $verified.artifacts['audio.wav'].path $report.renderFps ($decodePrefix+'-wav') $true
    $movie=Join-Path $destination 'WreckRiff-0.4.0-demo.mp4'
    Invoke-ShowcaseFfmpeg $Ffmpeg @('-hide_banner','-nostdin','-xerror','-y','-threads','2','-i',$verified.artifacts['showcase.avi'].path,
        '-i',$verified.artifacts['audio.wav'].path,'-map','0:v:0','-map','1:a:0','-c:v','libx264','-threads','4','-preset','fast',
        '-crf','19','-pix_fmt','yuv420p','-fps_mode','passthrough','-c:a','aac','-b:a','192k','-shortest','-movflags','+faststart',$movie) (Join-Path $destination 'encode') 180
    $outputVideo=Test-ShowcaseVideo $Ffmpeg $movie $report.renderFps ($decodePrefix+'-mp4-video') $sourceVideo.frames
    $outputAudio=Test-ShowcaseAudio $Ffmpeg $movie $report.renderFps ($decodePrefix+'-mp4-audio')
    foreach($entry in @($report.captures)+@($report.artifacts)+@($sourceReport)){Assert-ReleaseArtifact $entry}
    if((Get-ReleaseSha256 $report.zipPath) -ne $identity.zipSha256){throw 'Showcase ZIP changed during export'}
    $media.sourceVideo=$sourceVideo;$media.sourceAudio=$sourceAudio;$media.outputVideo=$outputVideo;$media.outputAudio=$outputAudio
$shots=Read-ReleaseJson (Join-Path $PSScriptRoot 'showcase-captions-ru.json')
$cards=foreach($shot in $shots) {
    if(!$verified.captures.ContainsKey($shot[0])){throw "Missing verified capture $($shot[0])"}
    $name=$shot[0]+'.png'
    '<figure><a href="captures/'+$name+'"><img loading="lazy" src="captures/'+$name+'" alt="'+$shot[1]+'"></a><figcaption>'+$shot[1]+'</figcaption></figure>'
}
$html=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'showcase-template.html') -Raw -Encoding UTF8
$html+='<p>Source SHA-256: <code>'+[Net.WebUtility]::HtmlEncode($report.sourceSha256)+'</code></p><section>'+($cards -join "`n")+'</section></main></html>'
[IO.File]::WriteAllText((Join-Path $destination 'index.html'),$html,[Text.UTF8Encoding]::new($false))
    $media.video=Get-ReleaseArtifact $movie
    $media.index=Get-ReleaseArtifact (Join-Path $destination 'index.html')
    $media.status='DECODED_EXPORT_REQUIRES_VISUAL_REVIEW'
} catch {$media.failure=$_.Exception.Message;throw}
finally {Write-ReleaseJson (Join-Path $destination 'media.json') $media}
Write-Output "Showcase export: $destination"

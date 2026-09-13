param(
    [Parameter(Mandatory=$true)][string]$ZipPath,
    [ValidateSet(30,60,120)][int]$RenderFps=30,
    [ValidateSet(0,2,4,8)][int]$Msaa=4,
    [switch]$NoGlow
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'release-evidence.ps1')
. (Join-Path $PSScriptRoot 'showcase-media.ps1')
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runRoot=Join-Path $projectRoot ('build/showcase-runs/'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($runRoot)|Out-Null
$package=Expand-ReleasePackage $ZipPath (Join-Path $runRoot 'extracted')
$identity=$package.identity
$result=[ordered]@{
    schemaVersion=2;status='FAIL';version=$identity.version;sourceSha256=$identity.sourceSha256;mainJarSha256=$identity.mainJarSha256
    verificationInputsSha256=$identity.verificationInputsSha256
    zipSha256=$identity.zipSha256;zipPath=$identity.zipPath;runRoot=$runRoot
    renderFps=$RenderFps;msaaSamples=$Msaa;glow=(!$NoGlow)
    performance='NOT_MEASURED: video recording';feelApproval='PENDING_OWNER'
    videoValidation='PENDING_EXPORT_DECODE'
    audio='Reconstructed allocated game voices; not native OpenAL/HRTF recording'
}
try {
    Assert-ReleaseImage $identity.zipPath $package.image
    $arguments=@('--dev','--seed=42','--showcase',"--render-fps=$RenderFps","--msaa=$Msaa")
    if($NoGlow){$arguments+='--no-glow'}
    $run=Invoke-ReleaseProcess $package.image $runRoot $arguments 'showcase' 420 $identity
    Assert-ReleaseImage $identity.zipPath $package.image
    if((Get-ReleaseSha256 $identity.zipPath) -ne $identity.zipSha256){throw 'Final ZIP changed during showcase.'}
    $evidence=$run.diagnostic;$directory=Split-Path -Parent $run.reportPath
    $result.diagnosticPath=$run.reportPath;$result.artifactDirectory=$directory
    $result.diagnostic=Get-ReleaseArtifact $run.reportPath
    $result.gamePid=$run.gamePid;$result.launcherPid=$run.launcherPid
    $result.startedAtUtc=$run.startedAtUtc;$result.completedAtUtc=$run.completedAtUtc
    if($evidence.status -ne 'PASS' -or $evidence.showcase.seconds -lt 49 -or
       $evidence.showcase.freezeBallisticCombo.damagingCharges -ne 4 -or
       !$evidence.showcase.freezeBallisticCombo.allHitsWhileFrozen -or !$evidence.showcase.freezeBallisticCombo.controlEnded){
        throw 'Showcase did not demonstrate the full arsenal and four frozen Ballistic hits.'
    }
    if($evidence.requestedRenderFps -ne $RenderFps -or $evidence.recordingFrameRate -ne $RenderFps -or
       $evidence.msaaSamples -ne $Msaa -or $evidence.glow -ne (!$NoGlow) -or
       $evidence.width -ne 1600 -or $evidence.height -ne 900 -or $evidence.detailedProfiling -or !$evidence.videoRecording){
        throw 'Rendered showcase conditions differ from the requested visual comparison.'
    }
    $requiredCaptures=@(Get-ShowcaseCaptureLabels)
    $captures=@(Get-ChildItem -LiteralPath (Join-Path $directory 'captures') -Filter '*.png' -File)
    $verifiedCaptures=@()
    foreach($label in $requiredCaptures) {
        $capturePattern='^WreckRiff-'+[regex]::Escape($identity.version)+'-'+[regex]::Escape($label)+'-\d{12,}-\d+\.png$'
        $matches=@($captures|Where-Object {$_.Name -match $capturePattern})
        if($matches.Count -ne 1){throw "Missing or ambiguous showcase capture: $label"}
        Assert-ReleaseUiPng $matches[0].FullName 1600 900
        $captureEntry=Get-ReleaseArtifact $matches[0].FullName
        $captureEntry.label=$label
        $verifiedCaptures+=$captureEntry
    }
    $artifacts=@()
    foreach($name in @('showcase.avi','audio.wav','audio-events.json','AUDIO_README.txt','diagnostic-result.json')) {
        $artifact=Join-Path $directory $name
        if(!(Test-Path -LiteralPath $artifact -PathType Leaf) -or (Get-Item -LiteralPath $artifact).Length -eq 0){
            throw "Missing showcase artifact: $name"
        }
        $artifacts+=Get-ReleaseArtifact $artifact
    }
    $result.captures=$verifiedCaptures;$result.artifacts=$artifacts
    $result.status='TECHNICAL_PASS_REQUIRES_VISUAL_REVIEW'
} catch {$result.failure=$_.Exception.Message;throw}
finally {
    Write-ReleaseJson (Join-Path $runRoot 'showcase-verification.json') $result
}
Write-Output "$($result.status): $runRoot"

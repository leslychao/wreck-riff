param(
    [Parameter(Mandatory=$true)][string]$ZipPath,
    [ValidateSet('Smoke','Soak','NormalNew','NormalMigrated','NormalContinue','UiReview')][string]$Mode='Smoke',
    [ValidateRange(60,3600)][int]$Seconds=600,
    [ValidateRange(60,7200)][int]$TimeoutSeconds=2400,
    [string]$ProfileDirectory,
    [ValidateSet('640x480','720p','1280x720','1080p','1920x1080','2560x1440','3440x1440','3840x1080','3840x2160')][string]$Resolution='1280x720',
    [ValidateRange(.8,1.5)][double]$UiScale=1,
    [switch]$Windowed
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'release-evidence.ps1')
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if($Mode -ne 'UiReview' -and ($PSBoundParameters.ContainsKey('Resolution') -or $PSBoundParameters.ContainsKey('UiScale') -or $PSBoundParameters.ContainsKey('Windowed'))){throw 'Resolution, UiScale and Windowed are only valid for UiReview.'}
$uiRequest=if($Mode -eq 'UiReview'){Get-ReleaseUiReviewRequest $Resolution $UiScale $Windowed.IsPresent}else{$null}
$reportName="windows-package-$Mode.json"
if($null -ne $uiRequest) {
    $scaleName=$UiScale.ToString('0.########',[Globalization.CultureInfo]::InvariantCulture).Replace('.','p')
    $reportName="windows-package-UiReview-$($uiRequest.width)x$($uiRequest.height)-scale$scaleName-$($uiRequest.windowMode)-$([Guid]::NewGuid().ToString('N')).json"
}
# Escape literals keep Windows PowerShell 5.1 compatible with UTF-8 without BOM.
$cyrillic=([char]0x041F).ToString()+[char]0x0440+[char]0x043E+[char]0x0432+[char]0x0435+[char]0x0440+[char]0x043A+[char]0x0430
$runRoot=Join-Path $root ("build/package-tests/$cyrillic final ZIP "+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($runRoot) | Out-Null
$package=Expand-ReleasePackage $ZipPath (Join-Path $runRoot 'extracted')
$identity=$package.identity;$image=$package.image
$result=[ordered]@{schemaVersion=3;status='FAIL';mode=$Mode;version=$identity.version;sourceSha256=$identity.sourceSha256;mainJarSha256=$identity.mainJarSha256;
    verificationInputsSha256=$identity.verificationInputsSha256;zipSha256=$identity.zipSha256;zipPath=$identity.zipPath;imagePath=$image;runRoot=$runRoot;
    installationWriteDenied=$false;noExternalJavaOnPath=$true;javaHomePointedToMissingDirectory=$true}
if($null -ne $uiRequest){$result.requestedFramebuffer=$uiRequest;$result.releaseEligible=$false;$result.ownerAcceptance='NOT_GRANTED';$result.scenarioStatus='FAIL'}
$originalAcl=$null
try {
    $profile=Join-Path $runRoot 'user-data/WreckRiff'
    [IO.Directory]::CreateDirectory($profile) | Out-Null
    $result.profileDirectory=$profile;$result.profileInputs=@()
    if($Mode -in @('NormalMigrated','NormalContinue')) {
        if([string]::IsNullOrWhiteSpace($ProfileDirectory)){throw "$Mode needs an existing explicit -ProfileDirectory; it is copied, never modified."}
        $beforeDirectory=Join-Path $runRoot 'profile-inputs';[IO.Directory]::CreateDirectory($beforeDirectory) | Out-Null
        foreach($name in @('settings.json','stats.json')) {
            $inputFile=Join-Path $ProfileDirectory $name
            if(!(Test-Path -LiteralPath $inputFile -PathType Leaf)){throw "Profile lacks $name"}
            $preservedInput=Join-Path $beforeDirectory $name
            Copy-Item -LiteralPath $inputFile -Destination $preservedInput
            $result.profileInputs+=Get-ReleaseArtifact $preservedInput
            Copy-Item -LiteralPath $preservedInput -Destination (Join-Path $profile $name)
        }
        $result.profileBefore=[ordered]@{settings=(Read-ReleaseJson (Join-Path $profile 'settings.json'));stats=(Read-ReleaseJson (Join-Path $profile 'stats.json'))}
    } elseif($ProfileDirectory){throw '-ProfileDirectory is only valid for NormalMigrated or NormalContinue.'}
    Assert-ReleaseImage $identity.zipPath $image
    $originalAcl=Get-Acl -LiteralPath $image;$restrictedAcl=Get-Acl -LiteralPath $image
    $userSid=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $deny=[Security.AccessControl.FileSystemAccessRule]::new($userSid,[Security.AccessControl.FileSystemRights]::Write,
        [Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit',[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Deny)
    $restrictedAcl.AddAccessRule($deny);Set-Acl -LiteralPath $image -AclObject $restrictedAcl
    try {[IO.File]::WriteAllText((Join-Path $image 'must-not-be-writable.tmp'),'probe')}
    catch [UnauthorizedAccessException] {$result.installationWriteDenied=$true}
    if(!$result.installationWriteDenied){throw 'Extracted installation directory still allows writes.'}
    $arguments=@();$diagnosticMode='normal'
    if($Mode -eq 'Smoke') {$arguments=@('--dev','--seed=42','--ai-player',"--smoke-seconds=$Seconds");$diagnosticMode='graphics-smoke'}
    elseif($Mode -eq 'Soak') {$arguments=@('--dev','--seed=42','--ai-player',"--soak-seconds=$Seconds");$diagnosticMode='soak'}
    elseif($Mode -eq 'UiReview') {
        $scaleArgument=$UiScale.ToString('R',[Globalization.CultureInfo]::InvariantCulture)
        $arguments=@('--dev','--seed=42','--ui-review',"--resolution=$($uiRequest.width)x$($uiRequest.height)","--ui-scale=$scaleArgument");$diagnosticMode='ui-review'
        if($Windowed){$arguments+='--windowed'}
    }
    else {Write-Output "Normal launch: complete the $Mode scenario and exit through the game menu; no diagnostic arguments are passed."}
    $result.arguments=$arguments
    $run=Invoke-ReleaseProcess $image $runRoot $arguments $diagnosticMode $TimeoutSeconds $identity
    $result.gamePid=$run.gamePid;$result.launcherPid=$run.launcherPid;$result.processStartTimeUtc=$run.processStartTimeUtc
    $result.startedAtUtc=$run.startedAtUtc;$result.completedAtUtc=$run.completedAtUtc;$result.launcherExitCode=$run.exitCode
    $result.diagnostic=Get-ReleaseArtifact (Join-Path $runRoot 'diagnostic-result.json')
    $result.memory=Get-ReleaseArtifact (Join-Path $runRoot 'memory.json')
    $result.stdout=Get-ReleaseArtifact (Join-Path $runRoot 'stdout.log');$result.stderr=Get-ReleaseArtifact (Join-Path $runRoot 'stderr.log')
    if($Mode -eq 'Soak') {
        $result.status='PASS';Assert-ReleaseSoak ([pscustomobject]$result) $run.diagnostic $run.memory
    } elseif($Mode -eq 'Smoke' -and $run.diagnostic.status -ne 'PASS'){throw 'Executable scenario did not report successful completion.'}
    if($Mode -eq 'Smoke') {
        if($run.diagnostic.menuCleanupVerified -ne $true -or $run.diagnostic.pauseTickPreserved -ne $true -or $run.diagnostic.retryCycles.Count -lt 20){throw 'Smoke omitted pause, cleanup or twenty retry checks.'}
    }
    if($Mode.StartsWith('Normal')) {
        Assert-ReleaseNormal $run.diagnostic
        if($run.diagnostic.dev -ne $false -or $arguments.Count -ne 0){throw 'Normal launch was not a real launch without --dev.'}
        $result.profileOutputs=@()
        foreach($name in @('settings.json','stats.json')) {
            $path=Join-Path $profile $name
            if(!(Test-Path -LiteralPath $path)){throw "Normal scenario did not persist $name"}
            $result.profileOutputs+=Get-ReleaseArtifact $path
        }
        $result.profileAfter=[ordered]@{settings=(Read-ReleaseJson (Join-Path $profile 'settings.json'));stats=(Read-ReleaseJson (Join-Path $profile 'stats.json'))}
        $result.scenarioAcceptance='REQUIRES_REVIEW'
    }
    if($Mode -eq 'UiReview') {
        $manifest=Join-Path ([IO.Path]::GetDirectoryName($run.reportPath)) 'ui-review-manifest.json'
        $result.uiReview=Get-ReleaseUiReviewEvidence $manifest $run.diagnostic $uiRequest
        $result.scenarioStatus='PASS';$result.scenarioAcceptance='VISUAL_REVIEW_PENDING'
    }
    Set-Acl -LiteralPath $image -AclObject $originalAcl;$originalAcl=$null
    Assert-ReleaseImage $identity.zipPath $image
    if((Get-ReleaseSha256 $identity.zipPath) -ne $identity.zipSha256){throw 'Final ZIP changed during executable verification.'}
    $result.status=if($Mode -eq 'UiReview'){'CAPTURE_COLLECTION_COMPLETE'}else{'PASS'}
} catch {$result.status='FAIL';if($Mode -eq 'UiReview'){$result.scenarioStatus='FAIL'};$result.failure=$_.Exception.Message;throw}
finally {
    if($null -ne $originalAcl){Set-Acl -LiteralPath $image -AclObject $originalAcl}
    Write-ReleaseJson (Join-Path $runRoot 'package-launch-verification.json') $result
    Write-ReleaseJson (Join-Path $root "build/reports/$reportName") $result
}
Write-Output "$($result.status): $Mode from the final ZIP; $runRoot"

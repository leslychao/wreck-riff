<#
.SYNOPSIS
Validates evidence for one immutable Windows ZIP. Missing evidence keeps it a candidate.
.DESCRIPTION
First run prepare-windows-release.ps1 on a stable workspace. Then use -RunAutomated
to run real EXE Smoke, six 600-second benchmarks and a 1800-second soak sequentially.
Run test-windows-package.ps1 separately with NormalNew, NormalMigrated and NormalContinue
and actually perform those scenarios in the game. -AcceptancePath is a human-authored
JSON review, not a switch that grants owner/controller/other-Windows acceptance.
See tools/fixtures/release-acceptance.example.json for the deliberately pending format.
This script never publishes or changes the ZIP, and never generates approval entries.
#>
param(
    [Parameter(Mandatory=$true)][string]$ZipPath,
    [string]$BuildVerificationPath,
    [string]$ReportsDirectory,
    [string]$AcceptancePath,
    [switch]$RunAutomated
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'release-evidence.ps1')
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if(!$ReportsDirectory){$ReportsDirectory=Join-Path $root 'build/reports'}
if(!$BuildVerificationPath){$BuildVerificationPath=Join-Path $ReportsDirectory 'release-build-verification.json'}
$identity=Get-ReleasePackageIdentity $ZipPath
$arenas=@('dead-air-yard','construction_17','neon_zero','euphoria_park','ash_necropolis','doomsday_arena')
if($RunAutomated) {
    if([IO.Path]::GetFullPath($ReportsDirectory) -ne [IO.Path]::GetFullPath((Join-Path $root 'build/reports'))){throw '-RunAutomated writes the standard build/reports directory.'}
    & (Join-Path $PSScriptRoot 'test-windows-package.ps1') -ZipPath $identity.zipPath -Mode Smoke -Seconds 600 -TimeoutSeconds 900
    foreach($arena in $arenas){& (Join-Path $PSScriptRoot 'test-windows-benchmark.ps1') -ZipPath $identity.zipPath -Arena $arena -Seconds 600}
    & (Join-Path $PSScriptRoot 'test-windows-package.ps1') -ZipPath $identity.zipPath -Mode Soak -Seconds 1800 -TimeoutSeconds 3600
}
$technicalErrors=[Collections.Generic.List[string]]::new();$pending=[Collections.Generic.List[string]]::new()
$artifacts=[Collections.Generic.List[object]]::new();$accepted=[Collections.Generic.List[string]]::new()
$script:sourceMaterials=$null
function Test-Evidence([string]$Name,[scriptblock]$Check) {
    try {& $Check} catch {$technicalErrors.Add($Name+': '+$_.Exception.Message)}
}
function Read-VerifiedLaunch([string]$Path,[string]$Mode) {
    $report=Read-ReleaseJson $Path
    Assert-ReleaseIdentity $report $identity
    if($report.status -ne 'PASS' -or $report.launcherExitCode -ne 0){throw 'Executable verification did not pass.'}
    if([DateTime]$report.startedAtUtc -lt [DateTime]$identity.packagedAtUtc){throw 'Executable report predates this package.'}
    foreach($field in @('diagnostic','memory','stdout','stderr')){Assert-ReleaseArtifact $report.$field}
    Assert-ReleaseProcessLogs @($report.stdout.path,$report.stderr.path)
    $diagnostic=Read-ReleaseJson $report.diagnostic.path;$memory=Read-ReleaseJson $report.memory.path
    Assert-ReleaseImage $identity.zipPath $report.imagePath
    Assert-ReleaseDiagnostic $diagnostic $identity $report.imagePath $Mode
    if($report.gamePid -ne $diagnostic.pid -or $memory.pid -ne $diagnostic.pid -or $memory.processStartTimeUtc -ne $report.processStartTimeUtc -or $memory.processExited -ne $true){throw 'Executable process evidence mismatch.'}
    $artifacts.Add((Get-ReleaseArtifact $Path))
    return [pscustomobject]@{report=$report;diagnostic=$diagnostic;memory=$memory}
}
Test-Evidence 'packaged source materials and notices' {$script:sourceMaterials=Assert-ReleaseSourceDistribution $identity.zipPath}
Test-Evidence 'clean build' {
    $build=Read-ReleaseJson $BuildVerificationPath
    if($build.status -ne 'PASS' -or $build.exitCode -ne 0 -or $build.sourceSha256 -ne $identity.sourceSha256 -or $build.verificationInputsSha256 -ne $identity.verificationInputsSha256){throw 'Clean checks do not identify this package source and verification inputs.'}
    if([DateTime]$build.startedAtUtc -ge [DateTime]$build.completedAtUtc -or [DateTime]$build.completedAtUtc -gt [DateTime]$identity.packagedAtUtc){throw 'Clean-check/package chronology is invalid.'}
    foreach($task in @('clean','test','physicsTest','verifyAssets')){if($build.command -notcontains $task){throw "Missing clean build task $task"}}
    Assert-ReleaseArtifact $build.buildInit
    if($build.command -notcontains '-I' -or $build.command -notcontains $build.buildInit.path -or $build.packageCommand -notcontains $build.buildInit.path){throw 'Clean checks and packaging did not share the isolated build configuration.'}
    if($build.jdkRelease -notmatch 'JAVA_VERSION="21\.' -or $build.jdkRelease -notmatch 'IMPLEMENTOR="Microsoft"'){throw 'Clean checks did not use Microsoft JDK 21.'}
    Assert-ReleaseArtifact $build.log
    foreach($suiteName in @('test','physicsTest')) {
        $suite=$build.suites.$suiteName;$count=0
        if($suite.artifacts.Count -eq 0){throw "Missing $suiteName XML artifacts"}
        foreach($artifact in $suite.artifacts){Assert-ReleaseArtifact $artifact;$count+=Assert-ReleaseTestXml $artifact.path ([DateTime]$build.startedAtUtc)}
        if($count -ne $suite.tests){throw "Test count changed for $suiteName"}
    }
    Assert-ReleaseArtifact $build.assets
    Assert-ReleaseArtifact $build.sourceDistribution
    if($null -eq $script:sourceMaterials -or $build.sourceDistribution.sha256 -ne $script:sourceMaterials.reportSha256){throw 'Clean source/notice verification does not identify the packaged materials.'}
    $assets=Read-ReleaseJson $build.assets.path
    if($assets.status -ne 'TECHNICAL_PASS'){throw 'Asset verification did not pass.'}
    $artifacts.Add((Get-ReleaseArtifact $BuildVerificationPath))
}
Test-Evidence 'packaged smoke' {
    $run=Read-VerifiedLaunch (Join-Path $ReportsDirectory 'windows-package-Smoke.json') 'graphics-smoke'
    if($run.report.installationWriteDenied -ne $true -or $run.report.noExternalJavaOnPath -ne $true -or $run.report.javaHomePointedToMissingDirectory -ne $true){throw 'Installation isolation was not exercised.'}
    if($run.diagnostic.status -ne 'PASS' -or $run.diagnostic.menuCleanupVerified -ne $true -or $run.diagnostic.pauseTickPreserved -ne $true -or $run.diagnostic.retryCycles.Count -lt 20){throw 'Smoke omitted required lifecycle checks.'}
}
foreach($arena in $arenas) {
    Test-Evidence "benchmark $arena" {
        $run=Read-VerifiedLaunch (Join-Path $ReportsDirectory "windows-benchmark-$arena.json") 'benchmark'
        Assert-ReleaseBenchmark $run.report $run.diagnostic $run.memory $arena
    }
}
Test-Evidence '30-minute soak' {
    $run=Read-VerifiedLaunch (Join-Path $ReportsDirectory 'windows-package-Soak.json') 'soak'
    Assert-ReleaseSoak $run.report $run.diagnostic $run.memory
}
foreach($mode in @('NormalNew','NormalMigrated','NormalContinue')) {
    Test-Evidence $mode {
        $run=Read-VerifiedLaunch (Join-Path $ReportsDirectory "windows-package-$mode.json") 'normal'
        Assert-ReleaseNormal $run.diagnostic
        if($run.report.mode -ne $mode -or $run.report.arguments.Count -ne 0){throw 'Normal launch requires no --dev and no diagnostic arguments.'}
        if($run.report.installationWriteDenied -ne $true -or $run.report.noExternalJavaOnPath -ne $true){throw 'Normal launch omitted installation isolation.'}
        if($run.report.profileOutputs.Count -ne 2){throw 'Settings and statistics persistence evidence is absent.'}
        foreach($artifact in $run.report.profileOutputs){Assert-ReleaseArtifact $artifact}
        if($mode -ne 'NormalNew') {
            if($run.report.profileInputs.Count -ne 2){throw 'Original profile inputs are absent.'}
            foreach($artifact in $run.report.profileInputs){Assert-ReleaseArtifact $artifact}
        }
    }
}
$requiredReviews=@('normalNewProfile','normalMigratedProfile','normalCampaignContinue','physicalController','otherWindows','ownerFeel','graphicalUxMatrix','arenaRoutes','distributionLicenses','releaseDocumentation')
if(!$AcceptancePath) {foreach($name in $requiredReviews){$pending.Add($name+': no acceptance file supplied')}}
else {
    try {
        $acceptance=Read-ReleaseJson $AcceptancePath
        Assert-ReleaseIdentity $acceptance $identity
        if($acceptance.schemaVersion -ne 1){throw 'Unknown acceptance schema.'}
        foreach($name in $requiredReviews) {
            try {
                $review=$acceptance.checks.$name
                if($review.status -ne 'ACCEPTED' -or [string]::IsNullOrWhiteSpace($review.reviewedBy) -or [string]::IsNullOrWhiteSpace($review.summary)){throw 'No explicit documented human acceptance.'}
                $at=[DateTime]$review.reviewedAtUtc
                if($at -lt [DateTime]$identity.packagedAtUtc -or $at -gt [DateTime]::UtcNow.AddMinutes(5)){throw 'Acceptance date does not match this candidate.'}
                if($review.artifacts.Count -eq 0){throw 'No supporting evidence artifacts.'}
                foreach($artifact in $review.artifacts){Assert-ReleaseArtifact $artifact}
                $accepted.Add($name)
            } catch {$pending.Add($name+': '+$_.Exception.Message)}
        }
        $artifacts.Add((Get-ReleaseArtifact $AcceptancePath))
    } catch {foreach($name in $requiredReviews){$pending.Add($name+': invalid acceptance identity/schema: '+$_.Exception.Message)}}
}
if((Get-ReleaseSha256 $identity.zipPath) -ne $identity.zipSha256){$technicalErrors.Add('ZIP changed while evidence was checked.')}
$ready=$technicalErrors.Count -eq 0 -and $pending.Count -eq 0
$result=[ordered]@{schemaVersion=1;status=$(if($ready){'RELEASE_READY'}else{'RELEASE_CANDIDATE'});technicalStatus=$(if($technicalErrors.Count -eq 0){'PASS'}else{'FAIL'});
    version=$identity.version;sourceSha256=$identity.sourceSha256;mainJarSha256=$identity.mainJarSha256;verificationInputsSha256=$identity.verificationInputsSha256;zipSha256=$identity.zipSha256;zipPath=$identity.zipPath;
    checkedAtUtc=[DateTime]::UtcNow.ToString('o');technicalFailures=$technicalErrors.ToArray();pendingAcceptance=$pending.ToArray();acceptedReviews=$accepted.ToArray();artifacts=$artifacts.ToArray();published=$false}
$output=Join-Path $ReportsDirectory ('release-gate-'+$identity.zipSha256+'.json')
Write-ReleaseJson $output $result
Write-Output "$($result.status): technical failures=$($technicalErrors.Count); pending acceptance=$($pending.Count); $output"
if(!$ready){throw 'Release remains a candidate; inspect technicalFailures and pendingAcceptance in the gate report.'}

param(
    [Parameter(Mandatory=$true)][string]$ZipPath,
    [ValidateRange(60,3600)][int]$Seconds=600,
    [ValidateSet('dead-air-yard','construction_17','neon_zero','euphoria_park','ash_necropolis','doomsday_arena')]
    [string]$Arena='construction_17'
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'release-evidence.ps1')
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runRoot=Join-Path $root ('build/benchmark-runs/'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($runRoot) | Out-Null
$package=Expand-ReleasePackage $ZipPath (Join-Path $runRoot 'extracted')
$identity=$package.identity
$result=[ordered]@{schemaVersion=3;status='FAIL';version=$identity.version;sourceSha256=$identity.sourceSha256;mainJarSha256=$identity.mainJarSha256;
    verificationInputsSha256=$identity.verificationInputsSha256;zipSha256=$identity.zipSha256;zipPath=$identity.zipPath;arenaId=$Arena;requestedSeconds=$Seconds;runRoot=$runRoot}
try {
    Assert-ReleaseImage $identity.zipPath $package.image
    $run=Invoke-ReleaseProcess $package.image $runRoot @('--dev','--seed=42','--ai-player',"--arena=$Arena",'--resolution=1080p',"--benchmark-seconds=$Seconds") 'benchmark' (60+$Seconds+[Math]::Max(120,[Math]::Floor($Seconds/2))) $identity
    $result.gamePid=$run.gamePid;$result.launcherPid=$run.launcherPid;$result.processStartTimeUtc=$run.processStartTimeUtc
    $result.startedAtUtc=$run.startedAtUtc;$result.completedAtUtc=$run.completedAtUtc;$result.launcherExitCode=$run.exitCode
    $result.imagePath=$package.image
    Assert-ReleaseImage $identity.zipPath $package.image
    if((Get-ReleaseSha256 $identity.zipPath) -ne $identity.zipSha256){throw 'Final ZIP changed during benchmark.'}
    $result.diagnostic=Get-ReleaseArtifact (Join-Path $runRoot 'diagnostic-result.json')
    $result.memory=Get-ReleaseArtifact (Join-Path $runRoot 'memory.json')
    $result.stdout=Get-ReleaseArtifact (Join-Path $runRoot 'stdout.log');$result.stderr=Get-ReleaseArtifact (Join-Path $runRoot 'stderr.log')
    if($Seconds -lt 600) {
        if($run.diagnostic.status -ne 'DIAGNOSTIC_COMPLETE'){throw 'Short diagnostic did not complete.'}
        $result.status='DIAGNOSTIC_COMPLETE'
    } else {
        $result.status='PASS'
        Assert-ReleaseBenchmark ([pscustomobject]$result) $run.diagnostic $run.memory $Arena
    }
} catch {$result.status='FAIL';$result.failure=$_.Exception.Message;throw}
finally {
    Write-ReleaseJson (Join-Path $runRoot 'benchmark-verification.json') $result
    Write-ReleaseJson (Join-Path $root "build/reports/windows-benchmark-$Arena.json") $result
}
Write-Output "$($result.status): final ZIP benchmark ($Arena); $runRoot"

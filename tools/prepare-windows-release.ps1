param([string]$JdkHome='C:\Users\vitalii\.jdks\ms-21.0.11',[switch]$VerifyOnly,[switch]$PlanOnly)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'release-evidence.ps1')
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$jdk=(Resolve-Path -LiteralPath $JdkHome).ProviderPath
$release=[IO.File]::ReadAllText((Join-Path $jdk 'release'))
if($release -notmatch 'JAVA_VERSION="21\.' -or $release -notmatch 'IMPLEMENTOR="Microsoft"'){throw 'Microsoft JDK 21 is required.'}
$before=Get-ReleaseInputHash $root
$source=Get-ReleaseInputHash $root -RuntimeOnly
$started=[DateTime]::UtcNow
$runId=$started.ToString('yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N')
$releaseBuildRoot=[IO.Path]::GetFullPath((Join-Path $root "build/release-output/$runId"))
$controlRoot=[IO.Path]::GetFullPath((Join-Path $root "build/release-controls/$runId"))
$workspaceBuild=[IO.Path]::GetFullPath((Join-Path $root 'build'))
foreach($candidate in @($releaseBuildRoot,$controlRoot)) {
    if(!$candidate.StartsWith($workspaceBuild+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Release build root escaped workspace build.'}
    if(Test-Path -LiteralPath $candidate){throw 'Release preparation requires new isolated directories.'}
    $cursor=Split-Path -Parent $candidate
    while($cursor.Length -ge $workspaceBuild.Length) {
        if((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Release build ancestor is a reparse point.'}
        $cursor=Split-Path -Parent $cursor
    }
}
[IO.Directory]::CreateDirectory($controlRoot) | Out-Null
$initPath=Join-Path $controlRoot 'release-build.init.gradle'
$gradlePath=$releaseBuildRoot.Replace('\','/').Replace("'","\'")
$initText="beforeProject { project ->`n    project.layout.buildDirectory.set(project.file('$gradlePath'))`n}`n"
[IO.File]::WriteAllText($initPath,$initText,[Text.UTF8Encoding]::new($false))
$gradleOptions=@('-I',$initPath,'--project-cache-dir',(Join-Path $controlRoot 'project-cache'))
# clean targets only releaseBuildRoot. Controls/logs and all older build evidence survive.
$log=Join-Path $controlRoot 'release-clean-build.log'
$packageLog=Join-Path $controlRoot 'release-package.log'
$savedJava=$env:JAVA_HOME;$savedPath=$env:PATH;$savedGradle=$env:GRADLE_USER_HOME
$result=[ordered]@{schemaVersion=1;status='FAIL';startedAtUtc=$started.ToString('o');sourceSha256=$source;
    verificationInputsSha256=$before;jdkHome=$jdk;jdkRelease=$release;buildRoot=$releaseBuildRoot;
    buildInit=(Get-ReleaseArtifact $initPath);command=($gradleOptions+@('clean','test','physicsTest','verifyAssets','--offline','--no-daemon','--console=plain'));
    packageCommand=($gradleOptions+@('packageWindows','--offline','--no-daemon','--console=plain'))}
if($PlanOnly) {
    $result.status='PLANNED';$planPath=Join-Path $controlRoot 'release-build-plan.json'
    Write-ReleaseJson $planPath $result
    Write-Output $planPath
    return
}
Push-Location -LiteralPath $root
try {
    $env:JAVA_HOME=$jdk;$env:PATH="$jdk\bin;$savedPath";$env:GRADLE_USER_HOME=Join-Path $root '.gradle-user'
    $result.exitCode=Invoke-ReleaseNativeCommand '.\gradlew.bat' $result.command $log
    if($before -ne (Get-ReleaseInputHash $root)){throw 'Verification inputs changed during clean checks.'}
    $suites=[ordered]@{}
    foreach($suite in @('test','physicsTest')) {
        $files=@(Get-ChildItem -LiteralPath (Join-Path $releaseBuildRoot "test-results/$suite") -Filter 'TEST-*.xml' -File)
        if($files.Count -eq 0){throw "No $suite results were produced."}
        $tests=0;$artifacts=@()
        foreach($file in $files){$tests+=Assert-ReleaseTestXml $file.FullName $started;$artifacts+=Get-ReleaseArtifact $file.FullName}
        $suites[$suite]=[ordered]@{tests=$tests;artifacts=$artifacts}
    }
    $assetsPath=Join-Path $releaseBuildRoot 'reports/assets/verification.json'
    if((Get-Item -LiteralPath $assetsPath).LastWriteTimeUtc -lt $started -or (Read-ReleaseJson $assetsPath).status -ne 'TECHNICAL_PASS'){throw 'Missing fresh verifyAssets success.'}
    $sourceMaterialsPath=Join-Path $releaseBuildRoot 'reports/assets/source-distribution.json'
    if((Get-Item -LiteralPath $sourceMaterialsPath).LastWriteTimeUtc -lt $started -or (Read-ReleaseJson $sourceMaterialsPath).status -ne 'SOURCE_AND_NOTICE_MATERIALS_VERIFIED'){throw 'Missing fresh corresponding-source/notice verification.'}
    $result.suites=$suites;$result.assets=Get-ReleaseArtifact $assetsPath;$result.sourceDistribution=Get-ReleaseArtifact $sourceMaterialsPath;$result.status='PASS'
} catch {
    $result.status='FAIL';$result.failure=$_.Exception.Message
    if($_.Exception.Data.Contains('exitCode')){$result.exitCode=$_.Exception.Data['exitCode']}
    throw
}
finally {
    if(Test-Path -LiteralPath $log){$result.log=Get-ReleaseArtifact $log}
    $result.completedAtUtc=[DateTime]::UtcNow.ToString('o')
    Write-ReleaseJson (Join-Path $root 'build/reports/release-build-verification.json') $result
    $env:JAVA_HOME=$savedJava;$env:PATH=$savedPath;$env:GRADLE_USER_HOME=$savedGradle
    Pop-Location
}
if(!$VerifyOnly) {
    Push-Location -LiteralPath $root
    try {
        $env:JAVA_HOME=$jdk;$env:PATH="$jdk\bin;$savedPath";$env:GRADLE_USER_HOME=Join-Path $root '.gradle-user'
        if($before -ne (Get-ReleaseInputHash $root)){throw 'Verification inputs changed before packaging.'}
        $result.packageExitCode=Invoke-ReleaseNativeCommand '.\gradlew.bat' $result.packageCommand $packageLog
        if($before -ne (Get-ReleaseInputHash $root)){throw 'Verification inputs changed during packaging.'}
    } catch {
        if($_.Exception.Data.Contains('exitCode')){$result.packageExitCode=$_.Exception.Data['exitCode']}
        throw
    } finally {
        if(Test-Path -LiteralPath $packageLog){$result.packageLog=Get-ReleaseArtifact $packageLog}
        Write-ReleaseJson (Join-Path $root 'build/reports/release-build-verification.json') $result
        $env:JAVA_HOME=$savedJava;$env:PATH=$savedPath;$env:GRADLE_USER_HOME=$savedGradle;Pop-Location
    }
}
Write-Output "Clean checks recorded for $releaseBuildRoot. Package remains a release candidate until test-windows-release.ps1 accepts all evidence."

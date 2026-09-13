param([string]$TexconvExecutable)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$jdk='C:\Users\vitalii\.jdks\ms-21.0.11'
if(!$TexconvExecutable){$TexconvExecutable=Join-Path $projectRoot 'build/asset-tooling/DirectXTex/texconv.exe'}
if(!(Test-Path -LiteralPath $TexconvExecutable -PathType Leaf)){throw 'Prepare the pinned local DirectXTex tool first; this command never downloads it.'}
$taskOutput=Join-Path $projectRoot 'build/environment-asset-preparation'
[IO.Directory]::CreateDirectory($taskOutput)|Out-Null
$taskInit=Join-Path $taskOutput 'isolated.init.gradle'
[IO.File]::WriteAllText($taskInit,"gradle.beforeProject { p -> if (p == p.rootProject) p.layout.buildDirectory.set(p.file('build/environment-asset-preparation/gradle')) }`n",[Text.UTF8Encoding]::new($false))
$previousJava=$env:JAVA_HOME;$previousGradle=$env:GRADLE_USER_HOME
Push-Location -LiteralPath $projectRoot
try {
    $env:JAVA_HOME=$jdk;$env:GRADLE_USER_HOME=Join-Path $projectRoot '.gradle-user'
    # Explicit offline preparation only. No dependency from assemble, test or the game.
    foreach($task in @('prepareEnvironmentLighting','prepareEnvironmentDiffuse','prepareArenaModels','verifyAssets')) {
        & ./gradlew.bat --offline --no-daemon --console=plain --project-cache-dir (Join-Path $taskOutput 'project-cache') -I $taskInit "-PtexconvExecutable=$TexconvExecutable" $task
        if($LASTEXITCODE -ne 0){throw "Offline environment preparation failed: $task"}
    }
} finally {Pop-Location;$env:JAVA_HOME=$previousJava;$env:GRADLE_USER_HOME=$previousGradle}

param(
    [string]$BlenderExecutable,
    [switch]$Bake,
    [switch]$Reexport,
    [switch]$VerifyRepeat
)
$ErrorActionPreference='Stop'
if($Bake -and $Reexport){throw 'Choose -Bake (regenerate recipe sources) or -Reexport (preserve saved Blender edits).'}
if($VerifyRepeat -and !$Bake -and !$Reexport){throw 'Repeat verification requires explicit -Bake or -Reexport; no editable source is overwritten implicitly.'}
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskJdk='C:\Users\vitalii\.jdks\ms-21.0.11'
if(!$BlenderExecutable){$BlenderExecutable=Join-Path $projectRoot 'build/tools/blender-4.5.9-windows-x64/blender.exe'}
$BlenderExecutable=[IO.Path]::GetFullPath($BlenderExecutable)
$taskOutput=Join-Path $projectRoot 'build/combat-asset-preparation'
[IO.Directory]::CreateDirectory($taskOutput)|Out-Null
$taskInit=Join-Path $taskOutput 'isolated.init.gradle'
[IO.File]::WriteAllText($taskInit,"gradle.beforeProject { p -> if (p == p.rootProject) p.layout.buildDirectory.set(p.file('build/combat-asset-preparation/gradle')) }`n",[Text.UTF8Encoding]::new($false))
function Invoke-PreparationGradle([string[]]$Tasks) {
    & (Join-Path $projectRoot 'gradlew.bat') --offline --no-daemon --console=plain --project-cache-dir (Join-Path $taskOutput 'project-cache') -I $taskInit @Tasks
    if($LASTEXITCODE -ne 0){throw "Offline Gradle preparation failed: $Tasks"}
}
function Assert-Blender {
    if(!(Test-Path -LiteralPath $BlenderExecutable -PathType Leaf)){throw 'Blender is missing. Install Blender 4.5.9 LTS locally before explicit preparation; Gradle will not download it.'}
    $version=& $BlenderExecutable --version
    if($LASTEXITCODE -ne 0 -or $version[0] -notmatch '^Blender 4\.5\.9'){throw 'The authored asset contract requires Blender 4.5.9 LTS.'}
}
function Bake-Sources {
    Assert-Blender
    foreach($script in @('src/tools/author_vehicle_models.py','src/tools/author_combat_vfx.py')) {
        & $BlenderExecutable --background --python (Join-Path $projectRoot $script)
        if($LASTEXITCODE -ne 0){throw "Blender preparation failed: $script"}
    }
}
function Export-SavedSources {
    Assert-Blender
    foreach($profile in @('rivet','grinder','spark','boss_foreman','boss_prefect','boss_emcee')) {
        & $BlenderExecutable --background --python (Join-Path $projectRoot 'src/tools/author_vehicle_models.py') -- --reexport $profile
        if($LASTEXITCODE -ne 0){throw "Saved Blender export failed: $profile"}
    }
}
function Normalized-Exports {
    $result=[ordered]@{}
    # Editable .blend files contain session metadata. Compare exported geometry, UVs,
    # all morph/regional data, runtime J3O and PNG bytes; provenance is separately verified.
    foreach($directory in @('src/tools/assets/vehicles','src/main/resources/models/vehicles','src/main/resources/textures/vehicles','src/main/resources/textures/vfx','src/main/resources/vfx')) {
        Get-ChildItem -LiteralPath (Join-Path $projectRoot $directory) -File -Recurse | Where-Object {
            $_.Name -match '\.(glb|j3o|png)$' -or $_.Name -in @('mesh.json.gz','regions.json.gz','recipes.json')
        } | Sort-Object FullName | ForEach-Object {
            $result[[IO.Path]::GetRelativePath($projectRoot,$_.FullName).Replace('\','/')]=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
        }
    }
    return $result
}
$previousJava=$env:JAVA_HOME;$previousGradle=$env:GRADLE_USER_HOME
Push-Location $projectRoot
try {
    $env:JAVA_HOME=$taskJdk;$env:GRADLE_USER_HOME=Join-Path $projectRoot '.gradle-user'
    if($Bake){Bake-Sources}
    elseif($Reexport){Export-SavedSources}
    Invoke-PreparationGradle @('prepareVehicleModels')
    $first=Normalized-Exports
    if($VerifyRepeat) {
        if($Bake){Bake-Sources}else{Export-SavedSources}
        Invoke-PreparationGradle @('prepareVehicleModels')
        $second=Normalized-Exports
        $changed=@($first.Keys|Where-Object {!$second.Contains($_) -or $first[$_] -ne $second[$_]})
        $added=@($second.Keys|Where-Object {!$first.Contains($_)})
        $report=[ordered]@{schemaVersion=1;status=$(if($changed.Count -eq 0 -and $added.Count -eq 0){'PASS'}else{'FAIL'});scope=$(if($Bake){'Recipe regeneration of vehicles and VFX'}else{'Export of saved editable vehicle Blender sources; existing VFX unchanged'});tool='Blender 4.5.9 LTS / Microsoft JDK 21';comparedExports=$first.Count;changed=$changed;added=$added;hashes=$second}
        [IO.File]::WriteAllText((Join-Path $taskOutput 'reproducibility.json'),($report|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
        if($report.status -ne 'PASS'){throw 'Repeated preparation changed normalized exports. See reproducibility.json.'}
    }
    Invoke-PreparationGradle @('verifyAssets')
} finally {
    Pop-Location
    $env:JAVA_HOME=$previousJava;$env:GRADLE_USER_HOME=$previousGradle
}

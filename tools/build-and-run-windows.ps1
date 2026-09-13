$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

Push-Location -LiteralPath $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') packageWindows --offline --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    $launcher = Join-Path $projectRoot 'build/distributions/WreckRiff/WreckRiff.exe'
    if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
        throw 'Packaging succeeded, but WreckRiff.exe was not found.'
    }

    $game = Start-Process -FilePath $launcher -WorkingDirectory (Split-Path -Parent $launcher) -WindowStyle Normal -Wait -PassThru
    exit $game.ExitCode
}
finally {
    Pop-Location
}

param([ValidateRange(60,600)][int]$TimeoutSeconds=180)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$buildRoot=Join-Path $projectRoot 'build'
$source=Join-Path $buildRoot 'distributions/WreckRiff'
# Unique local copy preserves the deliverable and any previous diagnostic evidence.
$testRoot=Join-Path $buildRoot ('package-tests/Проверка пакета '+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($testRoot) | Out-Null
Copy-Item -LiteralPath $source -Destination (Join-Path $testRoot 'Wreck Riff') -Recurse
$image=Join-Path $testRoot 'Wreck Riff'
$originalAcl=Get-Acl -LiteralPath $image
$restrictedAcl=Get-Acl -LiteralPath $image
$identity=[Security.Principal.WindowsIdentity]::GetCurrent().User
$deny=[Security.AccessControl.FileSystemAccessRule]::new($identity,
    [Security.AccessControl.FileSystemRights]::Write,
    [Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit',
    [Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Deny)
$restrictedAcl.AddAccessRule($deny)
$savedJava=$env:JAVA_HOME
$savedPath=$env:PATH
$process=$null
$started=[DateTime]::UtcNow
$writeDenied=$false
$exitCode=$null
try {
    Set-Acl -LiteralPath $image -AclObject $restrictedAcl
    try { [IO.File]::WriteAllText((Join-Path $image 'must-not-be-writable.tmp'),'probe') }
    catch [UnauthorizedAccessException] { $writeDenied=$true }
    if (-not $writeDenied) { throw 'The test installation directory still permits writes.' }
    $env:JAVA_HOME=Join-Path $testRoot 'Java is not installed'
    $env:PATH="$env:SystemRoot\System32;$env:SystemRoot"
    $process=Start-Process -FilePath (Join-Path $image 'WreckRiff.exe') -WorkingDirectory $image -WindowStyle Hidden -PassThru `
        -ArgumentList @('--dev','--seed=42','--smoke-seconds=120') `
        -RedirectStandardOutput (Join-Path $testRoot 'stdout.log') -RedirectStandardError (Join-Path $testRoot 'stderr.log')
    if (-not $process.WaitForExit($TimeoutSeconds*1000)) { $process.Kill();throw 'Packaged graphical smoke timed out.' }
    $process.Refresh();$exitCode=$process.ExitCode
    if ($exitCode -ne 0) { throw "Packaged executable failed with exit code $exitCode. See $testRoot" }
} finally {
    $env:JAVA_HOME=$savedJava;$env:PATH=$savedPath
    Set-Acl -LiteralPath $image -AclObject $originalAcl
}
$evidence=$null
$diagnostics=[IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'WreckRiff/diagnostics'))
# The jpackage Windows launcher can delegate to another process. Correlate the
# exact report emitted to this launch's redirected stdout, not the launcher PID.
$reportLine=Get-Content -LiteralPath (Join-Path $testRoot 'stdout.log') | Where-Object { $_.StartsWith('DIAGNOSTIC_REPORT: ') } | Select-Object -Last 1
if ($null -ne $reportLine) {
    $reportPath=[IO.Path]::GetFullPath($reportLine.Substring('DIAGNOSTIC_REPORT: '.Length).Trim())
    if (-not $reportPath.StartsWith($diagnostics+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Report path escapes the diagnostic directory.' }
    $reportFile=Get-Item -LiteralPath $reportPath
    if ($reportFile.LastWriteTimeUtc -lt $started) { throw 'The reported evidence predates this launch.' }
    $evidence=Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
    Copy-Item -LiteralPath $reportPath -Destination (Join-Path $testRoot 'diagnostic-result.json')
}
if ($null -eq $evidence -or $evidence.status -ne 'PASS') { throw 'The executable exited without a passing diagnostic report.' }
$expectedRuntime=[IO.Path]::GetFullPath((Join-Path $image 'runtime'))
if ([string]::IsNullOrWhiteSpace($evidence.javaHome) -or -not [IO.Path]::GetFullPath($evidence.javaHome).Equals($expectedRuntime,[StringComparison]::OrdinalIgnoreCase)) {
    throw 'The reported JVM home is not this test image bundled runtime.'
}
$result=[ordered]@{
    schemaVersion=1;status='PASS';testPath=$image;exitCode=$exitCode;installationWriteDenied=$writeDenied
    launcherPid=$process.Id;gamePid=$evidence.pid
    noExternalJavaOnPath=$true;javaHomePointedToMissingDirectory=$true;bundledJava=$evidence.jdk
    javaHome=$evidence.javaHome;sourceSha256=$evidence.sourceSha256
    controller='PENDING_MANUAL';freshWindowsInstallation='NOT_AVAILABLE; external Java was isolated for this test'
}
[IO.File]::WriteAllText((Join-Path $buildRoot 'reports/packaged-launch.json'),($result|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
Write-Output "Packaged real-window smoke PASS: $image"

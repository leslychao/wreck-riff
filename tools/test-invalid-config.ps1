$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$buildRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'build'))
$utf8 = [Text.UTF8Encoding]::new($false)
$timeoutSeconds = 45

function Assert-BuildChild([string]$Candidate) {
    $absolute = [IO.Path]::GetFullPath($Candidate)
    $prefix = $buildRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $absolute.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Path escapes workspace build directory: $absolute" }
    $cursor = $absolute
    while ($cursor.Length -ge $projectRoot.Length) {
        if (Test-Path -LiteralPath $cursor) {
            if (((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Path contains a reparse point: $cursor" }
        }
        $cursor = [IO.Path]::GetDirectoryName($cursor)
        if ([string]::IsNullOrEmpty($cursor)) { break }
    }
    return $absolute
}

function Quote-WindowsArgument([string]$Argument) {
    # Start-Process joins ArgumentList. Apply Windows argv quoting explicitly,
    # including backslashes before quotes and at the end of a quoted argument.
    return '"' + [regex]::Replace([regex]::Replace($Argument, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"'
}

function Read-Log([string]$LiteralPath) {
    if (-not (Test-Path -LiteralPath $LiteralPath -PathType Leaf)) { return '' }
    # PowerShell still owns a writer while redirecting the child process output.
    $stream=[IO.FileStream]::new($LiteralPath,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]'ReadWrite,Delete')
    $reader=[IO.StreamReader]::new($stream,$utf8)
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}

$imageRoot = Assert-BuildChild (Join-Path $buildRoot 'distributions/WreckRiff')
$executable = Assert-BuildChild (Join-Path $imageRoot 'WreckRiff.exe')
$runRoot = Assert-BuildChild (Join-Path $buildRoot ('negative-launch-' + [Guid]::NewGuid().ToString('N')))
$configRoot = Assert-BuildChild (Join-Path $runRoot 'config')
$stdoutPath = Assert-BuildChild (Join-Path $runRoot 'stdout.log')
$stderrPath = Assert-BuildChild (Join-Path $runRoot 'stderr.log')
$resultPath = Assert-BuildChild (Join-Path $buildRoot 'reports/negative-launch.json')
if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) { throw 'LOCALAPPDATA is required for the owned diagnostic report.' }
$diagnosticsRoot = [IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'WreckRiff/diagnostics'))
$started = [DateTime]::UtcNow
$ownedProcesses = @{}
$launcher = $null
$evidence = $null
$failure = $null
$timedOut = $false
$result = [ordered]@{ schemaVersion = 1; status = 'FAIL'; test = 'invalid-arena-config'; startedUtc = $started.ToString('o'); executable = $executable; runPath = $runRoot }

function Read-OwnedEvidence {
    $lines = @([regex]::Matches((Read-Log $stdoutPath), '(?m)^DIAGNOSTIC_REPORT: ([^\r\n]+)\r?$'))
    if ($lines.Count -eq 0) { return $null }
    $paths = @($lines | ForEach-Object { [IO.Path]::GetFullPath($_.Groups[1].Value.Trim()) } | Select-Object -Unique)
    if ($paths.Count -ne 1) { throw 'This launch emitted multiple different diagnostic report paths.' }
    $path = $paths[0]
    $directory = [IO.Path]::GetDirectoryName($path)
    if (-not [IO.Path]::GetDirectoryName($directory).Equals($diagnosticsRoot, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($directory) -notmatch '^run-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' -or
        [IO.Path]::GetFileName($path) -ne 'diagnostic-result.json') { throw 'Diagnostic report path is outside the expected unique run directory.' }
    $file = Get-Item -LiteralPath $path
    if ($file.LastWriteTimeUtc -lt $started) { throw 'Diagnostic evidence predates this launch.' }
    # The application rewrites its report during shutdown. A concurrent partial
    # write is retried while the owned process is alive, then checked strictly.
    $data = (Read-Log $path) | ConvertFrom-Json
    if ($data.mode -ne 'graphics-smoke' -or $data.requestedSeconds -ne 30 -or $data.pid -le 0) { throw 'Diagnostic report does not describe this requested run.' }
    return [pscustomobject]@{ Path = $path; Data = $data }
}

function Get-VerifiedProcess([int]$CandidateId) {
    $candidate = Get-CimInstance Win32_Process -Filter "ProcessId=$CandidateId" -OperationTimeoutSec 2
    if ($null -eq $candidate) { return $null }
    if ([string]::IsNullOrWhiteSpace($candidate.ExecutablePath) -or
        -not [IO.Path]::GetFullPath($candidate.ExecutablePath).Equals($executable, [StringComparison]::OrdinalIgnoreCase) -or
        $candidate.CreationDate.ToUniversalTime() -lt $started) { throw "Refusing unverified process $CandidateId." }
    return $candidate
}

function Track-OwnedProcesses {
    # jpackage can delegate to another WreckRiff.exe. Record descendants while
    # their parent is known; never select a concurrent game by executable alone.
    $candidates = @(Get-CimInstance Win32_Process -Filter "Name='WreckRiff.exe'" -OperationTimeoutSec 2)
    do {
        $added = $false
        foreach ($candidate in $candidates) {
            $candidateId = [int]$candidate.ProcessId
            if ($ownedProcesses.ContainsKey($candidateId) -or -not $ownedProcesses.ContainsKey([int]$candidate.ParentProcessId)) { continue }
            $verified = Get-VerifiedProcess $candidateId
            if ($null -ne $verified) {
                $ownedProcesses[$candidateId] = $verified.CreationDate.ToUniversalTime()
                $added = $true
            }
        }
    } while ($added)
}

function Stop-OwnedProcesses {
    # Report PID is correlated through this run's private stdout, not a glob of
    # LOCALAPPDATA. Verify the executable and creation time before every stop.
    try {
        $latest = Read-OwnedEvidence
        if ($null -ne $latest) {
            $verified = Get-VerifiedProcess ([int]$latest.Data.pid)
            if ($null -ne $verified) { $ownedProcesses[[int]$verified.ProcessId] = $verified.CreationDate.ToUniversalTime() }
        }
    } catch { Write-Warning "Could not verify a report process for cleanup: $($_.Exception.Message)" }
    Track-OwnedProcesses
    foreach ($candidateId in @($ownedProcesses.Keys | Sort-Object { $_ -eq $launcher.Id })) {
        $verified = Get-VerifiedProcess $candidateId
        if ($null -eq $verified) { continue }
        if ($verified.CreationDate.ToUniversalTime() -ne $ownedProcesses[$candidateId]) { throw "Process $candidateId was reused; refusing to stop it." }
        Stop-Process -Id $candidateId -Force -ErrorAction Stop
    }
}

try {
    if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) { throw 'Run packageWindows before this regression.' }
    if (Test-Path -LiteralPath $runRoot) { throw 'The unique negative-launch directory already exists.' }
    [IO.Directory]::CreateDirectory($configRoot) | Out-Null
    $configNames = @('ai', 'arena', 'audio', 'camera', 'combat', 'gamepad', 'match', 'vehicle')
    foreach ($name in $configNames) {
        $source = Join-Path $projectRoot "src/main/resources/config/$name.json"
        $destination = Assert-BuildChild (Join-Path $configRoot "$name.json")
        Copy-Item -LiteralPath $source -Destination $destination
    }
    $arenaPath = Assert-BuildChild (Join-Path $configRoot 'arena.json')
    $arena = [IO.File]::ReadAllText($arenaPath, $utf8) | ConvertFrom-Json
    $arena.schemaVersion = 999
    [IO.File]::WriteAllText($arenaPath, ($arena | ConvertTo-Json -Depth 64), $utf8)
    $arguments = @('--dev', '--seed=42', '--smoke-seconds=30', "--config-dir=$configRoot")
    $argumentLine = ($arguments | ForEach-Object { Quote-WindowsArgument $_ }) -join ' '
    $started = [DateTime]::UtcNow
    $result.startedUtc = $started.ToString('o')
    $launcher = Start-Process -FilePath $executable -WorkingDirectory $imageRoot -WindowStyle Hidden -PassThru -ArgumentList $argumentLine `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
    $null = $launcher.Handle
    $ownedProcesses[[int]$launcher.Id] = $launcher.StartTime.ToUniversalTime()
    $deadline = $started.AddSeconds($timeoutSeconds)
    $completed = $false
    do {
        Track-OwnedProcesses
        try { $evidence = Read-OwnedEvidence } catch { $evidence = $null }
        $output = Read-Log $stdoutPath
        if ($launcher.HasExited -and $null -ne $evidence -and
            $output -match '(?m)^GRAPHICS_DIAGNOSTIC_(FAIL|PASS)\r?$') {
            $gameProcess = Get-VerifiedProcess ([int]$evidence.Data.pid)
            if ($null -eq $gameProcess) { $completed = $true; break }
        }
        if ($launcher.HasExited) { Start-Sleep -Milliseconds 250 }
        else { $null = $launcher.WaitForExit(250) }
    } while ([DateTime]::UtcNow -lt $deadline)
    if (-not $completed) { $timedOut = $true; throw 'Invalid-config executable did not finish and exit within 45 seconds.' }
    $launcher.WaitForExit()
    $exitCode = $launcher.ExitCode
    $result.exitCode = $exitCode
    $result.launcherPid = $launcher.Id
    $evidence = Read-OwnedEvidence
    $stdout = Read-Log $stdoutPath
    $stderr = Read-Log $stderrPath
    if ($exitCode -ne 1) { throw "Expected exit code 1, got $exitCode." }
    if ($stdout -notmatch '(?m)^GRAPHICS_DIAGNOSTIC_FAIL\r?$' -or ($stdout + $stderr) -match 'GRAPHICS_DIAGNOSTIC_PASS') { throw 'Expected only the GRAPHICS_DIAGNOSTIC_FAIL completion marker.' }
    if ($null -eq $evidence -or $evidence.Data.status -ne 'FAIL') { throw 'Expected a final FAIL diagnostic report.' }
    $errors = @($evidence.Data.errors)
    if ($errors.Count -ne 1 -or $errors[0] -notmatch '^IllegalArgumentException: Invalid config arena:') { throw 'Expected exactly one Invalid config arena error.' }
    if (($errors -join "`n") -match 'NullPointerException|Shutdown\s*failed' -or $stderr -match 'NullPointerException|Shutdown\s*failed') { throw 'Configuration failure caused a secondary render or shutdown error.' }
    if ($evidence.Data.sourceSha256 -notmatch '^[0-9a-fA-F]{64}$') { throw 'The diagnostic report has no valid source SHA-256.' }
    $gameProcess = Get-VerifiedProcess ([int]$evidence.Data.pid)
    if ($null -ne $gameProcess) { throw 'The reported game process is still running after diagnostic completion.' }
    $copyPath = Assert-BuildChild (Join-Path $runRoot 'diagnostic-result.json')
    Copy-Item -LiteralPath $evidence.Path -Destination $copyPath
    $result.status = 'PASS'
    $result.sourceSha256 = $evidence.Data.sourceSha256
    $result.gamePid = $evidence.Data.pid
    $result.diagnosticPath = $evidence.Path
    $result.diagnosticCopyPath = $copyPath
    $result.errors = $errors
    $result.summary = 'Invalid arena schema exits 1 with one reported cause and no secondary render/shutdown failure.'
} catch {
    $failure = $_
    $result.failure = $_.Exception.Message
} finally {
    if ($null -ne $launcher -and ($null -ne $failure -or -not $launcher.HasExited)) {
        try { Stop-OwnedProcesses } catch { $result.cleanupFailure = $_.Exception.Message; if ($null -eq $failure) { $failure = $_; $result.status = 'FAIL' } }
    }
    $result.timedOut = $timedOut
    $result.elapsedSeconds = [Math]::Round(([DateTime]::UtcNow - $started).TotalSeconds, 3)
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName((Assert-BuildChild $resultPath))) | Out-Null
    [IO.File]::WriteAllText((Assert-BuildChild $resultPath), ($result | ConvertTo-Json -Depth 6), $utf8)
    if ($null -ne $launcher) { $launcher.Dispose() }
}
if ($null -ne $failure) { throw $failure }
Write-Output "Invalid-config packaged regression PASS: $resultPath"

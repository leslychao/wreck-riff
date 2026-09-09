param(
    [Parameter(Mandatory=$true)][int]$ProcessId,
    [Parameter(Mandatory=$true)][string]$Output,
    [ValidateRange(1,7200)][int]$MaximumSeconds=700
)
$ErrorActionPreference='Stop'
$samples=[Collections.Generic.List[object]]::new()
$started=[DateTime]::UtcNow
$cpu=(Get-ItemProperty -LiteralPath 'HKLM:\HARDWARE\DESCRIPTION\System\CentralProcessor\0' -Name ProcessorNameString).ProcessorNameString.Trim()
$ram=$null
try { $ram=(Get-CimInstance Win32_ComputerSystem -OperationTimeoutSec 5).TotalPhysicalMemory } catch { }
while (([DateTime]::UtcNow-$started).TotalSeconds -lt $MaximumSeconds) {
    $process=Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $process) { break }
    $samples.Add([pscustomobject][ordered]@{
        seconds=[Math]::Round(([DateTime]::UtcNow-$started).TotalSeconds,3)
        workingSetBytes=$process.WorkingSet64
        privateBytes=$process.PrivateMemorySize64
        handles=$process.HandleCount
        threads=$process.Threads.Count
    })
    Start-Sleep -Seconds 1
}
$peak=if($samples.Count -gt 0){($samples | Measure-Object -Property workingSetBytes -Maximum).Maximum}else{0}
$result=[ordered]@{
    schemaVersion=1;pid=$ProcessId;cpu=$cpu;ramBytes=$ram
    os=[Environment]::OSVersion.VersionString
    measurement='Windows process counters; private bytes include JVM and native allocations'
    peakWorkingSetBytes=$peak;within1_5GiB=($samples.Count -gt 0 -and $null -ne $peak -and $peak -le 1.5GB)
    processExited=($null -eq (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue))
    samples=$samples.ToArray()
}
$absolute=[IO.Path]::GetFullPath($Output)
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($absolute)) | Out-Null
[IO.File]::WriteAllText($absolute,($result | ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
Write-Output "Memory report: $absolute; samples=$($samples.Count); peakWorkingSetBytes=$peak"

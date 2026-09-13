# Headless PowerShell 5.1 regression: no game, CIM provider or GPU is started.
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot '../release-evidence.ps1')
$script:checks=0
function Check([bool]$Condition,[string]$Name){if(!$Condition){throw "Fixture failed: $Name"};$script:checks++}
$tokens=$null;$parseErrors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $root 'tools/test-local-world.ps1'),[ref]$tokens,[ref]$parseErrors)
Check ($parseErrors.Count -eq 0) 'local sampler parses on Windows PowerShell'
$loop=$ast.Find({param($node) $node -is [Management.Automation.Language.WhileStatementAst] -and $node.Condition.Extent.Text -eq '!$game.HasExited'},$true)
Check ($null -ne $loop) 'the actual owned process sampling loop exists'
$blocking=@($loop.FindAll({param($node) $node -is [Management.Automation.Language.CommandAst] -and $node.GetCommandName() -in @('Get-CimInstance','Get-WmiObject','Get-Counter')},$true))
Check ($blocking.Count -eq 0) 'optional WDDM I/O cannot block the process memory sampler'
$gpuRecord=$ast.Find({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -eq '$gpuMemory'},$true)
Check ($null -ne $gpuRecord) 'GPU memory availability is still reported'
foreach($enabled in @($false,$true)) {
    $Profile=[Management.Automation.SwitchParameter]::new($enabled)
    . ([scriptblock]::Create($gpuRecord.Extent.Text))
    Check ($gpuMemory.status -eq $(if($enabled){'UNAVAILABLE'}else{'NOT_REQUESTED'})) 'GPU memory availability reflects the requested mode'
    Check ($null -eq $gpuMemory.peakDedicatedBytes -and $gpuMemory.samples.Count -eq 0) 'unknown VRAM is neither zero nor sampled evidence'
    Check (![string]::IsNullOrWhiteSpace($gpuMemory.reason)) 'unavailable memory has an explicit reason'
}
Check ($ast.Extent.Text.Contains("if(`$Profile){`$arguments+='--profile'}")) 'game CPU/GPU profiling remains enabled when requested'
# The existing seven-second sampling gap remains a failure; removing optional
# WDDM collection must never relax the independent memory evidence gate.
$start=[DateTime]::UtcNow;$iso=$start.ToString('o')
$samples=@(0..61 | ForEach-Object {[pscustomobject]@{seconds=$_+.02;observedAtUtc=$start.AddSeconds($_+.02).ToString('o');workingSetBytes=10MB;peakWorkingSetBytes=12MB;handles=100}})
$memory=[pscustomobject]@{pid=123;processExited=$true;processStartTimeUtc=$iso;samples=$samples;peakWorkingSetBytes=12MB;finalPeakWorkingSetBytes=12MB;finalPeakObservedAfterExit=$true}
$binding=[pscustomobject]@{gamePid=123;processStartTimeUtc=$iso};$diagnostic=[pscustomobject]@{pid=123}
Assert-ReleaseMemory $binding $diagnostic $memory 60;$script:checks++
for($index=1;$index -lt $samples.Count;$index++){$samples[$index].seconds+=6.55;$samples[$index].observedAtUtc=$start.AddSeconds($samples[$index].seconds).ToString('o')}
$rejected=$false;try{Assert-ReleaseMemory $binding $diagnostic $memory 60}catch{$rejected=$true}
Check $rejected 'a 7.55-second gap still invalidates memory evidence'
Write-Output "Local process-memory fixtures PASS: $script:checks assertions; no game or GPU was launched."

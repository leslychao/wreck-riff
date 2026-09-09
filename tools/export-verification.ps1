$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Join-Path $PSScriptRoot '..')
$projectRoot=$PWD.Path
$version='0.3.0'
$name="WreckRiff-$version-verification"
$destination=Join-Path $projectRoot "build/distributions/$name"
$archive="$destination.zip"
if((Test-Path -LiteralPath $destination) -or (Test-Path -LiteralPath $archive)) {throw 'Verification output already exists; preserve the earlier evidence.'}
$benchmark=Get-Content -LiteralPath build/reports/windows-benchmark.json -Raw | ConvertFrom-Json
$packaged=Get-Content -LiteralPath build/reports/packaged-launch.json -Raw | ConvertFrom-Json
$negative=Get-Content -LiteralPath build/reports/negative-launch.json -Raw | ConvertFrom-Json
$showcase=Get-Content -LiteralPath build/reports/showcase.json -Raw | ConvertFrom-Json
$buildInfo=ConvertFrom-StringData (Get-Content -LiteralPath build/generated-resources/build-info.properties -Raw)
foreach($report in @($benchmark,$packaged,$negative,$showcase)) {
    if($report.status -ne 'PASS' -or $report.sourceSha256 -ne $buildInfo.sourceSha256) {throw 'Final reports do not confirm the packaged source.'}
}
if(!$benchmark.fullTenMinutes) {throw 'Full ten-minute benchmark is required.'}
$smokeRoot=Split-Path -Parent $packaged.testPath
$smokeLine=Get-Content -LiteralPath (Join-Path $smokeRoot 'stdout.log') | Where-Object {$_.StartsWith('DIAGNOSTIC_REPORT: ')} | Select-Object -Last 1
$smokePath=$smokeLine.Substring(19).Trim()
$smoke=Get-Content -LiteralPath $smokePath -Raw | ConvertFrom-Json
if($smoke.status -ne 'PASS' -or $smoke.restarts -ne 20 -or $smoke.pid -ne $packaged.gamePid) {throw 'Smoke identity/acceptance mismatch.'}
function Copy-Evidence([string]$source,[string]$relative) {
    $target=Join-Path $destination $relative
    [IO.Directory]::CreateDirectory((Split-Path -Parent $target)) | Out-Null
    Copy-Item -LiteralPath $source -Destination $target -Recurse
}
[IO.Directory]::CreateDirectory($destination) | Out-Null
Copy-Evidence 'build/generated-resources/build-info.properties' 'build-info.properties'
Copy-Evidence 'README.md' 'PROJECT_README.md'
Copy-Evidence "build/distributions/WreckRiff-$version-windows-x64.zip.sha256" "WreckRiff-$version-windows-x64.zip.sha256"
foreach($doc in @('ACCEPTANCE.md','IMPLEMENTATION_STATUS.md','V0_3_PLAN.md','MVP_SPEC.md','DECISIONS.md','ASSET_REGISTER.csv','AUDIO_DESIGN.md','THIRD_PARTY_NOTICES.md','STEAM_HANDOFF.md')) {
    Copy-Evidence "docs/$doc" "docs/$doc"
}
foreach($report in @('windows-benchmark.json','packaged-launch.json','negative-launch.json','ai-batch.json','showcase.json')) {
    Copy-Evidence "build/reports/$report" "reports/$report"
}
Copy-Evidence 'build/distributions/WreckRiff/reports/package-verification.json' 'reports/package-verification.json'
Copy-Evidence 'build/reports/assets' 'reports/assets'
Copy-Evidence 'build/reports/v03-first-failure' 'reports/prior-failures'
Copy-Evidence 'build/v03-final-build.log' 'logs/final-build.log'
Copy-Evidence 'build/v03-final-package.log' 'logs/final-package.log'
Copy-Evidence 'build/distributions/WreckRiff-0.3.0-demo' 'demo'
foreach($suite in @('test','physicsTest')) {
    foreach($xml in Get-ChildItem -LiteralPath "build/test-results/$suite" -Filter 'TEST-*.xml') {
        [xml]$parsed=Get-Content -LiteralPath $xml.FullName -Raw
        if([int]$parsed.testsuite.failures -gt 0 -or [int]$parsed.testsuite.errors -gt 0 -or [int]$parsed.testsuite.skipped -gt 0) {throw "Unsuccessful test report: $($xml.Name)"}
        Copy-Evidence $xml.FullName "tests/$suite/$($xml.Name)"
    }
    Copy-Evidence "build/reports/tests/$suite" "tests/html/$suite"
}
Copy-Evidence $smokePath 'reports/graphics-smoke.json'
Copy-Evidence $benchmark.diagnosticPath 'reports/benchmark.json'
Copy-Evidence (Join-Path $benchmark.runRoot 'memory.json') 'reports/benchmark-process-memory.json'
foreach($log in @('stdout.log','stderr.log')) {
    Copy-Evidence (Join-Path $smokeRoot $log) "logs/smoke/$log"
    Copy-Evidence (Join-Path $benchmark.runRoot $log) "logs/benchmark/$log"
    Copy-Evidence (Join-Path $negative.runPath $log) "logs/negative/$log"
}
Copy-Evidence (Join-Path (Split-Path -Parent $smokePath) 'captures') 'captures/720p'
Copy-Evidence (Join-Path (Split-Path -Parent $benchmark.diagnosticPath) 'captures') 'captures/1080p'
foreach($helper in @('test-windows-package.ps1','test-windows-benchmark.ps1','test-invalid-config.ps1','watch-process-memory.ps1','test-windows-showcase.ps1','export-showcase.ps1')) {
    Copy-Evidence "tools/$helper" "tools/$helper"
}
$readme=@'
# Wreck Riff 0.3.0 verification

This archive accompanies WreckRiff-0.3.0-windows-x64.zip; it contains no game runtime.
Check build-info.properties, the game ZIP checksum, and docs/ACCEPTANCE.md.
reports/graphics-smoke.json and reports/benchmark.json contain the full final runtime evidence.
reports/prior-failures is historical failed evidence, explicitly not a final PASS.
The screenshots are unedited framebuffer captures from the same final source. The staged HP gallery and reconstructed audio are labelled in demo/AUDIO_README.txt and demo/index.html.
The reports retain local diagnostic paths for traceability; all copied evidence is usable here.
Owner feel/music/visual acceptance, physical controller, and a separate fresh Windows remain manual gates.
No publication, remote CI run, owner approval, or MVP acceptance is implied.
'@
[IO.File]::WriteAllText((Join-Path $destination 'README.md'),$readme,[Text.UTF8Encoding]::new($false))
$manifest=Get-ChildItem -LiteralPath $destination -Recurse -File | Sort-Object FullName | ForEach-Object {
    [ordered]@{path=[IO.Path]::GetRelativePath($destination,$_.FullName).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
}
[IO.File]::WriteAllText((Join-Path $destination 'manifest.json'),(ConvertTo-Json -InputObject @($manifest) -Depth 5),[Text.UTF8Encoding]::new($false))
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($destination,$archive,[IO.Compression.CompressionLevel]::Optimal,$true)
$checksum=(Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$archive.sha256",("$checksum  $name.zip"+[Environment]::NewLine),[Text.UTF8Encoding]::new($false))
Write-Output "Verification archive: $archive"
Write-Output "SHA256: $checksum"

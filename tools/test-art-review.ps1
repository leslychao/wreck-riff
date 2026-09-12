param(
    [Parameter(Mandatory=$true)][string]$InstallDirectory,
    [Parameter(Mandatory=$true)][string]$JdkHome,
    [Parameter(Mandatory=$true)][string]$Ffmpeg,
    [ValidateSet('720p','1080p')][string]$Resolution='720p',
    [switch]$NoGlow,
    [string[]]$Arenas=@('construction_17','dead-air-yard','neon_zero','euphoria_park','ash_necropolis','doomsday_arena')
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$install=(Resolve-Path -LiteralPath $InstallDirectory).ProviderPath
$jdk=(Resolve-Path -LiteralPath $JdkHome).ProviderPath
$encoder=(Resolve-Path -LiteralPath $Ffmpeg).ProviderPath
if((Get-Content -LiteralPath (Join-Path $jdk 'release') -Raw) -notmatch 'JAVA_VERSION="21\.') {throw 'Microsoft JDK 21 required'}
if((Get-Content -LiteralPath (Join-Path $jdk 'release') -Raw) -notmatch 'IMPLEMENTOR="Microsoft"') {throw 'Microsoft JDK 21 required'}
$java=Join-Path $jdk 'bin/java.exe'
$mainJar=Join-Path $install 'lib/wreck-riff-0.4.0.jar'
$jarHash=(Get-FileHash -LiteralPath $mainJar -Algorithm SHA256).Hash
$runRoot=Join-Path $projectRoot ('build/art-review/'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory -Path $runRoot
$results=@()
foreach($arena in $Arenas) {
    if($arena -notmatch '^[a-z][a-z0-9_-]*$') {throw 'Invalid arena ID'}
    if((Get-FileHash -LiteralPath $mainJar -Algorithm SHA256).Hash -ne $jarHash) {throw 'Frozen review JAR changed during matrix'}
    $destination=Join-Path $runRoot ($arena+'-'+$Resolution)
    $null=New-Item -ItemType Directory -Path $destination
    $stdout=Join-Path $destination 'stdout.log'
    $stderr=Join-Path $destination 'stderr.log'
    $arguments=@('-ea','-Xms128m','-Xmx768m','-cp',('"'+(Join-Path $install 'lib/*')+'"'),'game.wreckriff.Main','--dev','--seed=42',('--arena='+$arena),'--art-showcase',('--resolution='+$Resolution))
    if($NoGlow) {$arguments+='--no-glow'}
    $started=[DateTime]::UtcNow
    $process=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $install -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $null=$process.Handle
    Write-Output "ART_WINDOW: $arena $Resolution PID=$($process.Id) glow=$(!$NoGlow)"
    while(!$process.WaitForExit(1000)) {
        if(([DateTime]::UtcNow-$started).TotalSeconds -gt 245) {throw "Art window timeout; PID $($process.Id), see $destination"}
    }
    $process.WaitForExit()
    $exitCode=$process.ExitCode
    # An uncaught decoder/worker failure can coexist with a zero process exit and a render-thread PASS.
    $uncaughtFailures=@(Select-String -LiteralPath $stdout,$stderr -Pattern 'Exception in thread |AssertionError|FATAL ERROR|A fatal error has been detected')
    $line=Get-Content -LiteralPath $stdout | Where-Object {$_.StartsWith('DIAGNOSTIC_REPORT: ')} | Select-Object -Last 1
    if(!$line) {throw "No diagnostic report: $destination"}
    $reportPath=[IO.Path]::GetFullPath($line.Substring(19).Trim())
    $diagnosticRoot=[IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'WreckRiff/diagnostics'))+[IO.Path]::DirectorySeparatorChar
    if(!$reportPath.StartsWith($diagnosticRoot,[StringComparison]::OrdinalIgnoreCase)) {throw 'Unexpected diagnostic report location'}
    if((Get-Item -LiteralPath $reportPath).LastWriteTimeUtc -lt $started) {throw 'Stale diagnostic report'}
    $report=Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
    $source=Split-Path -Parent $reportPath
    if($report.status -ne 'PASS' -or !$report.PSObject.Properties['artShowcase']) {
        foreach($name in @('diagnostic-result.json','audio.wav','audio-events.json','AUDIO_README.txt')) {
            $artifact=Join-Path $source $name
            if(Test-Path -LiteralPath $artifact) {Copy-Item -LiteralPath $artifact -Destination $destination}
        }
        if(Test-Path -LiteralPath (Join-Path $source 'captures')) {Copy-Item -LiteralPath (Join-Path $source 'captures') -Destination $destination -Recurse}
        $results += [ordered]@{arena=$arena;resolution=$Resolution;glow=(!$NoGlow);status='FAIL';pid=$process.Id;sourceSha256=$report.sourceSha256;jarSha256=$jarHash;rawArtifactDirectory=$source;reviewDirectory=$destination;elapsedSeconds=([DateTime]::UtcNow-$started).TotalSeconds;performance='NOT_MEASURED';feel='PENDING_OWNER';reason='Game ended without complete passing art evidence'}
        [IO.File]::WriteAllText((Join-Path $runRoot 'matrix.json'),(ConvertTo-Json -InputObject $results -Depth 8),[Text.UTF8Encoding]::new($false))
        throw "Incomplete art run (exit $exitCode); original report and captures preserved: $destination"
    }
    $height=if($Resolution -eq '720p'){720}else{1080}
    $passed=$exitCode -eq 0 -and $report.status -eq 'PASS' -and $report.mode -eq 'art-showcase' -and $report.arenaId -eq $arena -and $report.pid -eq $process.Id -and $report.height -eq $height -and $report.width -eq ($height*16/9) -and $report.audioEnabled -and $report.windowVisible -and $report.artShowcase.collectedTypes.Count -eq 8 -and $report.artShowcase.failures.Count -eq 0
    $expectedTypes=@('HOMING_AMMO','POWER_AMMO','MINE_AMMO','NAPALM_AMMO','BALLISTIC_AMMO','CANNON_AMMO','REPAIR','TURBO_CELL')
    $missingTypes=@($expectedTypes | Where-Object {$_ -notin $report.artShowcase.collectedTypes})
    $requiredCaptures=@('art-overview','art-upper-route','art-launch-approach','art-landmark')
    foreach($type in $expectedTypes) {
        $requiredCaptures+='pickup-available-'+$type.ToLowerInvariant()
        $requiredCaptures+='pickup-collected-'+$type.ToLowerInvariant()
    }
    $missingCaptures=@($requiredCaptures | Where-Object {
        !(Get-ChildItem -LiteralPath (Join-Path $source 'captures') -Filter ('*-'+$_+'-*.png') -File)
    })
    $cannon=@($report.artShowcase.events | Where-Object {$_.type -eq 'PICKUP' -and $_.kind -eq 'cannon-ammo'})
    $passed=$passed -and $uncaughtFailures.Count -eq 0 -and $missingTypes.Count -eq 0 -and $missingCaptures.Count -eq 0 -and $cannon.Count -eq 1 -and $cannon[0].amount -eq 1 `
        -and $report.glow -eq (!$NoGlow) -and $report.undrawableSeconds -eq 0 -and $report.sourceSha256 -match '^[a-fA-F0-9]{64}$'
    if($arena -ne 'dead-air-yard') {$passed=$passed -and $report.artShowcase.nativeLaunch -and $report.artShowcase.nativeLanding}
    foreach($name in @('diagnostic-result.json','audio.wav','audio-events.json','AUDIO_README.txt')) {Copy-Item -LiteralPath (Join-Path $source $name) -Destination $destination}
    Copy-Item -LiteralPath (Join-Path $source 'captures') -Destination $destination -Recurse
    $movie=Join-Path $destination 'pickups.mp4'
    & $encoder -hide_banner -loglevel warning -i (Join-Path $source 'art-showcase.avi') -i (Join-Path $source 'audio.wav') -map 0:v:0 -map 1:a:0 -c:v libx264 -threads 4 -preset fast -crf 20 -pix_fmt yuv420p -c:a aac -b:a 192k -shortest -movflags +faststart $movie
    if($LASTEXITCODE -ne 0) {throw "Video encoding failed: $destination"}
    $results += [ordered]@{arena=$arena;resolution=$Resolution;glow=(!$NoGlow);status=$(if($passed){'GRAPHICS_PASS'}else{'FAIL'});pid=$process.Id;sourceSha256=$report.sourceSha256;jarSha256=$jarHash;rawArtifactDirectory=$source;reviewDirectory=$destination;elapsedSeconds=([DateTime]::UtcNow-$started).TotalSeconds;performance='NOT_MEASURED';feel='PENDING_OWNER';audio='Reconstructed actual voice allocation, not native OpenAL/HRTF loopback'}
    [IO.File]::WriteAllText((Join-Path $runRoot 'matrix.json'),(ConvertTo-Json -InputObject $results -Depth 8),[Text.UTF8Encoding]::new($false))
    Write-Output "ART_RESULT: $arena $($results[-1].status) $destination"
    if(!$passed) {throw "Art evidence failed: $destination"}
}
$cards=foreach($result in $results) {
    $folder=Split-Path -Leaf $result.reviewDirectory
    $shots=Get-ChildItem -LiteralPath (Join-Path $result.reviewDirectory 'captures') -Filter '*-art-*.png' -File | Sort-Object Name
    $images=foreach($shot in $shots) {'<a href="'+$folder+'/captures/'+$shot.Name+'"><img loading="lazy" src="'+$folder+'/captures/'+$shot.Name+'" alt="'+$shot.Name+'"></a>'}
    '<section><h2>'+$result.arena+' / '+$result.resolution+'</h2><video controls preload="metadata" src="'+$folder+'/pickups.mp4"></video><div>'+($images -join '')+'</div></section>'
}
$html='<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Wreck Riff — реальные кадры художественного обновления</title><style>body{background:#151b21;color:#e8eced;font:17px/1.5 system-ui;margin:32px auto;max-width:1440px;padding:24px}h1,h2{color:#ffba68}video{width:100%;max-height:760px}section{margin:42px 0}section div{display:grid;grid-template-columns:1fr 1fr;gap:12px}img{width:100%}a{color:#ffba68}</style><h1>Wreck Riff: художественный прогон</h1><p>Настоящее окно игры. Позиции и недостаток ресурсов подготовлены для демонстрации; все события подбора, начисления, подбросы и приземления проходят штатную симуляцию. Дорожка восстановлена из фактически запущенных игровых семплов и параметров, это не точная запись OpenAL/HRTF. Замер производительности и оценка владельца здесь не присваиваются.</p>'+($cards -join '')+'</html>'
[IO.File]::WriteAllText((Join-Path $runRoot 'index.html'),$html,[Text.UTF8Encoding]::new($false))
Write-Output "ART_REVIEW: $runRoot"

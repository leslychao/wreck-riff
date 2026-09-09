param(
    [Parameter(Mandatory=$true)][string[]]$MatrixDirectories,
    [Parameter(Mandatory=$true)][string]$BaselineDirectory,
    [string]$CombatAfterDirectory,
    [string]$VehicleDirectory
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$reviewRoot=Join-Path $projectRoot ('build/art-review/collection-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
if(Test-Path -LiteralPath $reviewRoot) {throw 'Review collection already exists'}
$null=New-Item -ItemType Directory -Path $reviewRoot
function Escape-Html([string]$value) {[Net.WebUtility]::HtmlEncode($value)}
function Asset-Link([string]$path) {
    $resolved=(Resolve-Path -LiteralPath $path).ProviderPath
    [IO.Path]::GetRelativePath($reviewRoot,$resolved).Replace('\','/')
}
function Image-Gallery([string]$directory,[string]$pattern) {
    $shots=Get-ChildItem -LiteralPath (Join-Path $directory 'captures') -Filter $pattern -File | Sort-Object Name
    $items=foreach($shot in $shots) {
        $link=Escape-Html (Asset-Link $shot.FullName)
        '<a href="'+$link+'"><img loading="lazy" src="'+$link+'" alt="'+(Escape-Html $shot.Name)+'"></a>'
    }
    '<div class="shots">'+($items -join '')+'</div>'
}
$allResults=@(foreach($directory in $MatrixDirectories) {
    $manifest=Join-Path (Resolve-Path -LiteralPath $directory).ProviderPath 'matrix.json'
    foreach($entry in (Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json)) {$entry}
})
$latest=[ordered]@{}
foreach($result in $allResults) {
    if($result.status -eq 'GRAPHICS_PASS') {
        $evidence=Get-Content -LiteralPath (Join-Path $result.reviewDirectory 'diagnostic-result.json') -Raw | ConvertFrom-Json
        if($evidence.status -ne 'PASS' -or $evidence.sourceSha256 -ne $result.sourceSha256 -or $evidence.pid -ne $result.pid) {
            throw "Evidence and matrix disagree: $($result.reviewDirectory)"
        }
        $latest[$result.arena+'|'+$result.resolution+'|'+$result.glow]=$result
    }
}
if($latest.Count -eq 0) {throw 'No passing graphical evidence'}
$cards=foreach($result in $latest.Values) {
    $movie=Escape-Html (Asset-Link (Join-Path $result.reviewDirectory 'pickups.mp4'))
    $evidence=Escape-Html (Asset-Link (Join-Path $result.reviewDirectory 'diagnostic-result.json'))
    '<section><h2>'+(Escape-Html $result.arena)+' / '+$result.resolution+' / glow='+$result.glow+'</h2>'+
        '<p class="meta">Source SHA256: '+$result.sourceSha256+' · <a href="'+$evidence+'">JSON проверки</a></p>'+
        (Image-Gallery $result.reviewDirectory '*-art-*.png')+
        '<details><summary>Все восемь подборов: модели и реальные начисления</summary>'+
        '<video controls preload="metadata" src="'+$movie+'"></video>'+
        (Image-Gallery $result.reviewDirectory '*-pickup-*.png')+'</details></section>'
}
$baseline=(Resolve-Path -LiteralPath $BaselineDirectory).ProviderPath
$comparison='<section><h2>До обновления — исходная сборка</h2><p>Сохранённый исходный 40-секундный сценарий. Это отдельный снимок кода, не замер производительности.</p>'+
    (Image-Gallery $baseline '*.png')+'</section>'
foreach($extra in @(@{path=$CombatAfterDirectory;title='После обновления — боевые эффекты'},@{path=$VehicleDirectory;title='Три машины и их специальные действия'})) {
    if(!$extra.path) {continue}
    $folder=(Resolve-Path -LiteralPath $extra.path).ProviderPath
    $report=Get-Content -LiteralPath (Join-Path $folder 'diagnostic-result.json') -Raw | ConvertFrom-Json
    $comparison+='<section><h2>'+$extra.title+'</h2><p>Diagnostic status: '+(Escape-Html $report.status)+
        ' · Source SHA256: '+(Escape-Html $report.sourceSha256)+'</p>'+(Image-Gallery $folder '*.png')
    foreach($movie in (Get-ChildItem -LiteralPath $folder -Filter '*.mp4' -File)) {
        $comparison+='<video controls preload="metadata" src="'+(Escape-Html (Asset-Link $movie.FullName))+'"></video>'
    }
    $comparison+='</section>'
}
$failures=@($allResults | Where-Object {$_.status -ne 'GRAPHICS_PASS'})
$manifest=[ordered]@{createdUtc=[DateTime]::UtcNow.ToString('o');arenas=@($latest.Values);failedAttempts=$failures;
    baseline=$baseline;combatAfter=$CombatAfterDirectory;vehicles=$VehicleDirectory;performance='NOT_MEASURED';feel='PENDING_OWNER'}
[IO.File]::WriteAllText((Join-Path $reviewRoot 'evidence.json'),(ConvertTo-Json -InputObject $manifest -Depth 12),[Text.UTF8Encoding]::new($false))
$html='<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Wreck Riff — художественная приёмка</title><style>body{background:#151b21;color:#e8eced;font:17px/1.5 system-ui;margin:0 auto;max-width:1440px;padding:32px}h1,h2{color:#ffba68}a{color:#82ddea}video{width:100%;max-height:800px;margin:18px 0}section{margin:48px 0}.shots{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}img{width:100%}.meta{font-size:13px;overflow-wrap:anywhere}summary{cursor:pointer;color:#ffba68;padding:16px 0}@media(max-width:800px){.shots{grid-template-columns:1fr}}</style><h1>Wreck Riff: реальные кадры обновления</h1><p>Все изображения сняты из настоящего окна игры. Для демонстрации подготовлены позиции и недостаток ресурсов; начисления, подбросы и приземления проходят штатную симуляцию. Аудиодорожка реконструирована из реально выделенных игровых голосов; это не loopback OpenAL/HRTF.</p><p>Показанные сборки обозначены своими SHA256. Кадры общего вида не доказывают полноценный верхний бой или прохождение босса. Performance: NOT_MEASURED. FEEL_APPROVED: PENDING_OWNER. Сохранённых неудачных попыток: '+$failures.Count+'. <a href="evidence.json">Полный реестр</a>.</p>'+($cards -join '')+$comparison+'</html>'
[IO.File]::WriteAllText((Join-Path $reviewRoot 'index.html'),$html,[Text.UTF8Encoding]::new($false))
Write-Output "ART_COLLECTION: $reviewRoot"

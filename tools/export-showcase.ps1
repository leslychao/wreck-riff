param([Parameter(Mandatory=$true)][string]$Ffmpeg)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$report=Get-Content (Join-Path $projectRoot 'build/reports/showcase.json') -Raw | ConvertFrom-Json
if($report.status -ne 'PASS') {throw 'A successful current showcase is required'}
$buildInfo=ConvertFrom-StringData (Get-Content (Join-Path $projectRoot 'build/generated-resources/build-info.properties') -Raw)
if($report.sourceSha256 -ne $buildInfo.sourceSha256) {throw 'Showcase does not match the current build'}
$source=[IO.Path]::GetFullPath($report.artifactDirectory)
$destination=Join-Path $projectRoot 'build/distributions/WreckRiff-0.4.0-demo'
if(Test-Path -LiteralPath $destination) {throw 'Preserve the earlier demo before exporting a new one'}
[IO.Directory]::CreateDirectory($destination)|Out-Null
Copy-Item -LiteralPath (Join-Path $source 'captures') -Destination $destination -Recurse -Force
foreach($name in @('audio-events.json','AUDIO_README.txt','diagnostic-result.json')) {Copy-Item -LiteralPath (Join-Path $source $name) -Destination $destination -Force}
$movie=Join-Path $destination 'WreckRiff-0.4.0-demo.mp4'
& $Ffmpeg -hide_banner -y -i (Join-Path $source 'showcase.avi') -i (Join-Path $source 'audio.wav') `
    -map 0:v:0 -map 1:a:0 -c:v libx264 -preset fast -crf 19 -pix_fmt yuv420p `
    -c:a aac -b:a 192k -shortest -movflags +faststart $movie
if($LASTEXITCODE -ne 0) {throw 'Video encoding failed'}
$shots=@(
    @('hp-100','Целый кузов / 100%'),@('hp-75','Лёгкие повреждения / 75%'),
    @('hp-50','Повреждённые панели / 50%'),@('hp-25','Критическое состояние / 25%'),
    @('hp-0','Обугленный кузов / 0%'),@('hp-repaired','После ремонта / 100%'),
    @('machine-gun','Пулемёт'),@('power-hit','Power / отбрасывание'),
    @('freeze','Freeze'),@('shield','Щит'),@('napalm','Напалм / помощь броску'),
    @('ballistic-warning','Баллистика / предупреждение'),@('ballistic-hit','Баллистика / накрытие'),
    @('cannon-hit','Ядро / боковой удар'),@('cannon-ricochet','Ядро / рикошеты'),
    @('cannon-lethal','Смертельный удар / физический остов'),@('wreck-removed','Остов удалён через три секунды'))
$cards=foreach($shot in $shots) {
    $file=Get-ChildItem (Join-Path $destination 'captures') -Filter "WreckRiff-0.4.0-$($shot[0])-*.png" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if(!$file) {throw "Missing capture $($shot[0])"}
    '<figure><a href="captures/'+$file.Name+'"><img loading="lazy" src="captures/'+$file.Name+'" alt="'+$shot[1]+'"></a><figcaption>'+$shot[1]+'</figcaption></figure>'
}
$html=@'
<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Wreck Riff 0.4 — демонстрация</title><style>
body{margin:0;background:#14191c;color:#e6e5dc;font:17px/1.6 system-ui,sans-serif}main{max-width:1400px;margin:auto;padding:32px}
h1{font-size:36px;margin:0;color:#ffbc72}p{max-width:1000px}video{display:block;width:100%;border-radius:8px;background:#050708}
section{display:grid;grid-template-columns:repeat(auto-fit,minmax(440px,1fr));gap:20px}figure{margin:0;background:#20282d;border:1px solid #3a4246;border-radius:8px;overflow:hidden}
img{display:block;width:100%}figcaption{padding:12px 18px;color:#ffca91}code{word-break:break-all}a{color:#ffca91}
@media(max-width:520px){main{padding:16px}section{grid-template-columns:1fr}h1{font-size:28px}}</style><main>
<h1>Wreck Riff 0.4</h1><p>Настоящие кадры игры: повреждения кузова, тяжёлые попадания, напалм, баллистика, ядро, Freeze и щит.</p>
<video controls preload="metadata" src="WreckRiff-0.4.0-demo.mp4"></video>
<p>Первые 12 секунд — подписанная галерея предустановленных долей HP. Нулевая стадия здесь показана без уничтожения тестовой цели. Между последующими сценами позиции и HP подготовлены заново; все выстрелы, попадания и финальное уничтожение проходят обычную симуляцию. Дорожка собрана из фактически запущенных игровых семплов и параметров, с текущей музыкой. Это не точная запись OpenAL/HRTF. Окончательную оценку ощущений даёт владелец.</p>
'@
$html+='<p>Source SHA-256: <code>'+[Net.WebUtility]::HtmlEncode($report.sourceSha256)+'</code></p><section>'+($cards -join "`n")+'</section></main></html>'
[IO.File]::WriteAllText((Join-Path $destination 'index.html'),$html,[Text.UTF8Encoding]::new($false))
Get-FileHash -LiteralPath $movie -Algorithm SHA256 | Format-List
Write-Output "Showcase export: $destination"

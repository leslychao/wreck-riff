param([Parameter(Mandatory=$true)][string]$RouteDirectory)
$ErrorActionPreference='Stop'
$reviewRoot=(Resolve-Path -LiteralPath $RouteDirectory).Path
$evidence=Get-Content -LiteralPath (Join-Path $reviewRoot 'review.json') -Raw | ConvertFrom-Json
if($evidence.status -ne 'VISUAL_CAPTURE_COMPLETE' -or $evidence.performanceEvidence){throw 'A completed visual route inspection is required.'}
if($evidence.routes.Count -ne 25 -or $evidence.captures.Count -ne 75){throw 'The review must include all 16 districts and nine interiors, three captures each.'}
if(@($evidence.routes | Where-Object kind -eq 'district').Count -ne 16 -or @($evidence.routes | Where-Object kind -eq 'interior').Count -ne 9){throw 'The district/interior matrix is incomplete.'}
if(@($evidence.captures.path | Select-Object -Unique).Count -ne 75){throw 'Every view must retain its own image.'}
$titles=@{construction_17='МЕГАСТРОЙ-17';neon_zero='НЕОН-РАЙОН ZERO';euphoria_park='ЛУНАПАРК ЭЙФОРИЯ'}
$names=@{pit='Котлован';homes='Кварталы';plant='Бетонный завод';warehouses='Склады';interchange='Развязка';business='Деловой центр';market='Торговые улицы';transport='Транспортный узел';parking='Паркинг';entrance='Входная площадь';fair='Ярмарка';lake='Озеро и остров';rides='Аттракционы';circus='Цирк';backstage='Служебная территория';'unfinished-apartments'='Недострой';'concrete-plant'='Производственный корпус';warehouse='Сквозной склад';'shopping-passage'='Торговый пассаж';'technical-complex'='Технический комплекс';'parking-ground-floor'='Проезд паркинга';'ride-pavilion'='Павильон аттракционов';'repair-depot'='Ремонтное депо'}
function Escape-Html([string]$value){[Net.WebUtility]::HtmlEncode($value)}
$routeSignatures=@{}
$imageHashes=[Collections.Generic.HashSet[string]]::new()
$cards=foreach($route in $evidence.routes){
    $shots=@($evidence.captures | Where-Object {$_.arenaId -eq $route.arenaId -and $_.route -eq $route.id -and $_.kind -eq $route.kind} | Sort-Object shot)
    if($shots.Count -ne 3){throw "Incomplete route $($route.arenaId)/$($route.kind)/$($route.id)"}
    $shotHashes=[Collections.Generic.List[string]]::new()
    $images=foreach($shot in $shots){
        if($shot.path -notmatch '^[a-z0-9_-]+\.png$'){throw 'Unexpected capture filename.'}
        $file=Get-Item -LiteralPath (Join-Path $reviewRoot $shot.path)
        if($file.Length -eq 0){throw "Empty screenshot $($shot.path)"}
        $hash=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
        $shotHashes.Add($hash)
        [void]$imageHashes.Add($hash)
        $label=@('Подъезд','Игровая зона / поворот','Выезд')[$shot.shot-1]
        '<figure><a href="'+(Escape-Html $shot.path)+'"><img loading="lazy" src="'+(Escape-Html $shot.path)+'" alt="'+$label+'"></a><figcaption>'+$label+'</figcaption></figure>'
    }
    $signature=$shotHashes -join ':'
    $routeName="$($route.arenaId)/$($route.kind)/$($route.id)"
    if($routeSignatures.ContainsKey($signature)){throw "Duplicate three-view inspection: $routeName repeats $($routeSignatures[$signature]). Inspect the district and interior separately."}
    $routeSignatures[$signature]=$routeName
    $kind=if($route.kind -eq 'district'){'Район'}else{'Интерьер'}
    '<section data-arena="'+$route.arenaId+'" data-kind="'+$route.kind+'"><p class="eyebrow">'+$titles[$route.arenaId]+' · '+$kind+'</p><h2>'+$names[$route.id]+'</h2><div class="shots">'+($images -join '')+'</div></section>'
}
$identity=Escape-Html $evidence.build.sourceSha256
$html=@'
<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Wreck Riff — просмотр новых карт</title>
<style>
:root{color-scheme:dark;font:16px/1.5 system-ui;background:#11191e;color:#e9eef1}body{max-width:1500px;margin:auto;padding:32px}h1{font-size:38px;line-height:1.15;margin:12px 0}h2{font-size:25px;margin:4px 0 18px}.intro{max-width:850px;color:#c3ced4}.meta{font-size:12px;overflow-wrap:anywhere;color:#90a1aa}.filters{position:sticky;top:0;z-index:1;background:#11191eef;padding:16px 0;display:flex;gap:12px;flex-wrap:wrap}select{font:inherit;background:#26343c;color:white;border:1px solid #71848f;border-radius:6px;padding:8px 12px}section{padding:26px 0;border-top:1px solid #34444d}.eyebrow{color:#f1c26c;font-size:13px;letter-spacing:.06em;margin:0}.shots{display:grid;grid-template-columns:repeat(3,1fr);gap:16px}figure{margin:0}img{display:block;width:100%;border-radius:6px;background:#23323b}figcaption{font-size:14px;margin-top:7px;color:#b9c7cf}a{color:#87cef5}section[hidden]{display:none}@media(max-width:800px){body{padding:20px}.shots{grid-template-columns:1fr}h1{font-size:30px}}
</style>
<p class="eyebrow">WRECK RIFF · РЕВИЗИЯ ПЛАНИРОВОК 3</p><h1>Три места для автомобильного боя</h1>
<p class="intro">16 районов и девять сквозных интерьеров. Кадры сняты в настоящем окне при движении камеры вдоль авторских маршрутов. Нажмите изображение, чтобы открыть полный размер.</p>
<p class="intro">Этот обзор помогает оценить назначение сооружений, читаемость дороги, ориентиры и пространство внутри зданий. Статичные кадры не подтверждают отсутствие мерцания во времени, удобство управления или производительность. Художественная приёмка владельцем остаётся отдельной.</p>
<p class="meta">Исходная сборка: {{IDENTITY}} · {{UNIQUE}} различных изображений · <a href="review.json">Данные съёмки</a></p>
<div class="filters"><select id="arena" aria-label="Карта"><option value="">Все карты</option><option value="construction_17">МЕГАСТРОЙ-17</option><option value="neon_zero">НЕОН-РАЙОН ZERO</option><option value="euphoria_park">ЛУНАПАРК ЭЙФОРИЯ</option></select><select id="kind" aria-label="Вид участка"><option value="">Районы и интерьеры</option><option value="district">Районы</option><option value="interior">Интерьеры</option></select></div>
{{CARDS}}
<script>const arena=document.getElementById('arena'),kind=document.getElementById('kind');function filter(){document.querySelectorAll('section').forEach(s=>s.hidden=(arena.value&&s.dataset.arena!==arena.value)||(kind.value&&s.dataset.kind!==kind.value))}arena.addEventListener('change',filter);kind.addEventListener('change',filter);</script></html>
'@
$html=$html.Replace('{{IDENTITY}}',$identity).Replace('{{UNIQUE}}',[string]$imageHashes.Count).Replace('{{CARDS}}',($cards -join "`n"))
$destination=Join-Path $reviewRoot 'index.html'
[IO.File]::WriteAllText($destination,$html,[Text.UTF8Encoding]::new($false))
Write-Output $destination

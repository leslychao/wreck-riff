# Диагностика кадров и проверка окончательного Windows ZIP

Все контрольные запуски выполняются из **новой распаковки одного окончательного ZIP**.
Версия, SHA-256 исходников, JAR и ZIP берутся из пакета. Исходный app-image и прежние
отчёты не заменяют проверку этого файла.

После завершения изменений подготовить кандидата штатными Gradle-задачами:

```powershell
.\tools\prepare-windows-release.ps1
$build = Get-Content .\build\reports\release-build-verification.json -Raw | ConvertFrom-Json
$zip = (Get-ChildItem -LiteralPath (Join-Path $build.buildRoot 'distributions') -Filter 'WreckRiff-*-windows-x64.zip' -File).FullName
```

Скрипт использует Microsoft JDK 21 и отдельный каталог сборки внутри
`build/release-output`. `clean` очищает только эту сборку; старые видео, отчёты
и app-image сохраняются. Изменение исходников или проверочных файлов во время сборки
останавливает подготовку. Успешные тесты и упаковка оставляют **кандидата в релиз**.

Короткий запуск собирает диагностические данные:

```powershell
.\tools\test-windows-benchmark.ps1 -ZipPath $zip -Seconds 60 -Arena construction_17
```

Он сообщает `DIAGNOSTIC_COMPLETE` и не подтверждает производительность релиза.
Полный runtime-замер сообщает `BENCHMARK_MEASURED`; это тоже ещё не `PASS`:
wrapper проверяет внешний Windows working set, привязку процесса и завершение.

Для одной карты:

```powershell
.\tools\test-windows-benchmark.ps1 -ZipPath $zip -Seconds 600 -Arena construction_17
```

Полная матрица включает **шесть** карт: `dead-air-yard`, `construction_17`,
`neon_zero`, `euphoria_park`, `ash_necropolis`, `doomsday_arena`.
Отчёты записываются отдельно в `build/reports/windows-benchmark-<arena>.json`.
Внутри каждого wrapper-отчёта `diagnostic.path`, `memory.path`, `stdout.path`
и `stderr.path` сопровождаются SHA-256. Каталог каждого запуска уникален.

Условия `PASS`: минимум 30 секунд активного прогрева, затем минимум 600 измеренных
секунд `ARENA_COMBAT`/`BOSS_COMBAT`; framebuffer 1920×1080, MSAA 4, работающий
звук и видимое окно, VSync и подробное профилирование выключены; p95 ≤16,7 мс,
p99 ≤25 мс, ни одного боевого кадра >100 мс, нулевое потерянное время симуляции,
peak working set процесса ≤1,5 GiB. Для новых карт требуются наблюдения обычного боя,
босса, native-подброса и эффектов в измеренном интервале. Наличие эффектов само по себе
не доказывает насыщенность сценария и не заменяет проверку боевых ситуаций.
Необработанная ошибка в другом потоке или native-crash в stdout/stderr отклоняет
прогон даже при коде выхода 0.

Полный технический запуск выполняет smoke, шесть benchmark и soak последовательно:

```powershell
.\tools\test-windows-release.ps1 -ZipPath $zip -RunAutomated
```

Soak требует минимум 1800 секунд, 10 смен карт и 20 Retry. В отчёте сохраняются
`soakCoverage`, фактические `resourceSnapshots` LOAD/UNLOAD и `resourceChecks`.
После инициализации всех арен и режимов дуэли устанавливается фиксированная база
удержанных ресурсов. Проверяются тела, listeners, projectiles, голоса, очередь
сохранений, native trackers, direct buffers, GPU textures и внешний Windows handle
count. Само `status=PASS` не заменяет проверку этих наблюдений.

Новый профиль, миграцию и продолжение кампании проверяют обычным EXE без `--dev`:

```powershell
.\tools\test-windows-package.ps1 -ZipPath $zip -Mode NormalNew
.\tools\test-windows-package.ps1 -ZipPath $zip -Mode NormalMigrated -ProfileDirectory 'C:\path\to\old-profile'
.\tools\test-windows-package.ps1 -ZipPath $zip -Mode NormalContinue -ProfileDirectory 'C:\path\to\saved-campaign'
```

Профиль копируется; исходные файлы не изменяются. Сценарий выполняется в обычном интерфейсе,
затем игра закрывается через меню. `launch-summary.json` подтверждает обычный
запуск, реальные кадры, `CLOSED`, завершение shutdown и flush прогресса.
Это не автоматическое подтверждение правильности миграции или продолжения.

Итоговый gate сохраняет отдельные ожидающие пункты приёмки: профиль и кампания,
контроллер, другая чистая Windows, ощущения владельца, UX-матрица, маршруты,
лицензии и документация. Формат — `tools/fixtures/release-acceptance.example.json`.
Фактический просмотр и проверку может выполнить агент или человек: `reviewerType`
указывает автора, `observations` фиксирует проверенные пункты сценария, `artifacts`
связывает вывод с файлами по SHA-256. Для `ownerFeel` требуется настоящий отзыв
владельца с `reviewerType=human`; контроллер и другая Windows требуют наблюдения
соответствующего физического устройства и отдельной установки ОС. Проверка
лицензионных материалов не выдаёт разрешение на распространение.
После фактической проверки передать заполненный файл через `-AcceptancePath`.
Пока приёмка или технические проверки не завершены, статус остаётся
`RELEASE_CANDIDATE`, а команда возвращает ошибку с причинами в gate-отчёте.

Для экспорта уже полученных успешных технических доказательств:

```powershell
.\tools\export-verification.ps1 -ZipPath $zip
# При необходимости явно приложить прежние отчёты:
.\tools\export-verification.ps1 -ZipPath $zip -OutputDirectory '.\build\review-with-history' -HistoryDirectories '.\build\reports\v04-prior-failures'
```

Экспорт использует `release-gate-<ZIP SHA-256>.json`, проверяет связанные хеши
и сохраняет исходные отчёты без изменения. `evidence-index.json` сопоставляет
их исходные пути с файлами архива; `manifest.json` содержит хеши копий.
Отчёты и лицензии пакета берутся из ZIP. История помещается только в `history/`
и не считается текущим PASS. Незавершённая приёмка остаётся незавершённой
после экспорта.

## Разбор отдельных кадров

Время измеряется монотонными часами между кадрами и относится к фазе предыдущего
отрисованного кадра. Кадр, пересекающий смену фазы, имеет категорию `TRANSITION`.
Меню, загрузки, интро, вход босса, пауза и результаты имеют отдельные гистограммы.
Кадр завершения прогрева целиком остаётся в прогреве. Retry не сбрасывает замер.

`phaseMetrics` хранит медиану, p95/p99, максимум, количества кадров >33/50/100 мс
и 32 самых тяжёлых кадра: frame, tick, карта, фаза, участники, физические тела,
снаряды, эффекты, подбросы и голоса SFX. Покадровая запись на диск не выполняется.
Загрузки и переходы исключены из боевых процентов, но сохраняются в отчёте.

Подробный профиль запускается отдельно из новой распаковки того же ZIP:

```powershell
. .\tools\release-evidence.ps1
$profilePackage = Expand-ReleasePackage $zip (Join-Path $PWD ('build/profile-zip-' + [Guid]::NewGuid().ToString('N')))
& (Join-Path $profilePackage.image 'WreckRiff.exe') --dev --seed=42 --ai-player --arena=construction_17 --resolution=1080p --benchmark-seconds=60 --profile
```

`profile.jfr` ограничен 128 MiB и двадцатью минутами истории. Штатная конфигурация
JDK `profile` записывает allocations, GC, блокировки и файловый I/O. JSON содержит
CPU-интервалы jME AppProfiler, этапы приложения и максимумы renderer counters.
Вложенные интервалы не суммируют: `PRESENTATION` включает HUD/AUDIO,
а `SIMULATION` — этапы runtime.

GPU измеряется отдельно через четыре асинхронных renderer timer query.
Чтение выполняется только для готовых результатов; ожидание GPU не блокирует кадр.
`gpuTiming.status` сообщает `SUPPORTED` или `UNAVAILABLE`,
`frames` содержит измерения, `skippedSamples` — пропущенные запросы.
Недоступное GPU-время не подменяется CPU-временем. Запуск с `--profile`
никогда не подходит для итогового performance gate.

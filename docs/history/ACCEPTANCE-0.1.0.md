# Приёмка Wreck Riff MVP

## Срез доказательств

Дата: 2026-09-09, часовой пояс UTC+04:00. Это протокол наблюдаемых результатов, а не декларация выпуска. Исходная спецификация — [MVP_SPEC](MVP_SPEC.md), архитектурные решения — [DECISIONS](DECISIONS.md). Каждый отчёт привязан к исходникам и пакету; граница между замером производительности и финальной поставкой указана ниже.

| Источник | Наблюдаемый результат | Граница доказательства |
|---|---|---|
| `build/test-results/test/TEST-*.xml`, 2026-09-08 23:39:26–28 UTC | PASS: 123 tests, 0 failures/errors/skips | Полный набор финального source `07fa2f2a…`; `final-build.log` — exit 0, 30 с |
| `build/test-results/physicsTest/TEST-*.xml`, 2026-09-08 23:39:29–36 UTC | PASS: 40 tests, 0 failures/errors/skips | Реальная Libbulletjme 22.0.3; финальный полный набор, включая обе рампы, recovery, read-only observation и две wheel-angle регрессии |
| `build/reports/ai-batch.json` | PASS: 1 recovery за 18.2206944 bot-min, то есть 0.5488265 на 10 bot-min при пределе 1; максимальная остановка 295 ticks (2.458 с), failures `[]` | Seed 0–9, пять AI через общий `MatchRuntime`; норма проверена на этом наборе, ручная оценка поведения отдельная |
| `NativeVehicleTest` | PASS: разгон 2.833 с, торможение 1.108 с | Из stdout финального XML; допуски 2.6–3.2 / 1.0–1.5 с сохранены |
| `DrivingBoundaryTest` | PASS: ручник достигает 150° за 0.858 с, yaw в 1.3 с — 192.4°; native FPS trajectories PASS | Из stdout финального XML; допуск 0.8–1.3 с сохранён |
| `build/reports/assets/verification.json` | `TECHNICAL_PASS`: 66 assets, 59 dependencies, 4 Windows natives, errors `[]` | Текущий полный build. Artistic `NEEDS_CREATIVE_REVIEW`, distribution `REVIEW_REQUIRED` |
| `build/reports/graphics-smoke.json`, финальный source `07fa2f2a…` | PASS: реальное окно + OpenAL, один законченный матч, 20 перезапусков за 88.364079 с; errors `[]` | Run `ca4ba99a-9b33-4fd8-8df8-c81a4a63a12b`; 1280×720, VSync on, pauseTickPreserved/menuCleanupVerified `true`; ручной ввод и творческая оценка отдельные |
| `build/reports/packaged-launch.json`, финальный source `07fa2f2a…` | PASS: exit 0, кириллица/пробелы, реальный запрет записи в install tree, PATH без внешней Java и отсутствующий JAVA_HOME | Использован bundled Java 21.0.11+10-LTS; fresh Windows на отдельной машине не проверялась |
| `build/reports/negative-launch.json`, финальный source `07fa2f2a…` | PASS: unsupported arena schema → ровно одна `Invalid config arena` ошибка и exit 1 | `tools/test-invalid-config.ps1`, 2.212 с, без NPE, shutdown failure и timeout; ожидаемый diagnostic FAIL не превращается в успешный запуск |
| `build/distributions/WreckRiff/reports/package-verification.json` | `PACKAGE_STRUCTURE_VERIFIED`; ZIP собран | Bundled Java 21.0.11, launcher, runtime/legal и 4 Windows x64 natives. Сам packaging не запускает окно; final exe smoke отдельный |
| `build/reports/benchmark.json`, source `5d6ed868…` | PASS: 630.000275 с, включая 30 с прогрева и 600 с замера; 83 завершённых матча / 83 restart | Реальный fullscreen 1920×1080, VSync off, OpenAL; 792,600 активных кадров, p95 0.8 мс / p99 1.1 мс, max 18.7421 мс, 0 кадров >100 мс; errors `[]` |
| `build/reports/benchmark-process-memory.json` | PASS: peak working set 372,277,248 bytes (355.03125 MiB), 621 samples, processExited `true` | Предел 1.5 GiB соблюдён на указанном ПК; это наблюдение за процессом, а не доказательство отсутствия всех возможных утечек |
| Clean-machine launch / CI | PENDING_MANUAL / PENDING_MANUAL | Fresh Windows и реальный workflow run не отмечены пройденными по одному наличию задания или исходника |

Пути `build/...` относительны корню рабочей копии. Отчёты Gradle перезаписываются следующим прогоном; при сдаче нужно сохранить весь каталог отчётов рядом с ZIP, его SHA-256 и commit. Доказательства запуска находятся в `%LOCALAPPDATA%/WreckRiff`; новые автоматические сценарии используют `diagnostics/run-<UUID>`.

Финальный пакет: `build/distributions/WreckRiff-0.1.0-windows-x64.zip`, SHA-256 `6ba3660285c99c09288d1f948d67b14e6d65e6f06b5ec417d4c045bb81b6936a`. Встроенный `sourceSha256` — `07fa2f2a6cbf06d6199fe7a128daab0accae8c034e46973f6f0776dc0834e3c7`, commit — `0f679ca4a48d5dc637b414ab0e581a928d18a00f` с изменённой рабочей копией. Hash исходников, а не один commit, связывает её с отчётами. `final-build.log` фиксирует полный test/native/assets/package PASS за 30 с; `clean-build.log` — предыдущий полный clean/build PASS за 44 с. Самый ранний clean с заблокированным старым Gradle daemon каталогом завершился ошибкой и не учитывается как успешный.

Benchmark выполнен на source `5d6ed8689485aa30bd37d875f95ed9a37609c942da5a0bf407a2a57372bc642e`, run `5c11ec25-a008-4f4a-9ae3-265e39d12c49`. Единственное изменение игрового исходника после него — `cleanupMatch()` в ветке `GameApplication.fail` для автоматического диагностического отказа. Оно устраняет вторичную NPE старого отрицательного запуска и не исполняется в штатном бою. `build/reports/source-validation.json` независимо пересчитывает SHA по 77 файлам; возврат только этой ветки к прежнему тексту воспроизводит точный benchmark SHA. Полный набор, строгий negative test и packaged smoke повторены на финальном SHA и PASS; 10-минутный benchmark после этого изменения не повторяли.

Исторический нестабильный разворот на рампе закрыт полным native набором. Разбор raw native angles и исходника Libbulletjme 22.0.3 указал на чтение предыдущего положения колеса до инициализации transform: nonfinite roll angle затем искажает ось трения. `PhysicsWorld` ограничивает именно этот native output до обновления колёс, без изменения сил или допусков; детали и upstream-путь — [DECISIONS](DECISIONS.md). `NativeWheelInitializationTest` проверяет raw angle/delta при 12 реальных стартах на обеих наклонных поверхностях и отдельно инъецирует NaN в native post-step callback, сравнивая последующую траекторию с контрольной. Инъекция — детерминированная проверка containment, а не измерение частоты исходного дефекта: до исправления она падает (`native-wheel-regression-before.log`), после него обе проверки PASS. `NativeObservationTest` отдельно подтверждает, что чтение wheel contacts больше не меняет подвеску/траекторию; `NativeRecoveryTest` проверяет возврат только в полностью поддержанную историческую позу.

`IMPLEMENTED` означает наличие рабочей реализации. `VERIFIED_AUTO` относится только к прямо перечисленному сценарию. `IN_PROGRESS` у verification означает неполное доказательство; `FAILED` — наблюдаемую ошибку. `PENDING_MANUAL` и `NEEDS_CREATIVE_REVIEW` не являются успешными проверками.

## T01–T16: правила и чистая логика

Все классы `*Test`, если не оговорено иное, расположены в `src/test/java/game/wreckriff/`.

| ID и требуемое поведение | Реализация | Verification | Доказательство и оставшийся шаг |
|---|---|---|---|
| T01. Валидные конфиги; неизвестные/невалидные значения отклоняются | IMPLEMENTED: `Configs`, records в config/combat/arena/ai/audio/input | VERIFIED_AUTO | 5 `CombatRulesTest`, `ConfigsTest` numeric overflow, 8 `GamepadProfileTest`; `VerifyAssets` валидирует все 8 bundled config records |
| T02. Нет выстрела без ресурса, согласованы ammo/cooldown | IMPLEMENTED: `CombatSystem` | VERIFIED_AUTO | `T02_emptyAmmoDoesNotSpendCooldownOrCreateProjectile`, `T02_limitRejectsAtomicallyButAllowsHitscanAndPulse`, cooldown boundary |
| T03. Перегрев блокирует MG до заданного порога | IMPLEMENTED: `CombatSystem` | VERIFIED_AUTO | `T03_overheatBlocksUntilTheExactRecoveryThreshold`, `T03_heatHasSpecifiedRiseAndCoolingDelay` |
| T04. Переключение сохраняет таймер и не стреляет | IMPLEMENTED: `CombatSystem` | VERIFIED_AUTO | `T04_weaponSwitchPreservesCooldownAndDoesNotFire` |
| T05. Одно нажатие не размножается по физическим шагам | IMPLEMENTED: `InputSystem`, `VehicleCommand`, `CombatSystem` | VERIFIED_AUTO | `InputSystemTest.edgeSurvivesRenderFramesWithoutTicksButDoesNotRepeat`, `T05_edgesConsumedAcrossPhysicsStepsDoNotRepeatPulseOrSwitch` |
| T06. Турбо: расход, задержка regen, запрет в воздухе | IMPLEMENTED: `VehicleController` | VERIFIED_AUTO | Реальный `physicsTest/DrivingBoundaryTest.turboConsumesOnlyOnGroundAndWaitsBeforeRegenerating` PASS |
| T07. Прямой rocket damage не складывается со splash | IMPLEMENTED: `CombatSystem` | VERIFIED_AUTO | `T07_directTargetReceivesOnlyDirectDamage`; дополнительно `NativeCombatTest.directRocketHitsARealCompoundHullOnlyOnce` |
| T08. Owner получает 50% splash и не получает свой Pulse | IMPLEMENTED: `CombatSystem` | VERIFIED_AUTO | `T08_ownerTakesHalfSplashAndPulseNeverHitsOwner` |
| T09. Дубликат hit/contact не создаёт второй взрыв/смерть | IMPLEMENTED: event/shot IDs, unordered ram pair | VERIFIED_AUTO | `T09_duplicateDamageCannotDuplicateDamageOrDeath`, `T09_ramsDeduplicateAnUnorderedPairAndUseGreatestClosingSpeed`, TTL/collision same-tick; реальный многоочечный контакт P09 также PASS |
| T10. Одновременные смерти не зависят от порядка | IMPLEMENTED: общая damage-фаза и `MatchSession` | VERIFIED_AUTO | `T10_simultaneousDeathsAndDamageStatsDoNotDependOnQueueOrder`, stable kill credit tests |
| T11. Последняя победа приоритетнее time limit | IMPLEMENTED: `MatchSession.finishTick` | VERIFIED_AUTO | `T11_finalTickEliminationWinsBeforeTimeLimitAndResultIsStable`, `SimulationLoopTest.lastTickVictoryAndSimultaneousDestructionHaveDefinedPriority` |
| T12. Полный ресурс не подбирается; мёртвый не воскресает | IMPLEMENTED: `ArenaSystems` | VERIFIED_AUTO | `ArenaRulesTest.fullDeadProtectedOccludedAndWrongFloorVehiclesCannotCollect` |
| T13. Однократный подбор, deterministic tie и этаж | IMPLEMENTED: `ArenaSystems`, `WorldQuery` | VERIFIED_AUTO | `repairIsAtomicAndTieBreaksByVehicleId`, `fullDeadProtectedOccludedAndWrongFloorVehiclesCannotCollect` |
| T14. Пауза замораживает время, HP, cooldown, regen и hazard | IMPLEMENTED: `ScreenFlow`, `SimulationLoop`, общий tick | VERIFIED_AUTO | `NativePauseTest.T14_pauseFreezesFullRuntimeAndResumesWithoutTimeDebt` PASS: полный snapshot и продолжение на границе шага без долга времени. Физический ввод/focus отдельно в I03 |
| T15. Сломанные настройки восстановлены с сохранением исходника | IMPLEMENTED: `SettingsStore` | VERIFIED_AUTO | 7 `SettingsStoreTest`: повреждение, future schema, недоступная запись, валидация, запись завершённого матча один раз |
| T16. Seed повторяет AI/оружие, audio RNG независим | IMPLEMENTED: отдельные seeded RNG участников/AI/audio | VERIFIED_AUTO | `BotControllerTest` deterministic decisions; `T16_weaponSpreadIsRepeatableAndIndependentOfUnrelatedSoundRandomness` |

## P01–P12: нативная физика

Классы в `src/physicsTest/java/game/wreckriff/` используют реальный `PhysicsSpace`, а не fake `WorldQuery`. Чистые тесты дополняют, но не заменяют эту таблицу.

| ID и требуемое поведение | Реализация | Verification | Доказательство и оставшийся шаг |
|---|---|---|---|
| P01. Создать, step и освободить native world | IMPLEMENTED: `PhysicsWorld` | VERIFIED_AUTO | `NativeVehicleTest.nativeFloorSupportsFourCorrectlyOrientedWheelsAndCleansUp` |
| P02. Устойчивый кузов, правильные колёса/оси | IMPLEMENTED: `PhysicsWorld.addVehicle`, `VehicleController` | VERIFIED_AUTO | Тот же тест: 4 контакта; `positiveSteerTurnsTowardPositiveX` |
| P03. Разгон и торможение в диапазоне | IMPLEMENTED: `VehicleController`, vehicle.json | VERIFIED_AUTO | `accelerationAndBrakingMeetMvpTargetsUsingForces`: 2.833 / 1.108 с при диапазонах 2.6–3.2 / 1.0–1.5 с |
| P04. Поворот/ручник, 30/60/144 FPS, допуск 5% | IMPLEMENTED: fixed tick, силы/torque | VERIFIED_AUTO | `nativeTrajectoriesAgreeAtThirtySixtyAnd144RenderFps` PASS; `handbrakeReversesCourseWithinAgreedWindowWithoutFlipping`: 150° за 0.858 с, yaw в 1.3 с — 192.4° |
| P05. Обе рампы и стыки проезжаются | IMPLEMENTED: `ArenaFactory`, shared geometry | VERIFIED_AUTO | `NativeArenaTest.bothRampsAndDeckSeamsArePassableByOrdinaryThrottle` |
| P06. Тонкая стена и препятствие у капота | IMPLEMENTED: swept logical projectile, muzzle guard | VERIFIED_AUTO | `NativeVehicleTest.staticAndRelativeSweepsSeeThinWallAndMovingTarget`, `NativeCombatTest.P06_wallBetweenWeaponBaseAndMuzzleStopsRocketBeforeSpawn` |
| P07. Движущаяся цель пересекает ракетный путь | IMPLEMENTED: relative sweep `PhysicsWorld.sweep` | VERIFIED_AUTO | `staticAndRelativeSweepsSeeThinWallAndMovingTarget` задаёт реальные предыдущую/текущую позы цели и проверяет пересечение |
| P08. Взрыв и Pulse блокируются стеной и перекрытием | IMPLEMENTED: hull visibility samples | VERIFIED_AUTO | Native Pulse: видимая машина/импульс, стена, перекрытие — PASS; `P08_powerRocketSplashCannotCrossAClosedWall` и `P08_powerRocketSplashCannotCrossAnUpperFloor` с видимой контрольной машиной также PASS |
| P09. Много контактов даёт один ram на пару/cooldown | IMPLEMENTED: contacts → `queueRam`, stable pair | VERIFIED_AUTO | `P09_multipleNativeContactPointsDamageEachPairOncePerCooldown` PASS: distinct contact points, pre-step closing speed, HP на каждом тике и на границе cooldown; чистые T09 дополняют native |
| P10. Recovery свободен от стен/машин, платит один раз | IMPLEMENTED: safe poses, chassis sweep, `VehicleController` | VERIFIED_AUTO | `DrivingBoundaryTest` occupied/emergency scenarios и `NativeRecoveryTest.recoveryRejectsNewerTwoWheelEdgePoseAndUsesFullySupportedHistory` PASS: неподдержанная краевая поза отвергается |
| P11. Достижимы все spawns/pickups, нет воздушных floor edges | IMPLEMENTED: `ArenaDefinition`, `NavGraph`, проверка full chassis | VERIFIED_AUTO | `NativeArenaTest`: все 5 spawns, оба ремонта и отдельный реальный подбор каждой из 6 ammo/turbo pickup PASS; deck exit/rail, свободный стартовый коннектор и разворот на обеих рампах PASS в полном наборе |
| P12. Боты находят оба ремонта и выезжают из гаража | IMPLEMENTED: `BotController`, graph, steering | VERIFIED_AUTO | `NativeArenaTest.aiReachesBothRepairsAndDrivesOutOfGarageWithoutRecovery`, встречный проезд и полный batch 10 seeds PASS; recovery rate 0.5488265 на 10 bot-min, максимальная остановка 295 ticks |

## I01–I05 и A01–A03: интеграция и поставка

| ID и требуемое поведение | Реализация | Verification | Доказательство и оставшийся шаг |
|---|---|---|---|
| I01. Menu → Countdown → Running → Result → Retry | IMPLEMENTED: `GameApplication`, `ScreenFlow`, `MatchRuntime` | VERIFIED_AUTO | Финальный packaged smoke зафиксировал реальные переходы и естественный результат; ручная навигация кнопками остаётся отдельной проверкой |
| I02. 20 Retry без накопления bodies/listeners/projectiles/voices | IMPLEMENTED: lifecycle close/cleanup и `DiagnosticEvidence` | VERIFIED_AUTO | Финальный packaged smoke: 20 restart сохраняют baseline, pause/menu cleanup PASS. Длительный benchmark: 83 restart, peak working set 355.03125 MiB |
| I03. Alt-Tab/отключение контроллера ставят паузу и снимают ввод | IMPLEMENTED: `loseFocus`, GLFW disconnect, input suppression | PENDING_MANUAL | Unit input edges/suppression PASS. Фактический контроллер, его disconnect/reconnect, удержанные кнопки при Resume и реальный Alt-Tab ещё не подтверждены |
| I04. В app-image есть Java runtime и Windows natives | IMPLEMENTED: `package-windows.ps1` | VERIFIED_AUTO | Текущий package report проверяет launcher, Java 21.0.11 runtime/legal и 4 Windows x64 native PE entries; графический запуск отдельный |
| I05. Пробелы/кириллица, read-only install, без Java | IMPLEMENTED: `NativeSetup`, `SettingsStore`, jpackage image | VERIFIED_AUTO | Финальный packaged smoke PASS с кириллицей/пробелами, запретом записи и изоляцией внешней Java. Это не заменяет отдельную fresh Windows без Java |
| A01. Непустые assets, происхождение и разрешённый статус | IMPLEMENTED: генераторы, manifest, license index | PENDING_MANUAL | Текущий manifest: TECHNICAL_PASS, 66 assets / 59 dependencies / 4 natives. Creative review и distribution review открыты, разрешение не выводится из SHA |
| A02. WAV корректны, mono/stereo соответствуют, нет clipping | IMPLEMENTED: `GenerateAudio`, `PcmWave`, `VerifyAssets` | VERIFIED_AUTO | `AudioAssetsTest`, `PcmWaveTest`, 7 `VerifyAssetsTest`; сравнение фактического PCM с metrics, RIFF/data size, channel/rate/bits, peak/RMS/SHA. Оценка музыки человеком отдельная |
| A03. Нет TM4 assets, секретов, случайных DLL и dev override | IMPLEMENTED: original generators, package filtering, resource path checks | PENDING_MANUAL | Текущий package gate прошёл фильтрацию ресурсов, pinned natives и отсутствие test/legacy Bullet jars. Источники используют original recipes; distribution review ещё ожидается. Поиск имён не является исчерпывающим доказательством отсутствия секретов |

## Передача доказательств и внешние проверки

Все доступные автоматические и реальные графические сценарии завершены: test/native/assets/package, benchmark, strict negative и финальный packaged smoke PASS. Архив протокола для передачи — `build/distributions/WreckRiff-0.1.0-verification.zip` с соседним SHA-256: reports, XML, документация и реальные captures. При передаче сохранять его вместе с игровым ZIP.

Подготовленный Windows workflow не запускался во внешнем CI. Его запуск требует доступной среды/репозитория и разрешения; локальный clean/build не доказывает clean checkout или успешный GitHub Actions run. Статус: `PENDING_MANUAL`.

## Ручной smoke-набор

Для каждого выполнения записать дату, версию/commit/SHA ZIP, Windows build, GPU/driver, CPU/RAM, устройство звука, модель и подключение контроллера, seed, ожидаемое/наблюдаемое, ссылку на отчёт/capture. Пока результат не записан, строка остаётся `PENDING_MANUAL`. В этой среде нет подключённого mapped gamepad и отдельной fresh Windows; native UI-control tools недоступны для реального keyboard/mouse/focus/video сценария. Автоматический driver и программный переход экранов не выдаются за человеческий ввод. Эти пункты передаются владельцу.

| Сценарий | Статус | Критерий |
|---|---|---|
| Чистая Windows без Java; путь `Игры / Wreck Riff` | PENDING_MANUAL | Реальное окно из распакованного app-image, нормальный бой и выход |
| Запрет записи в каталог установки | VERIFIED_AUTO | Финальный packaged smoke PASS с реальным запретом записи; extraction/settings/logs работают вне install tree |
| Полный бой клавиатурой/мышью | PENDING_MANUAL | Все атаки, верхняя площадка, recovery, результат, Retry и выход |
| Полный бой выбранным физическим геймпадом | PENDING_MANUAL | Указать модель; меню, RT/LT, стик, все кнопки, одновременный MG/rocket |
| Отключение/возврат контроллера, Alt-Tab, Resume | PENDING_MANUAL | Пауза без ticks; удержанный ввод не протекает, новое нажатие принимается |
| Видеорежим с отменой и с подтверждением | PENDING_MANUAL | Обратный отсчёт 10 с работает и при остановленной симуляции; настройки сохранены |
| Музыкальная петля и бой более одного цикла | PENDING_MANUAL | Нет щелчка/провала, оружие слышно, музыка не исчезает при интенсивном бое |
| Победа / поражение / ничья, 20 Retry | PENDING_MANUAL | Правильный результат и статистика, без старых объектов и ресурсного роста |
| Выход при отключённом звуке | PENDING_MANUAL | Без исключения/зависания; выключение аудио не маскирует основной audio smoke |

## Производительность и память

Обязательный сценарий: 1920×1080, VSync off, пять машин, 30 с прогрева, затем 10 мин повторяющихся боёв. Порог p95 ≤16.7 мс, p99 ≤25 мс, нет необъяснённых пауз >100 мс в активной игре; working set ≤1.5 ГБ. Load/меню/пауза должны быть отделены от активных frame times.

Benchmark: **PASS**. `benchmark.json` содержит 630.000275 с общего времени: 30 с прогрева и 600 с повторяющихся боёв. Завершены 83 матча и 83 перезапуска; после прогрева активный бой занимает 358.67697 с и 792,600 кадров. Загрузка, countdown и результаты исключены из histogram, но входят в длительность сценария. Реальный framebuffer 1920×1080, fullscreen, VSync off и звук включён; p95 = 0.8 мс, p99 = 1.1 мс, max = 18.7421 мс, кадров >100 мс — 0. Точность histogram 0.05 мс. Предыдущая оконная попытка с высотой 1055 была остановлена до валидного замера и не включена.

`benchmark-process-memory.json`: peak working set 372,277,248 bytes (355.03125 MiB), 621 измерение, процесс завершился. Максимальный heap в точках контроля циклов — 80.2864 MiB, direct buffers — 0.168213 MiB; это sampled counters, а не общий peak всех JVM/native allocations. Перезапуски сохраняют baseline тел/listeners/projectiles/voices. В исходных отчётах сохранены отдельные наблюдения вне измеряемого активного боя: в первые 30 с прогрева match 1 имеет max frame 129.669 мс; на countdown перед match 32 и 50 отброшено соответственно 0.0267664 и 1.2747348 с долга времени. Эти события не скрыты за активными процентилями и не считаются кадрами активного боя после прогрева.

Платформа: Core i9-14900F, 68,490,100,736 bytes RAM, Windows 11 build 26200, RTX 5080 / NVIDIA 610.62 / Java 21.0.11+10-LTS. Результат характеризует этот ПК и сценарий; минимальные требования и все возможные утечки не определены. Измеряемый source SHA и единственное последующее исправление ветки отказа указаны в срезе доказательств. Финальный packaged smoke: **PASS**, 88.364079 с, один законченный матч и 20 restart, errors `[]`.

## Приёмка владельцем

Статус: **NEEDS_CREATIVE_REVIEW**. Выполнить пять упражнений на том же контроллере и сопоставимом экране: разгон/торможение; ручник; преследование; ракеты у препятствия; полный бой с музыкой. Сравнение с личным оригинальным референсом не требует копирования его файлов в проект.

| Категория | Порог | Оценка владельца |
|---|---|---|
| Управление | ≥4/5 | Не получена |
| Камера | ≥4/5 | Не получена |
| Стрельба | ≥4/5 | Не получена |
| Музыка / микс | ≥4/5 | Не получена |
| Атмосфера | ≥4/5 | Не получена |
| Желание повторить бой | Да | Не получено |

Замечание фиксируется как ситуация → ожидаемое → наблюдаемое, build/seed и группа параметров до/после. Средняя оценка не компенсирует провал категории. Отдельно владелец подтверждает пригодность происхождения/лицензий для намеченного распространения; OpenAL Soft corresponding-source/replacement review остаётся явным пунктом [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md).

По §1.4/20.3 обязательные системы реализованы, интегрированы и прошли все доступные проверки; FUNCTIONAL_COMPLETE присвоен в этой границе. Перечисленные ручные UI/hardware/fresh Windows проверки, внешнее CI, приёмка ресурсов/прав и оценка владельца остаются открытыми. Это не MVP_ACCEPTED и не разрешение публикации.

FUNCTIONAL_COMPLETE = YES; FEEL_APPROVED = NO; MVP_ACCEPTED = NO.

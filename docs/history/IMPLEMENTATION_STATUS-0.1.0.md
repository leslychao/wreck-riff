# Реализация Wreck Riff MVP

Срез: 2026-09-09. **FUNCTIONAL_COMPLETE = YES:** обязательная реализация, исходные ресурсы, интеграция, доступные проверки и Windows-пакет завершены. Ручная приёмка владельцем и внешние проверки перечислены отдельно. Полный протокол T01–T16, P01–P12, I01–I05, A01–A03 и ссылки на доказательства находятся в [ACCEPTANCE](ACCEPTANCE.md). Инструкции — [README](../README.md), принятые решения — [DECISIONS](DECISIONS.md).

Полный запуск финального source `07fa2f2a…`, 2026-09-08 23:39 UTC: **123 чистых теста и 40 native тестов PASS, failures/errors/skips = 0**. P08/P09/T14, все P11 pickup routes, разворот на обеих рампах, `NativeRecoveryTest`, `NativeObservationTest` и обе проверки `NativeWheelInitializationTest` PASS. Batch 10 seeds PASS: 1 recovery за 18.2206944 bot-min = 0.5488265 на 10 bot-min при пределе ≤1; максимальная остановка 295 ticks (2.458 с). Историческая нестабильность рампы, native-angle containment и границы инъекционной регрессии описаны в ACCEPTANCE и DECISIONS; последний полный набор не содержит failures.

Текущий `verifyAssets` — **TECHNICAL_PASS: 66 assets / 59 dependencies / 4 natives**; Windows app-image/ZIP — **PACKAGE_STRUCTURE_VERIFIED**. `clean-build.log` фиксирует предыдущий полный clean/build PASS за 44 с; `final-build.log` — финальный полный PASS за 30 с. SHA исходников и ZIP указаны в [ACCEPTANCE](ACCEPTANCE.md), чтобы не расходились копии идентификаторов. Benchmark **PASS**: 30 с прогрева + 600 с сценария, 83 завершённых матча/restart, 1080p fullscreen/VSync off, p95 0.8 мс, p99 1.1 мс, peak working set 355.03125 MiB. Финальный packaged real-window smoke **PASS**: 88.364079 с, один законченный матч, 20 restart, пауза/очистка меню и exit 0 без ошибок. Кириллица/пробелы, read-only install и изоляция внешней Java подтверждены. Fresh Windows, физический контроллер, ручной UI, творческая оценка, права и CI остаются отдельными открытыми проверками.

Benchmark измерял `5d6ed868…`; финальная версия `07fa2f2a…` отличается только cleanup-веткой диагностической ошибки загрузки. `source-validation.json` подтверждает эту границу повторным расчётом SHA. Строгий `test-invalid-config.ps1` на финальном пакете **PASS**: ровно одна ожидаемая ошибка конфигурации, exit 1, без вторичной NPE/shutdown failure. Полные test/native/assets/package проверки повторены; 10-минутный замер после изменения ошибочной ветки не повторялся. Штатный игровой путь не менялся.

## Этапы M0–M6

| Этап | Implementation | Verification | Реализация, доказательство и остаток |
|---|---|---|---|
| M0. Основа | IMPLEMENTED | PENDING_MANUAL | Один Java 21/Gradle проект, pinned jME/Minie, Wrapper и locks, `Main`, native loader, исходные генераторы. Реальное окно/OpenAL, P01, app-image structure и bundled Java launch PASS. Внешний clean checkout CI и fresh Windows ещё не подтверждены |
| M1. Движение, камера, музыка | IMPLEMENTED | PENDING_MANUAL | `VehicleController`, `PhysicsWorld`, `ChaseCamera`, `InputSystem`, `AudioDirector`; P02–P05 и native FPS trajectories PASS. Camera/controller/feel review ожидается |
| M2. Бой | IMPLEMENTED | VERIFIED_AUTO | `CombatSystem`, projectiles, HP, lock, Pulse, visual/audio events и HUD; T02–T11, native muzzle/sweep/Pulse, blast occlusion и многоочечный ram PASS. Оценка ощущений входит в ручную приёмку |
| M3. Арена и AI | IMPLEMENTED | VERIFIED_AUTO | `ArenaFactory`, graph, `ArenaSystems`, `BotController`, общий `MatchRuntime`; все pickup routes, обе рампы/развороты и batch 10 seeds PASS, recovery rate 0.5488265 на 10 bot-min. Ручная оценка боя отдельная |
| M4. Характер и аудиовизуальная часть | IMPLEMENTED | NEEDS_CREATIVE_REVIEW | Оригинальные mesh, arena, CombatParticles, 180-секундная музыкальная композиция и mono SFX, собственный bitmap-шрифт. PCM/font/voice budget unit checks PASS; реальный полный loop/mix и оценка владельца ожидаются |
| M5. Пользовательский контур | IMPLEMENTED | PENDING_MANUAL | `ScreenFlow`, меню/результаты/настройки/Controls/Credits/HUD/help, persistence, input suppression, gamepad profile. T05/T14/T15 и финальные I01/I02 smoke PASS. Физический контроллер/focus, видеонастройки и ручная навигация ожидают доступного устройства/ручного ввода |
| M6. Проверки и передача | IMPLEMENTED | PENDING_MANUAL | Полные `test`/`physicsTest`, AI batch, `verifyAssets`, Windows package, strict negative, benchmark и финальный smoke PASS. Отчёты/README/ACCEPTANCE/STEAM_HANDOFF и Windows workflow подготовлены. Открыты внешний CI и перечисленные ручные gates |

## Требования R01–R19

| ID / требование из спецификации | Implementation | Verification | Конкретное место и доказательство / пробел |
|---|---|---|---|
| R01. Java, без C# | IMPLEMENTED | VERIFIED_AUTO | `build.gradle`: Microsoft Java 21 toolchain, application main; Java main/tools/test/native suites компилировались и запускались. Отдельный clean checkout CI ещё ожидается в R18 |
| R02. Один источник физики | IMPLEMENTED | VERIFIED_AUTO | `SimulationLoop` → `MatchRuntime` → один `PhysicsWorld.step()` на tick 1/120 с; visual читает состояние. P01 и native trajectories 30/60/144 PASS |
| R03. Управление / ручник / турбо | IMPLEMENTED | PENDING_MANUAL | `VehicleController`, `PhysicsWorld.addVehicle`, vehicle.json. Разгон 2.833 с, торможение 1.108 с, ручник 150° за 0.858 с; земля/воздух/regen/recovery PASS в исходных допусках. Ощущения ожидают владельца |
| R04. Камера и обзор | IMPLEMENTED | PENDING_MANUAL | `presentation/ChaseCamera.java`, camera.json: следование, collision probes, обзор назад. Нужен ручной гараж/обе рампы/резкий разворот и оценка ≥4/5 |
| R05. Клавиатура и геймпад | IMPLEMENTED | PENDING_MANUAL | `InputSystem`, `GamepadProfile`, Controls/remapping. T05 и keyboard/mouse suppression PASS; модель физического контроллера, триггеры, disconnect и I03 пока не зафиксированы |
| R06. Три оружия и Pulse | IMPLEMENTED | VERIFIED_AUTO | `combat/CombatSystem.java`, combat.json, `CombatVisuals`, `AudioDirector`; T02–T09, native P06/P07/P08, включая rocket splash occlusion, PASS. Ручной путь button→effect проверяется вместе с физическим вводом |
| R07. Урон / тараны / смерть | IMPLEMENTED | VERIFIED_AUTO | Общая resolveDamage фаза, shot/event dedup, owner splash, stable kill credit/5s window, предельные impulses. T07–T11 и P09 native multiple contacts/cooldown PASS |
| R08. Законченный матч | IMPLEMENTED | VERIFIED_AUTO | `MatchSession`, `MatchRuntime`, `ScreenFlow`, результаты/Retry. T10–T11, 10 полных native матчей и финальный I01 real-window smoke PASS |
| R09. Предметы и hazard | IMPLEMENTED | VERIFIED_AUTO | `arena/ArenaSystems.java`; T12/T13, hazard intervals/floor, T14 полный pause snapshot и P11 все native pickup routes PASS |
| R10. Оригинальная арена | IMPLEMENTED | NEEDS_CREATIVE_REVIEW | `ArenaDefinition`, `ArenaFactory`, `ArenaPresentation`, arena.json. P05/P11/P12, все spawns/pickups, обе рампы и deck/garage routes PASS; реальные captures получены. Визуальная приёмка владельцем ожидается |
| R11. Четыре бота | IMPLEMENTED | VERIFIED_AUTO | `BotController`, `NavGraph`, ai.json, native shared `MatchRuntimeTest`. 10 seeds 0–9 PASS: 1 recovery / 18.2206944 bot-min = 0.5488265 на 10 bot-min; максимальная остановка 295 ticks <600. Развороты на обеих рампах PASS в полном наборе |
| R12. Музыка и микс | IMPLEMENTED | NEEDS_CREATIVE_REVIEW | `GenerateAudio`, `AudioDirector`, `PcmWave`, audio.json; A02, voice limits и streaming lifecycle tests PASS. Финальный OpenAL smoke и длительный benchmark PASS. Человеческое прослушивание полного loop, микс и оценка ≥4/5 ещё не приняты |
| R13. Авторский визуальный стиль | IMPLEMENTED | NEEDS_CREATIVE_REVIEW | `VehicleVisual`, `ArenaPresentation`, `CombatVisuals`, `CombatParticles.*`, `GenerateFont`. Тесты mesh/particle budgets/font и финальный smoke PASS; реальные captures получены. Личная оценка атмосферы ожидается |
| R14. Настройки / сохранения | IMPLEMENTED | PENDING_MANUAL | `SettingsStore`, schema 1 JSON, атомарная запись, backup/future-version handling, UI настройки. 7 T15 tests и I05 PASS. Ручные I03 и video rollback/confirm не проверены: native UI-control tools недоступны |
| R15. Нет утечек между матчами | IMPLEMENTED | VERIFIED_AUTO | `MatchRuntime.close`, `CombatSystem.clear`, audio cleanup, `GameApplication.cleanupMatch`. Benchmark 83 restart сохраняет baseline, peak working set 355.03125 MiB; финальный I02 packaged smoke 20 restart PASS. Результат ограничен измеренными сценариями |
| R16. Windows без Java | IMPLEMENTED | PENDING_MANUAL | Текущий app-image содержит Java 21.0.11 и 4 natives, I04 PASS. Финальный packaged smoke подтвердил bundled runtime при изоляции внешней Java. Fresh Windows без Java остаётся отдельной ручной проверкой |
| R17. Ресурсы и права | IMPLEMENTED | PENDING_MANUAL | Original recipes, `ASSET_REGISTER.csv`, `license-index.json`, `THIRD_PARTY_NOTICES`, manifest/coordinates/hashes. Текущий technical gate PASS: 66 assets / 59 deps / 4 natives; distribution review в том числе OpenAL Soft открыт |
| R18. Воспроизводимость | IMPLEMENTED | PENDING_MANUAL | Wrapper 8.14.3 + SHA, dependency locks, deterministic generators, Java 21 pin, `.github/workflows/verify.yml`. Локальный clean/build и текущий ZIP PASS; генерация font дважды дала одинаковые hashes. Настоящий clean checkout CI требует внешней среды/разрешения; byte-identical jpackage ZIP не заявляется |
| R19. Нет ложной готовности | IMPLEMENTED | VERIFIED_AUTO | Этот статус и ACCEPTANCE отдельно показывают implementation/verification/evidence и известные FAIL/PENDING. FEEL_APPROVED и MVP_ACCEPTED без оценки владельца не присвоены |

## Передача и оставшиеся gates

- Исходники/конфиги/генераторы и команды: [README](../README.md).
- Матрица обязательных автоматических/ручных проверок, hardware/performance и оценка владельца: [ACCEPTANCE](ACCEPTANCE.md).
- Происхождение assets: [ASSET_REGISTER](ASSET_REGISTER.csv), [AUDIO_DESIGN](AUDIO_DESIGN.md); third-party evidence и открытые условия распространения: [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md).
- Windows app-image и игровой ZIP находятся в `build/distributions`; финальные наличие/структура/запуск подтверждены отчётами. Архив протокола передачи — `WreckRiff-0.1.0-verification.zip` с соседним SHA-256, reports/XML/docs/captures; идентификаторы сборки — в ACCEPTANCE.
- Будущая конфигурация Steam, реальные возможности и действия владельца: [STEAM_HANDOFF](STEAM_HANDOFF.md). Публикация и отправка на review не выполнялись.

Полные автоматические наборы, benchmark, строгий negative test и финальный packaged smoke PASS. По §1.4/20.3 обязательная реализация и все доступные проверки завершены: FUNCTIONAL_COMPLETE = YES. Недоступные в этой среде manual UI/focus/video, физический геймпад, fresh Windows и внешний CI имеют отдельный PENDING_MANUAL; права/распространение и оценка владельца также открыты. FEEL_APPROVED требует владельца, а MVP_ACCEPTED — дополнительно обязательные Windows/gamepad проверки и подтверждённые права. Ни один из этих пунктов не объявлен PASS из-за отсутствия средства проверки.

FUNCTIONAL_COMPLETE = YES; FEEL_APPROVED = NO; MVP_ACCEPTED = NO.

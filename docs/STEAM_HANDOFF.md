# Передача Wreck Riff для будущего Steam

Это описание локальной Windows-поставки и оставшейся работы владельца. Проект не содержит Steam AppID, Steam credentials, Steamworks SDK или сценария публикации. Ни ZIP, ни CI artifact не означают одобрение build или страницы магазина.

## Пакет и запуск

| Поле | Значение |
|---|---|
| Версия проекта | `0.3.0`, источник — `build.gradle` |
| Сборочная команда | `./gradlew.bat packageWindows` на Windows с Microsoft JDK 21 |
| Локальный app-image | `build/distributions/WreckRiff/` |
| ZIP | `build/distributions/WreckRiff-0.3.0-windows-x64.zip` |
| SHA-256 | Соседний файл `.zip.sha256`; проверить перед передачей |
| Launcher относительно корня содержимого depot | `WreckRiff.exe` |
| Обычные аргументы запуска | Пустые; `--dev` не нужен игроку |
| Целевая архитектура | Windows x64 |
| Подпись | `UNSIGNED_MVP` |

Предлагаемое содержимое Windows depot — **содержимое** каталога `WreckRiff`, без дополнительного вложенного `WreckRiff/` перед лаунчером:

```text
WreckRiff.exe
app/                 # application JAR, unmodified dependency JARs, launcher config
runtime/             # bundled Java 21, native runtime, legal notices
licenses/            # exact dependency notices and evidence
reports/             # asset manifest and package verification
README.txt
```

Сохранять структуру целиком. Настройки, локальная статистика, логи, снимки и native cache находятся в `%LOCALAPPDATA%/WreckRiff`, а не в depot. `--smoke-seconds` и `--benchmark-seconds` используют отдельный профиль `diagnostics/run-<UUID>`.

Не включать Gradle caches, исходную рабочую копию, пользовательские settings/stats/logs, captures с личными данными, credentials или локальные dev override. Штатный script собирает пакет из `installDist` и разрешённых ресурсов; при любой дополнительной перепаковке manifest и SHA нужно проверить заново.

## Что фактически реализовано

Один локальный игрок и четыре AI; Rivet с пятью ливреями и двухуровневая промышленная арена; пулемёт, Homing/Power Rocket, Mine, Napalm, Ballistic, Cannon; Freeze/Shield; ремонты/боеприпасы/hazard; результаты и Retry; локальные настройки и статистика. В 0.2 переработаны геометрия, текстуры, освещение, тени, шрифт и музыка. Игрок имеет 800 HP, боты 400 HP, перегрева и стартового отсчёта нет. Интерфейс — английский, документация разработчика — русская. Клавиатура/мышь и Xbox-совместимый GLFW-профиль предусмотрены кодом.

Сетевой multiplayer, достижения Steam, Steam Cloud, Steam Overlay API, Steam Input API, Workshop и Steam Deck certification не реализованы. Не указывать эти возможности как подтверждённые свойства страницы. Физический контроллер и полноту навигации им нужно принять отдельно прежде, чем заявлять соответствующий уровень controller support.

## Фактически проверенная платформа

Единый источник актуальных SHA исходников/ZIP, hardware, test/native/package/runtime reports и численных замеров — [ACCEPTANCE](ACCEPTANCE.md). Отчёты 0.1 сохранены в docs/history и не являются подтверждением новой сборки 0.3. Минимальные системные требования, физический контроллер и чистую Windows нужно подтвердить отдельно. FEEL_APPROVED = NO; MVP_ACCEPTED = NO до фактической оценки владельца.

## Ресурсы и распространение

Источники и преобразования assets: [ASSET_REGISTER](ASSET_REGISTER.csv), [AUDIO_DESIGN](AUDIO_DESIGN.md), `src/main/resources/licenses/asset-provenance.json` и генераторы. Текстуры Poly Haven, музыка Metalmania Kevin MacLeod и Roboto Condensed имеют локальные исходники и лицензионные сведения. Проверка WAV/размера/hash не подтверждает художественное качество. Ресурсы оригинальной Twisted Metal 4 не являются входом генераторов.

[THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES.md) и `src/main/resources/licenses/license-index.json` указывают upstream документы и hashes; build записывает точные Maven coordinates и license evidence. Сохраняются лицензии зависимости и `runtime/legal`. Для OpenAL Soft до внешнего распространения требуется закрыть отмеченную проверку corresponding-source delivery и возможности замены библиотеки для точного native build; здесь не дано неподтверждённое обещание поставки исходников от имени владельца.

Статусы на момент передачи: художественная приёмка `NEEDS_CREATIVE_REVIEW`, распространение `REVIEW_REQUIRED`. Права на коммерческое название и материалы будущей страницы не проверены. Не заменять эти статусы словом «очищено» только потому, что технический asset gate прошёл.

## Следующие действия владельца

1. Принять конкретный ZIP по SHA/source: сверить актуальные автоматические проверки в ACCEPTANCE, пройти полный бой вручную, UI/focus/video, физический геймпад и fresh Windows без Java; внешний CI выполнить при доступной среде и разрешении.
2. Записать личную оценку пяти категорий ощущений и желание повторного боя по [ACCEPTANCE](ACCEPTANCE.md); закрыть лицензионные и коммерческие вопросы.
3. При отдельном решении о публикации оформить собственное приложение Steamworks и получить реальные AppID/DepotID. Не использовать чужой тестовый AppID.
4. Подготовить страницу, реальные screenshots/trailer, требования и заявления о возможностях строго для принятой сборки. Настроить Windows launch option на `WreckRiff.exe`, без dev-аргументов.
5. По отдельному поручению настроить SteamPipe и проверить сборку в закрытой ветке. Секреты хранить в штатном защищённом механизме, не в Git или depot. Актуальные процедуры — [Uploading to Steam](https://partner.steamgames.com/doc/sdk/uploading).
6. Пройти отдельную проверку страницы и build; Valve проверяет соответствие заявленных возможностей поставленной игре. Локальный успех не заменяет эту процедуру. [Steamworks Review Process](https://partner.steamgames.com/doc/store/review_process).

Публикация, отправка на review, покупки и регистрация не выполнялись в рамках локальной реализации.

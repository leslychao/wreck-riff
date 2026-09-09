# Приёмка версии 0.4

Проверяется [утверждённый план 0.4](V0_4_PLAN.md), 9 сентября 2026 года.
Отчёт 0.3 сохранён в [истории](history/ACCEPTANCE-0.3-interim.md).
Ни старые отчёты, ни наличие исходников не подтверждают текущую поставку.

## Текущая проверка

- Presentation: 33 теста PASS, включая бюджет машины, складки, иней, щит и пулы.
- Общий test / physicsTest / verifyAssets: IN_PROGRESS.
- Графический матч и 20 Retry: PENDING.
- Полный benchmark: PENDING, 30 с прогрева + 600 с, 1080p/audio on/VSync off.
- Кадры и 40-секундная демонстрация: IN_PROGRESS.
- Windows ZIP, SHA-256 и отчёт одной сборки: PENDING.

Showcase явно обозначает постановочную галерею HP и подготовку отдельных сцен.
Попадания, рикошеты, уничтожение и движение остова используют обычный MatchRuntime
и Minie. Видео записывает настоящий framebuffer. Дорожка реконструируется из
фактически запущенных семплов и параметров игры; это не точная запись OpenAL/HRTF.
Benchmark выполняется отдельно без видеозаписи.

## Ручная приёмка

- Наведение, сила ударов, визуал и звук: PENDING_OWNER.
- Физический геймпад и отключение устройства: PENDING_MANUAL.
- Полный бой человеком, меню и видеонастройки: PENDING_MANUAL.
- Отдельная чистая Windows без Java: PENDING_MANUAL. Локальная изоляция внешней
  Java проверяет комплектность поставки, но не заменяет другую установку ОС.
- Публикация, Steam и внешний CI в эту работу не входят.

FUNCTIONAL_COMPLETE = NO (final checks in progress)

FEEL_APPROVED = NO (owner review pending)

MVP_ACCEPTED = NO (owner and remaining manual gates pending)

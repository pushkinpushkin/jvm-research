# Текущее состояние — 2026-09-27

## Где мы сейчас

Этап: локальная подготовка короткого полного прохода для визуализации и затем подготовка VPS к HotSpot pilot. Добавлен конфиг `configs/local-idle-rare-quick.env` для одного forward-прохода по четырём runtime через `scripts/run-v1-idle-rare-matrix.sh --config`: fresh idle 90s, rare requests 2m при `1/10s`, post-load idle 60s, `K6_TIME_SERIES=true`, HTML-отчёт в `results/local-idle-rare-quick/report.html`. Это проверка артефактов и заготовка визуализации, не pilot и не baseline. Скрипт матрицы теперь поддерживает `--config`, настраиваемые длительности/порядок проходов и опциональную генерацию HTML-отчёта; дефолтная 1h-матрица осталась прежней.

VPS-подготовка остаётся актуальной: добавлены репозиторные скрипты для настройки Debian/Ubuntu VPS, preflight-проверки и фиксации host snapshot; сами проверки на исследовательском хосте ещё не выполнены. При первом low-load pilot на Selectel подтверждён штатный длинный участок без вывода: после k6 runner переходит в drain и затем в `post-load-idle`, где при `POST_IDLE_SECONDS=600` ещё 10 минут собирает метрики. Runner печатает короткие progress/phase строки и подавляет пугающий stderr от необязательного `docker image inspect` при фиксации base image digest. Длительная стабильность и сравнительные выводы ещё не доказаны.

Базовая реализация воспроизводимого стенда: `d83cff13a1c50eb651b1f3248a536ca44c3a2198`; исправление конечного tick k6: `e9388c55cc44d3afa7ec5b5ea2ba174019596640`. Подготовка long-run отчётов: `2a8b104`.

## Исправлено

Ограничены cache/history/receipts/outbox; Kafka redelivery после ошибки, сохранение событий до ACK, конкурентные записи одной реплики и обработка scheduler ошибок. Есть все WireMock delay/error modes. Детерминированный trace и отдельные normal/faults; admission fail-closed; раздельные фазы, cgroup/CPU/heap/GC/async данные и фазовые агрегаты. Для трёхчасовых прогонов `phase-summary.json` теперь содержит окна `3600-7200s`, `7200-10800s`, часовые `0-1h`/`1-2h`/`2-3h` и slope working set за последние 60/90 минут фазы. Runner сохраняет `image-digests.json` и `metadata.imageDigests` для runtime/base images, MongoDB, Kafka и WireMock. Kafka lag admission документирован как CLI `kafka-consumer-groups`, не Prometheus labels. Подробности модели и ограничений: `DECISIONS.md`, формулы: `RESULTS_SCHEMA.md`.

## Подтверждено

Локально для quick matrix config: `bash -n scripts/run-v1-idle-rare-matrix.sh` — success; `DRY_RUN=true bash scripts/run-v1-idle-rare-matrix.sh --config configs/local-idle-rare-quick.env` — success, планирует 8 запусков и HTML-отчёт; `python3 -m unittest scripts.tests.test_experiments scripts.tests.test_research_validity` — 17/17. Эти проверки не запускали Docker/JVM-матрицу и не создавали исследовательские данные. Ранее для runner observability fix: `bash -n scripts/run-experiment.sh` — success; `python3 -m unittest discover scripts/tests` — 20/20. Ранее для VPS-скриптов поверх `cf6815b`: `for f in scripts/vps-install-prereqs.sh scripts/vps-preflight.sh scripts/vps-capture-host.sh; do bash -n "$f"; done` — success; `python3 -m unittest discover scripts/tests` — 20/20. Ранее после подготовки long-run отчёта: `./gradlew test` — success; 14 Java tests и bootJar, 10 живых HTTP-проверок WireMock; после endpoint fix — Python tests со строгой проверкой boundary skip, недостающей и лишней работы. Исполнение JS guard проверено на обычном index, одном endpoint no-op и недопустимом следующем index.

CI [36193573959](https://github.com/pushkinpushkin/jvm-research/actions/runs/36193573959) для `e9388c55cc44d3afa7ec5b5ea2ba174019596640`: **success**, все 6 jobs. Java/Python tests, bootJar и 10 WireMock mappings успешны. Все четыре runtime, включая сборку Native executable, прошли idle, normal load и faults + async drain: **12 smoke-прогонов, у каждого eligible=true**. Это короткие функциональные проверки, не baseline.

CI выявил и подтвердил исправление endpoint tick k6: один no-op при index=trace.length учитывается отдельно, HTTP-count остаётся точным. Реальные k6 прогоны проверили оба случая skips=0/1; недостающая и лишняя работа отклоняются regression tests. После указанного проверенного commit изменялись только документы (`[skip ci]`); повторная сборка неизменного кода не требуется.

На реальном HotSpot smoke независимо сверены 12 cgroup samples, time-weighted memory, CPU delta и heap pool sum; совпали с отчётом. Входные числа, формулы и run ID: [docs/verification-v1.md](docs/verification-v1.md).

## Что мешает baseline

Нужно выполнить `scripts/vps-preflight.sh` и `scripts/vps-capture-host.sh` на выбранном исследовательском хосте, зафиксировать образы и фактический GC, затем провести pilot 10–30 минут, контроль observer overhead (sampling 5/15 секунд), stability 1–3 часа и выбрать длительность. GitHub smoke на ephemeral runners не заменяет эту проверку. `image-digests.json` фиксирует доступные image IDs / repo digests, но не заменяет закрепление хоста и локального Docker окружения. Ограничения: одна реплика, конечное dedup-окно; docker exec и HTTP telemetry входят в измеряемую нагрузку. Working set не является RSS. Нельзя сравнивать runtime только по низкой памяти при нарушении SLO.

## Следующий шаг

Ближайшая отдельная цель: локально выполнить `bash scripts/run-v1-idle-rare-matrix.sh --config configs/local-idle-rare-quick.env`, проверить наличие eligible/rejected причин по каждому из 8 run и открыть `results/local-idle-rare-quick/report.html`, чтобы проработать форму визуализации. После этого на выбранном VPS запустить `scripts/vps-install-prereqs.sh` при необходимости, затем `scripts/vps-preflight.sh` и `scripts/vps-capture-host.sh`; если preflight проходит без FAIL, выполнить HotSpot pilot 10–30 минут с бюджетом приложения 2 CPU/1 GiB, проверить instrumentation, полноту данных и variance. Затем stability 1–3 часа с новыми окнами и slope. Полная матрица откладывается до корректного HotSpot baseline. Для продолжения читать этот файл и `RESEARCH_PROTOCOL.md`; историю переписки и полные логи повторно не загружать.

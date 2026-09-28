# Текущее состояние — 2026-09-28

## Где мы сейчас

Этап: hardening admission validator перед эталонными прогонами. В рабочем дереве (отдельный commit ещё не создан) `scripts/validate-run.py` разделяет business-final и post-idle-final state: HTTP/async counters, event balance, завершение фоновой работы, final order population и `observedWork` берутся из `state-drain.json`, если он есть; для старых артефактов сохранён fallback на `state-after.json`. `state-after.json` остаётся post-idle memory/state observation и проверяется на bounds, но cleanup после drain больше не ломает business validation. Методика метрик не менялась. В серии `v1-idle-rare-1h` failed rare-requests run остаётся partial diagnostic и не смешивается с новой серией после фикса.

VPS-подготовка остаётся актуальной: добавлены репозиторные скрипты для настройки Debian/Ubuntu VPS, preflight-проверки и фиксации host snapshot; сами проверки на исследовательском хосте ещё не выполнены. При первом low-load pilot на Selectel подтверждён штатный длинный участок без вывода: после k6 runner переходит в drain и затем в `post-load-idle`, где при `POST_IDLE_SECONDS=600` ещё 10 минут собирает метрики. Runner печатает короткие progress/phase строки и подавляет пугающий stderr от необязательного `docker image inspect` при фиксации base image digest. Длительная стабильность и сравнительные выводы ещё не доказаны.

Базовая реализация воспроизводимого стенда: `d83cff13a1c50eb651b1f3248a536ca44c3a2198`; исправление конечного tick k6: `e9388c55cc44d3afa7ec5b5ea2ba174019596640`. Подготовка long-run отчётов: `2a8b104`.

## Исправлено

Ограничены cache/history/receipts/outbox; Kafka redelivery после ошибки, сохранение событий до ACK, конкурентные записи одной реплики и обработка scheduler ошибок. Есть все WireMock delay/error modes. Детерминированный trace и отдельные normal/faults; admission fail-closed; раздельные фазы, cgroup/CPU/heap/GC/async данные и фазовые агрегаты. Для трёхчасовых прогонов `phase-summary.json` теперь содержит окна `3600-7200s`, `7200-10800s`, часовые `0-1h`/`1-2h`/`2-3h` и slope working set за последние 60/90 минут фазы. Runner сохраняет `image-digests.json` и `metadata.imageDigests` для runtime/base images, MongoDB, Kafka и WireMock. Kafka lag admission документирован как CLI `kafka-consumer-groups`, не Prometheus labels. Подробности модели и ограничений: `DECISIONS.md`, формулы: `RESULTS_SCHEMA.md`.

## Подтверждено

Локальная regression-проверка validator в рабочем дереве: `python3 -m unittest scripts.tests.test_research_validity scripts.tests.test_experiments` — 20/20. Адресная фикстура `before.orders=1000`, `drain.orders=1000`, `drain.http_processed=360`, `drain.http_unexpected=0`, `after.orders=0` даёт `eligible=true`; после удаления `state-drain.json` тот же run отклоняется через fallback на post-idle state. Это локальная проверка, не run ID эксперимента и не baseline.

Подготовлен `scripts/run-local-10m-matrix.sh` для локального эксперимента: по умолчанию
4 последовательных прогона `low-load` по 10 минут (HotSpot, OpenJ9, GraalVM JIT,
GraalVM Native), `ORDER_POOL=1000`, `SEED_ORDERS=1000`, `TRAFFIC_PROFILE=normal`,
отдельные `RUN_ID` и результаты в `results/local-10m/<series-id>/`. Поддержаны
`SCENARIO`, `RATE`, `MATRIX_PASSES`, `POST_IDLE_SECONDS` и `DRY_RUN`; shell syntax и
dry-run проверены. Серия не является baseline.

Локально для quick matrix config: `bash -n scripts/run-v1-idle-rare-matrix.sh` — success; `DRY_RUN=true bash scripts/run-v1-idle-rare-matrix.sh --config configs/local-idle-rare-quick.env` — success, планирует 8 запусков и HTML-отчёт; `python3 scripts/validate-run.py results/local-idle-rare-quick/20260926T231137Z-local-idle-rare-quick/pass1-forward/rare-requests/20260926T231137Z-local-idle-rare-quick-pass1-forward-1-rare-requests-work-hotspot-elastic` — `eligible=true` после исправления Kafka lag parser; `python3 -m unittest scripts.tests.test_research_validity scripts.tests.test_experiments` — 18/18. Эти проверки не завершили всю Docker/JVM-матрицу и не создают baseline-данные. Ранее для runner observability fix: `bash -n scripts/run-experiment.sh` — success; `python3 -m unittest discover scripts/tests` — 20/20. Ранее для VPS-скриптов поверх `cf6815b`: `for f in scripts/vps-install-prereqs.sh scripts/vps-preflight.sh scripts/vps-capture-host.sh; do bash -n "$f"; done` — success; `python3 -m unittest discover scripts/tests` — 20/20. Ранее после подготовки long-run отчёта: `./gradlew test` — success; 14 Java tests и bootJar, 10 живых HTTP-проверок WireMock; после endpoint fix — Python tests со строгой проверкой boundary skip, недостающей и лишней работы. Исполнение JS guard проверено на обычном index, одном endpoint no-op и недопустимом следующем index.

CI [36193573959](https://github.com/pushkinpushkin/jvm-research/actions/runs/36193573959) для `e9388c55cc44d3afa7ec5b5ea2ba174019596640`: **success**, все 6 jobs. Java/Python tests, bootJar и 10 WireMock mappings успешны. Все четыре runtime, включая сборку Native executable, прошли idle, normal load и faults + async drain: **12 smoke-прогонов, у каждого eligible=true**. Это короткие функциональные проверки, не baseline.

CI выявил и подтвердил исправление endpoint tick k6: один no-op при index=trace.length учитывается отдельно, HTTP-count остаётся точным. Реальные k6 прогоны проверили оба случая skips=0/1; недостающая и лишняя работа отклоняются regression tests. После указанного проверенного commit изменялись только документы (`[skip ci]`); повторная сборка неизменного кода не требуется.

На реальном HotSpot smoke независимо сверены 12 cgroup samples, time-weighted memory, CPU delta и heap pool sum; совпали с отчётом. Входные числа, формулы и run ID: [docs/verification-v1.md](docs/verification-v1.md).

## Что мешает baseline

Нужно выполнить `scripts/vps-preflight.sh` и `scripts/vps-capture-host.sh` на выбранном исследовательском хосте, зафиксировать образы и фактический GC, затем провести pilot 10–30 минут, контроль observer overhead (sampling 5/15 секунд), stability 1–3 часа и выбрать длительность. GitHub smoke на ephemeral runners не заменяет эту проверку. `image-digests.json` фиксирует доступные image IDs / repo digests, но не заменяет закрепление хоста и локального Docker окружения. Ограничения: одна реплика, конечное dedup-окно; docker exec и HTTP telemetry входят в измеряемую нагрузку. Working set не является RSS. Нельзя сравнивать runtime только по низкой памяти при нарушении SLO.

## Следующий шаг

Ближайшая цель: зафиксировать validator fix в commit, доставить его на VPS и повторно валидировать rare-requests/low-load артефакты с `state-drain.json`. Затем на VPS сохранить partial tarball, переименовать failed run и запустить новую чистую серию `v1-idle-rare-1h` с новым `RESULTS_ROOT`/`RUN_ID` либо надёжный retry только failed runtime/scenario. Проверить `validation.json`, `metadata.json` и `phase-summary.json`; old fresh-idle eligible run можно использовать как отдельное наблюдение, но не смешивать с данными после фикса для baseline. После успешного перезапуска продолжить preflight/host snapshot, pilot и stability; полная матрица остаётся отложенной до корректного HotSpot baseline.

# Текущее состояние — 2026-09-25

## Где мы сейчас

Этап: реализация и короткие интеграционные проверки пунктов 1–5 завершены; далее HotSpot pilot. PR [#8](https://github.com/pushkinpushkin/jvm-research/pull/8), ветка `feat/reproducible-research`; PR остаётся draft, merge не выполнялся. Длительная стабильность и сравнительные выводы ещё не доказаны.

Реализация: `d83cff13a1c50eb651b1f3248a536ca44c3a2198`; исправление конечного tick k6: `e9388c55cc44d3afa7ec5b5ea2ba174019596640`.

## Исправлено

Ограничены cache/history/receipts/outbox; Kafka redelivery после ошибки, сохранение событий до ACK, конкурентные записи одной реплики и обработка scheduler ошибок. Есть все WireMock delay/error modes. Детерминированный trace и отдельные normal/faults; admission fail-closed; раздельные фазы, cgroup/CPU/heap/GC/async данные и фазовые агрегаты. Подробности модели и ограничений: `DECISIONS.md`, формулы: `RESULTS_SCHEMA.md`.

## Подтверждено

Локально: 14 Java tests и bootJar, 10 живых HTTP-проверок WireMock; после endpoint fix — 19 Python tests, включая строгую проверку boundary skip, недостающей и лишней работы. Исполнение JS guard проверено на обычном index, одном endpoint no-op и недопустимом следующем index.

CI [36193573959](https://github.com/pushkinpushkin/jvm-research/actions/runs/36193573959) для `e9388c55cc44d3afa7ec5b5ea2ba174019596640`: **success**, все 6 jobs. Java/Python tests, bootJar и 10 WireMock mappings успешны. Все четыре runtime, включая сборку Native executable, прошли idle, normal load и faults + async drain: **12 smoke-прогонов, у каждого eligible=true**. Это короткие функциональные проверки, не baseline.

CI выявил и подтвердил исправление endpoint tick k6: один no-op при index=trace.length учитывается отдельно, HTTP-count остаётся точным. Реальные k6 прогоны проверили оба случая skips=0/1; недостающая и лишняя работа отклоняются regression tests. После указанного проверенного commit изменялись только документы (`[skip ci]`); повторная сборка неизменного кода не требуется.

На реальном HotSpot smoke независимо сверены 12 cgroup samples, time-weighted memory, CPU delta и heap pool sum; совпали с отчётом. Входные числа, формулы и run ID: [docs/verification-v1.md](docs/verification-v1.md).

## Что мешает baseline

Нужны исследовательский хост и фиксированные образы, pilot 10–30 минут, контроль observer overhead (sampling 5/15 секунд), stability 1–3 часа и выбор длительности. GitHub smoke на ephemeral runners не заменяет эту проверку. Ограничения: одна реплика, конечное dedup-окно; docker exec и HTTP telemetry входят в измеряемую нагрузку. Working set не является RSS. Нельзя сравнивать runtime только по низкой памяти при нарушении SLO.

## Следующий шаг

Одна отдельная цель: HotSpot pilot 10–30 минут на выбранном фиксированном хосте с бюджетом 2 CPU/1 GiB; проверить instrumentation, полноту данных и variance. Полная матрица откладывается до корректного HotSpot baseline. Для продолжения читать этот файл и `RESEARCH_PROTOCOL.md`; историю переписки и полные логи повторно не загружать.

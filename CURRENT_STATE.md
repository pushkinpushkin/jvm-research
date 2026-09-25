# Текущее состояние — 2026-09-25

## Где мы сейчас

Этап: закрытие корректности стенда (пункты 1–5), затем HotSpot pilot. PR [#8](https://github.com/pushkinpushkin/jvm-research/pull/8), ветка `feat/reproducible-research`; PR остаётся draft, merge не выполнялся. Длительная стабильность и сравнительные выводы ещё не доказаны.

Реализация: `d83cff13a1c50eb651b1f3248a536ca44c3a2198`; исправление конечного tick k6: `e9388c55cc44d3afa7ec5b5ea2ba174019596640`.

## Исправлено

Ограничены cache/history/receipts/outbox; Kafka redelivery после ошибки, сохранение событий до ACK, конкурентные записи одной реплики и обработка scheduler ошибок. Есть все WireMock delay/error modes. Детерминированный trace и отдельные normal/faults; admission fail-closed; раздельные фазы, cgroup/CPU/heap/GC/async данные и фазовые агрегаты. Подробности модели и ограничений: `DECISIONS.md`, формулы: `RESULTS_SCHEMA.md`.

## Подтверждено

Локально: 14 Java tests и bootJar, 10 живых HTTP-проверок WireMock; после endpoint fix — 19 Python tests, включая строгую проверку boundary skip, недостающей и лишней работы. Исполнение JS guard проверено на обычном index, одном endpoint no-op и недопустимом следующем index.

Первый CI [36148875277](https://github.com/pushkinpushkin/jvm-research/actions/runs/36148875277): Java/Python и WireMock успешны; все четыре runtime собраны и запущены, включая Native. Idle успешен; normal load HotSpot/OpenJ9 прошёл. В остальных load/fault шагах k6 иногда создавал дополнительную итерацию ровно на конечной границе: фактические бизнес-запросы корректны, но trace exhaustion отклонял run. Это дефект harness, не доказательство проблемы runtime.

Повторный CI [36193573959](https://github.com/pushkinpushkin/jvm-research/actions/runs/36193573959) для `e9388c5`: Java/Python и WireMock успешны. HotSpot, OpenJ9 и GraalVM JIT прошли все три smoke-сценария с eligible=true; Native ещё выполняется. Endpoint no-op проверен в реальном k6 как при skips=0, так и при skips=1. До подтверждения Native нельзя объявлять весь gate закрытым.

На реальном HotSpot smoke независимо сверены 12 cgroup samples, time-weighted memory, CPU delta и heap pool sum; совпали с отчётом. Входные числа, формулы и run ID: [docs/verification-v1.md](docs/verification-v1.md).

## Что мешает baseline

Нужны исследовательский хост и фиксированные образы, pilot 10–30 минут, контроль observer overhead (sampling 5/15 секунд), stability 1–3 часа и выбор длительности. GitHub smoke на ephemeral runners не заменяет эту проверку. Ограничения: одна реплика, конечное dedup-окно; docker exec и HTTP telemetry входят в измеряемую нагрузку. Working set не является RSS. Нельзя сравнивать runtime только по низкой памяти при нарушении SLO.

## Следующий шаг

Закрыть повторный smoke и сверить реальные raw-агрегаты. Затем одна отдельная цель: HotSpot pilot на выбранном хосте с бюджетом 2 CPU/1 GiB. Полная матрица откладывается до корректного HotSpot baseline. Для продолжения читать этот файл и `RESEARCH_PROTOCOL.md`; историю переписки и полные логи повторно не загружать.

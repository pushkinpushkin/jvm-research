# Local runbook

## Требования и сборка

Java 21, Bash, Python 3.9+, curl, Docker Engine/Desktop + Compose v2.
Для `load` и `low-load` обязателен локальный k6. Без него запуск завершается ошибкой.
`idle` k6 не использует. Порты 8080, 8089, 9092, 27017 должны быть свободны.

```bash
./gradlew test bootJar
# Локальная AOT-сборка: JAVA_HOME указывает на GraalVM JDK 21 с native-image;
# также нужны C toolchain и системные библиотеки по инструкции GraalVM.
./gradlew nativeCompile
# Результат: build/native/nativeCompile/jvm-research

# Альтернатива: весь native toolchain внутри отдельного builder-контейнера.
docker build -f Dockerfile.native -t jvm-research-sandbox:graalvm-native .
docker build -t jvm-research-sandbox:hotspot-liberica .
```

Native build требует существенно больше ресурсов, чем runtime с лимитом 1 GiB.
Лимиты `2 CPU / 1g` относятся к измеряемому контейнеру, а не сборщику.
Не запускайте сборку одновременно с измерением другого runtime.

## Smoke всех четырёх runtime

```bash
for profile in work-hotspot-fixed work-openj9-fixed work-graalvm-fixed work-graalvm-native; do
  SCENARIO=load RATE=1 DURATION=1m ORDER_POOL=100 SEED_ORDERS=100 \
    RESULTS_ROOT=results/smoke \
    bash scripts/run-experiment.sh "profiles/${profile}.env" || break
done
```

Каждый runner собирает образ, поднимает MongoDB, Kafka и WireMock, ждёт
`/actuator/health/readiness` со статусом UP (readinessState + Mongo), читает `/run-info`,
выполняет workload, собирает диагностику и удаляет только свой временный Compose project.
Readiness не заменяет проверку Kafka/внешнего API: для этого нужен enterprise load и просмотр логов.
Для проверки бизнес-цепочки ищите в `app.log` сообщения `External FNS request finished`,
`Kafka event published` и `Business event occurred` / `Order status changed`.
Ошибки Native reflection/deserialization требуют исправления до длинных замеров.

## Основная матрица: 2 CPU, 1 GiB

Нагрузочный fixed-heap baseline (три JVM) и отдельный native вариант:

```bash
SCENARIO=load RATE=10 DURATION=30m bash scripts/run-experiment.sh profiles/work-hotspot-fixed.env
SCENARIO=load RATE=10 DURATION=30m bash scripts/run-experiment.sh profiles/work-openj9-fixed.env
SCENARIO=load RATE=10 DURATION=30m bash scripts/run-experiment.sh profiles/work-graalvm-fixed.env
SCENARIO=load RATE=10 DURATION=30m bash scripts/run-experiment.sh profiles/work-graalvm-native.env
```

Memory-oriented матрица: поменяйте `fixed` на `elastic` для JVM.
Native остаётся в явно обозначенном режиме `native-default`, без JVM options.
Для выбранного профиля:

```bash
SCENARIO=idle DURATION=30m RESULTS_ROOT=results/idle/30m \
  bash scripts/run-experiment.sh profiles/work-hotspot-elastic.env
SCENARIO=low-load RATE=1 DURATION=30m RESULTS_ROOT=results/low-load/r1-30m \
  bash scripts/run-experiment.sh profiles/work-hotspot-elastic.env
SCENARIO=load RATE=10 DURATION=30m RESULTS_ROOT=results/benchmark/r10-30m \
  bash scripts/run-experiment.sh profiles/work-hotspot-elastic.env
SCENARIO=load RATE=25 DURATION=30m RESULTS_ROOT=results/benchmark/r25-30m \
  bash scripts/run-experiment.sh profiles/work-hotspot-elastic.env
```

Те же команды применяются к `work-openj9-elastic`, `work-graalvm-elastic` и
`work-graalvm-native`. Повторить минимум три круга, меняя порядок runtime.
Старые `work-*-baseline.env` сохранены без изменений: **1 CPU**, Xms=Xmx=512m.
Для исторического workload явно задавайте старые RATE/DURATION/ORDER_POOL и
`SYNTHETIC_WARMUP=true`: новый runner по умолчанию не вызывает synthetic endpoint.
Старые результаты не объединяются с новыми 2 CPU прогонами.

## Параметры и результаты

Экспортированные переменные командной строки имеют приоритет над профилем.
По умолчанию: SCENARIO=load, RATE=10 (low-load: 1, idle: 0), DURATION=30m,
INTERVAL_SECONDS=5, ORDER_POOL=10000, SEED_ORDERS=ORDER_POOL.
`DURATION=1m` / `3m` остаются доступны. Формат `1m30s` тоже поддерживается.
`SEED_ORDERS < ORDER_POOL` запрещён. Подготовка/seed k6 идут сверх DURATION.

- `RUN_ID` автоматически уникален; существующую директорию перезаписать нельзя.
- `RESULTS_ROOT` принимает относительный или абсолютный путь.
- `INTERVAL_SECONDS=5`: период между началами измерений; Docker stats сам занимает время.
- `COLLECT_PROMETHEUS=false`: отключить периодические scrapes во всей сравниваемой серии.
- `K6_TIME_SERIES=true`: сохранить исходную временную серию k6 для анализа latency/warmup;
  файл может быть большим. Используйте одинаковый режим для всех runtime.
- `SYNTHETIC_WARMUP=true`: только явно обозначенный отдельный эксперимент; запрещён в idle.
- `HEALTH_TIMEOUT_SECONDS=180`: время ожидания readiness.
- `APP_PORT`: внешний порт приложения. Остальные порты инфраструктуры фиксированы;
  запускайте эксперименты последовательно.

`results/<scenario>/<duration>/<run>/` (или RESULTS_ROOT/RUN_ID) содержит:

```text
metadata.json                 # настройки, mode, profile, startup, status, exitCode, git
profile.env, source.diff       # исходный профиль и tracked diff, если checkout был dirty
compose-config.yml            # итоговая конфигурация, включая реальные env
container-inspect.json        # image ID, StartedAt, фактические runtime limits
build.log, docker-version.txt, docker-info.txt
health.json, run-info.json
runtime-metrics.csv, collector.log
prometheus/*.prom, prometheus-before.txt, prometheus-after.txt
k6-summary.json, k6.log        # отсутствуют в idle
k6-timeseries.json            # только K6_TIME_SERIES=true
app.log, compose.log, app-logs/gc.log
cleanup.log, docker-compose-ps.txt, docker-stats.txt
```

При ошибке run получает `status=failed` и ненулевой exitCode. k6 threshold failure не
превращается в успешный эксперимент. Collector и workload останавливаются при EXIT/INT/TERM;
SIGKILL и авария хоста не позволяют выполнить cleanup. Данные старых Compose projects
и исторические результаты не удаляются. Новые экспериментальные Mongo volumes одноразовые.

## Сравнение

```bash
bash scripts/compare-benchmark-root.sh results/benchmark/r10-30m
bash scripts/compare-benchmark-root.sh results/idle/30m
bash scripts/compare-benchmark-root.sh results --csv > comparison.csv
bash scripts/compare-benchmark-root.sh results/verification --html results/verification/report.html
open results/verification/report.html # macOS
python3 -m unittest discover -s scripts/tests -v
```

Таблица содержит **одну строку на run**, включая все k6-перцентили и memory summary.
Пропуски отображаются как `n/a`, в CSV — пустая ячейка. Старый k6 summary поддерживается.
Скрипт не усредняет разные профили и не усредняет перцентили разных запусков.
Проверяйте status, scenario, memory_profile, CPU, rate, duration и config перед выводами.

HTML-отчёт работает локально без сервера и строится из сохранённых CSV и metadata:
линии памяти и CPU, сравнение пиков памяти, startup и HTTP p95. Выбор сценария
разделяет несовместимые условия; отдельные запуски можно скрывать в легенде.
После одного smoke видны его idle и load; сравнение runtime появится после
прогонов остальных профилей. Поминутный HTTP p95 требует `K6_TIME_SERIES=true`
при запуске эксперимента; из одного summary временной ряд восстановить нельзя.
Память — Docker working set estimate, а не heap или RSS.

## Ручной sandbox и ограничение измерений

```bash
bash scripts/run-enterprise-sandbox.sh graalvm-native
# В другом терминале:
curl http://localhost:8080/actuator/health/readiness
curl http://localhost:8080/run-info
curl -X POST 'http://localhost:8080/orders/generate?count=100'
curl -X POST 'http://localhost:8080/orders/order-1/process'
curl http://localhost:8080/orders/order-1
```

Ручной sandbox — отдельный, сохраняющий данные workflow. Перед автоматическими
экспериментами остановите его, чтобы освободить порты.
Docker Desktop измеряет Linux VM, не RSS приложения в macOS. Для переноса выводов в
Kubernetes повторите замеры на репрезентативном Linux node.

## Протокол v1 и проверка пригодности

Актуальные условия: [RESEARCH_PROTOCOL.md](../RESEARCH_PROTOCOL.md). Основная серия использует elastic heap.

```bash
# Сначала короткая интеграционная проверка (новый изолированный compose project на каждый запуск).
bash scripts/smoke-runtime.sh profiles/work-hotspot-elastic.env

# Главный сценарий: 30 минут low-load + drain + 30 минут простоя.
SCENARIO=low-load RATE=1 RATE_TIME_UNIT=1s DURATION=30m \
ORDER_POOL=1000 SEED_ORDERS=1000 TRAFFIC_PROFILE=normal TRACE_SEED=20260925 \
POST_IDLE_SECONDS=1800 RESULTS_ROOT=results/reference/low-load \
bash scripts/run-experiment.sh profiles/work-hotspot-elastic.env

# Отдельная проверка устойчивости, не смешивать с normal.
SCENARIO=load RATE=10 DURATION=3m ORDER_POOL=1000 SEED_ORDERS=1000 \
TRAFFIC_PROFILE=faults POST_IDLE_SECONDS=30 RESULTS_ROOT=results/verification/faults \
bash scripts/run-experiment.sh profiles/work-hotspot-elastic.env

# Повторная проверка имеющихся артефактов.
python3 scripts/validate-run.py results/path/to/run
python3 scripts/phase-report.py results/path/to/run
```

Остальные профили: `work-openj9-elastic`, `work-graalvm-elastic`, `work-graalvm-native`.
Для редких запросов: `RATE=1 RATE_TIME_UNIT=10s`. Fresh idle: `SCENARIO=idle DURATION=30m`.
Запуск с неизвестными/недостающими метриками отклоняется; код 2 означает отказ валидатора при успешном процессе. `validation.json` содержит причины, `phase-summary.json` — раздельные расчёты. Старые результаты без валидации не отображаются как допущенные серии.

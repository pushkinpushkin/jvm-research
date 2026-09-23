# JVM Research Sandbox

Песочница для сравнения HotSpot Liberica, OpenJ9, GraalVM JIT и GraalVM Native Image/AOT на одном Spring Boot 3.4.4 приложении. Основной новый этап — память idle/low-load/load при 2 CPU, 1 GiB и длительности 30 минут.

Цель репозитория — быстро получать воспроизводимые замеры, а потом переносить подход на реальные микросервисы.

## Выбранная JVM-матрица

| Variant | JVM type | Image | Role |
|---|---|---|---|
| `hotspot-liberica` | HotSpot / BellSoft Liberica | `bellsoft/liberica-openjre-alpine:21.0.11-11` | HotSpot Liberica baseline |
| `openj9` | Eclipse OpenJ9 / IBM Semeru | `ibm-semeru-runtimes:open-21.0.11.0-jdk-jammy` | эталонный OpenJ9 |
| `graalvm-jit` | Oracle GraalVM JDK | `container-registry.oracle.com/graalvm/jdk:21` | GraalVM в JVM/JIT режиме |

| `graalvm-native` | GraalVM Native Image / AOT | `oraclelinux:9-slim` | Native executable, без JVM/JIT |

Подробности: [`docs/jvm-matrix.md`](docs/jvm-matrix.md). Native Image не является четвёртой JVM; сравниваем общие container/startup/HTTP метрики.

## Что внутри

```text
.
├── build.gradle.kts
├── settings.gradle.kts
├── Dockerfile
├── docker/
│   ├── hotspot/Dockerfile
│   ├── openj9/Dockerfile
│   └── graalvm/Dockerfile
├── infra/
│   ├── docker-compose.yml
│   └── wiremock/mappings/
├── load/k6/enterprise-flow.js
├── profiles/
│   ├── work-hotspot-baseline.env
│   ├── work-openj9-baseline.env
│   └── work-graalvm-baseline.env
├── scripts/
│   ├── run-benchmarks.sh
│   ├── run-app.sh
│   ├── run-experiment.sh
│   ├── run-enterprise-sandbox.sh
│   └── run-load.sh
├── src/main/java/dev/pushkin/jvmresearch/enterprise/
├── src/jmh/java/dev/pushkin/jvmresearch/AllocationBenchmark.java
├── src/jmh/java/dev/pushkin/jvmresearch/StringProcessingBenchmark.java
└── docs/
    ├── enterprise-sandbox.md
    ├── experiment-plan.md
    ├── jvm-matrix.md
    ├── local-runbook.md
    └── metrics.md
```

## Единый entrypoint

Основное приложение одно:

```text
dev.pushkin.jvmresearch.enterprise.EnterpriseSandboxApplication
```

Внутри него есть два типа сценариев:

```text
1. /synthetic/runtime — быстрый CPU/allocation smoke внутри Spring Boot.
2. /orders/{orderId}/process — enterprise flow с MongoDB, WireMock, Kafka и scheduler'ами.
```

Если Gradle Wrapper ещё не сгенерирован локально:

```bash
gradle wrapper --gradle-version 8.14.3
chmod +x gradlew scripts/*.sh
```

Проверка проекта:

```bash
./gradlew test
./gradlew jmh
```

## Новый этап: memory footprint

Старые `profiles/work-*-baseline.env` сохранены с **1 CPU / Xms=Xmx512m** для
исторической воспроизводимости. Новые профили используют **2 CPU / 1 GiB**:

| Runtime | Fixed heap | Elastic heap |
|---|---|---|
| HotSpot | `work-hotspot-fixed.env` | `work-hotspot-elastic.env` |
| OpenJ9 | `work-openj9-fixed.env` | `work-openj9-elastic.env` |
| GraalVM JIT | `work-graalvm-fixed.env` | `work-graalvm-elastic.env` |
| GraalVM Native | `work-graalvm-native.env` — отдельный `native-default` | тот же native профиль |

Fixed: Xms512m/Xmx512m. Elastic: Xms32m/Xmx512m. У native контролируется внешний
лимит контейнера; одинаковый heap ceiling с JVM не заявляется.
Экспортированные env переопределяют значения профиля. Не смешивайте эти серии.

```bash
# Native build: GraalVM JDK 21 с native-image, либо полностью Docker build.
./gradlew nativeCompile
docker build -f Dockerfile.native -t jvm-research-sandbox:graalvm-native .

# Короткий enterprise smoke для каждого runtime.
for profile in work-hotspot-fixed work-openj9-fixed work-graalvm-fixed work-graalvm-native; do
  SCENARIO=load RATE=1 DURATION=1m ORDER_POOL=100 SEED_ORDERS=100 \
    RESULTS_ROOT=results/smoke bash scripts/run-experiment.sh "profiles/${profile}.env" || break
done

# Память без бизнес-нагрузки; никаких seed или synthetic вызовов.
SCENARIO=idle DURATION=30m bash scripts/run-experiment.sh profiles/work-hotspot-elastic.env
SCENARIO=low-load RATE=1 DURATION=30m bash scripts/run-experiment.sh profiles/work-hotspot-elastic.env
SCENARIO=load RATE=10 DURATION=30m bash scripts/run-experiment.sh profiles/work-hotspot-fixed.env
SCENARIO=load RATE=25 DURATION=30m bash scripts/run-experiment.sh profiles/work-hotspot-fixed.env

bash scripts/compare-benchmark-root.sh results
```

Подставьте соответствующий профиль OpenJ9/GraalVM JIT/native для той же нагрузки.
`DURATION=1m`/`3m` остаются доступны. k6 обязателен для low-load/load, idle его не требует.
Периодический collector одинаков для всех runtime; `INTERVAL_SECONDS=5` по умолчанию.

Каждый run сохраняет metadata, startup, `runtime-metrics.csv`, Prometheus scrapes,
конфигурацию Compose, image ID, логи и k6 summary (кроме idle). Память контейнера —
Docker working-set estimate, не RSS и не heap. Подробности и все команды:
[local-runbook](docs/local-runbook.md), [metrics](docs/metrics.md),
[план исследования](docs/experiment-plan.md), [статус проверок](docs/validation.md).

## Enterprise sandbox layer

Spring Boot-приложение имитирует типовой микросервисный flow:

```text
HTTP -> MongoDB -> WireMock external API -> mapping -> MongoDB -> Kafka -> schedulers
```

Подробнее: [docs/enterprise-sandbox.md](docs/enterprise-sandbox.md).

Быстрый запуск:

```bash
bash scripts/run-enterprise-sandbox.sh hotspot-liberica
bash scripts/run-enterprise-sandbox.sh openj9
bash scripts/run-enterprise-sandbox.sh graalvm-jit
```

Сервис поднимается вместе с MongoDB, Kafka и WireMock через `infra/docker-compose.yml`.

Проверка API:

```bash
curl -X POST 'http://localhost:8080/synthetic/runtime?iterations=20&payloadSize=100000'
curl -X POST 'http://localhost:8080/orders/generate?count=10000'
curl -X POST 'http://localhost:8080/orders/order-1/process'
curl 'http://localhost:8080/orders/order-1'
curl 'http://localhost:8080/actuator/prometheus'
```

Запуск нагрузки через k6:

```bash
RATE=20 DURATION=5m ORDER_POOL=10000 bash scripts/run-load.sh
```

## Docker-сборка JVM-вариантов

Dockerfile-ы запускают уже собранный runtime distribution и JMH jar.

Сначала собрать артефакты:

```bash
./gradlew clean test installDist jmhJar
```

Потом собрать runtime-образы для JMH/runtime слоя:

```bash
docker build -f docker/hotspot/Dockerfile -t jvm-research:hotspot-liberica .
docker build -f docker/openj9/Dockerfile -t jvm-research:openj9 .
docker build -f docker/graalvm/Dockerfile -t jvm-research:graalvm-jit .
```

Запуск Spring Boot-приложения:

```bash
docker run --rm jvm-research:hotspot-liberica
docker run --rm jvm-research:openj9
docker run --rm jvm-research:graalvm-jit
```

Запуск JMH внутри runtime-контейнера:

```bash
docker run --rm -v "$PWD/results:/results" jvm-research:hotspot-liberica java -jar /opt/jvm-research/jmh-benchmarks.jar -rf json -rff /results/hotspot-liberica-jmh.json
docker run --rm -v "$PWD/results:/results" jvm-research:openj9 java -jar /opt/jvm-research/jmh-benchmarks.jar -rf json -rff /results/openj9-jmh.json
docker run --rm -v "$PWD/results:/results" jvm-research:graalvm-jit java -jar /opt/jvm-research/jmh-benchmarks.jar -rf json -rff /results/graalvm-jit-jmh.json
```

Для enterprise sandbox используется корневой `Dockerfile` с runtime image как build arg:

```bash
docker build --build-arg RUNTIME_IMAGE=bellsoft/liberica-openjre-alpine:21.0.11-11 -t jvm-research-sandbox:hotspot-liberica .
docker build --build-arg RUNTIME_IMAGE=ibm-semeru-runtimes:open-21.0.11.0-jdk-jammy -t jvm-research-sandbox:openj9 .
docker build --build-arg RUNTIME_IMAGE=container-registry.oracle.com/graalvm/jdk:21 -t jvm-research-sandbox:graalvm-jit .
```

## Базовая идея исследования

1. Зафиксировать одинаковый код, входные данные и лимиты контейнера.
2. Прогнать синтетические JMH-бенчмарки.
3. Прогнать enterprise sandbox с одинаковым workload profile.
4. Снять startup/runtime метрики, GC, heap, RSS, p95/p99, throughput и error rate.
5. Повторить несколько прогонов и сравнивать не только среднее время, но и стабильность хвостов.

## Важно

Не делаем вывод по одному запуску и только по среднему времени. Для JVM важны прогрев, JIT-компиляция, GC, контейнерные лимиты, профиль нагрузки и повторяемость результата.

Native Image включён как отдельный AOT/runtime-вариант. Его совместимость и ограничения диагностики проверяются отдельно; отсутствие JVM-only метрик не трактуется как нулевое потребление памяти.

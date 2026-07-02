# JVM Research Sandbox

Песочница для сравнения поведения разных JVM на одинаковом Java-коде: рабочий HotSpot baseline, OpenJ9 и GraalVM JIT.

Цель репозитория — быстро получать воспроизводимые замеры, а потом переносить подход на реальные микросервисы.

## Выбранная JVM-матрица

| Variant | JVM type | Image | Role |
|---|---|---|---|
| `hotspot-work` | HotSpot / BellSoft Liberica | `docker-hub.binary.alfabank.ru/bellsoft/liberica-openjre-alpine:21.0.11-11` | рабочий baseline |
| `openj9` | Eclipse OpenJ9 / IBM Semeru | `ibm-semeru-runtimes:open-21.0.11.0-jdk-jammy` | эталонный OpenJ9 |
| `graalvm-jit` | Oracle GraalVM JDK | `container-registry.oracle.com/graalvm/jdk:21` | GraalVM в JVM/JIT режиме |

Подробности: [`docs/jvm-matrix.md`](docs/jvm-matrix.md).

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
├── scripts/
│   ├── run-benchmarks.sh
│   ├── run-app.sh
│   ├── collect-runtime-metrics.sh
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

## Enterprise sandbox layer

Spring Boot-приложение имитирует типовой микросервисный flow:

```text
HTTP -> MongoDB -> WireMock external API -> mapping -> MongoDB -> Kafka -> schedulers
```

Подробнее: [docs/enterprise-sandbox.md](docs/enterprise-sandbox.md).

Быстрый запуск:

```bash
bash scripts/run-enterprise-sandbox.sh hotspot-work
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

Dockerfile-ы запускают уже собранный runtime distribution и JMH jar. Это нужно, потому что рабочий HotSpot baseline использует OpenJRE-образ, а не JDK-образ.

Сначала собрать артефакты:

```bash
./gradlew clean test installDist jmhJar
```

Потом собрать runtime-образы для JMH/runtime слоя:

```bash
docker build -f docker/hotspot/Dockerfile -t jvm-research:hotspot-work .
docker build -f docker/openj9/Dockerfile -t jvm-research:openj9 .
docker build -f docker/graalvm/Dockerfile -t jvm-research:graalvm-jit .
```

Запуск Spring Boot-приложения:

```bash
docker run --rm jvm-research:hotspot-work
docker run --rm jvm-research:openj9
docker run --rm jvm-research:graalvm-jit
```

Запуск JMH внутри runtime-контейнера:

```bash
docker run --rm -v "$PWD/results:/results" jvm-research:hotspot-work java -jar /opt/jvm-research/jmh-benchmarks.jar -rf json -rff /results/hotspot-work-jmh.json
docker run --rm -v "$PWD/results:/results" jvm-research:openj9 java -jar /opt/jvm-research/jmh-benchmarks.jar -rf json -rff /results/openj9-jmh.json
docker run --rm -v "$PWD/results:/results" jvm-research:graalvm-jit java -jar /opt/jvm-research/jmh-benchmarks.jar -rf json -rff /results/graalvm-jit-jmh.json
```

Для enterprise sandbox используется корневой `Dockerfile` с runtime image как build arg:

```bash
docker build --build-arg RUNTIME_IMAGE=docker-hub.binary.alfabank.ru/bellsoft/liberica-openjre-alpine:21.0.11-11 -t jvm-research-sandbox:hotspot-work .
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

Native Image пока не входит в эту матрицу. Это отдельный эксперимент, потому что там сравнивается уже не JVM runtime, а AOT-компиляция и другой способ поставки приложения.

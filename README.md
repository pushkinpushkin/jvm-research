# JVM Research Sandbox

Песочница для сравнения поведения разных JVM на одинаковом Java-коде: HotSpot, OpenJ9 и GraalVM.

Цель репозитория — быстро получать воспроизводимые замеры, а потом переносить подход на реальные микросервисы.

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
├── src/main/java/dev/pushkin/jvmresearch/SandboxApp.java
├── src/main/java/dev/pushkin/jvmresearch/enterprise/
├── src/jmh/java/dev/pushkin/jvmresearch/AllocationBenchmark.java
├── src/jmh/java/dev/pushkin/jvmresearch/StringProcessingBenchmark.java
└── docs/
    ├── enterprise-sandbox.md
    ├── experiment-plan.md
    └── metrics.md
```

## Два уровня исследования

### 1. JMH / synthetic JVM layer

Микробенчмарки и простой `SandboxApp` нужны, чтобы отдельно смотреть:

```text
- allocations;
- string processing;
- startup;
- warmup;
- heap/GC на простом CPU + allocation сценарии.
```

Проверка проекта:

```bash
./gradlew test
./gradlew jmh
```

Если Gradle Wrapper ещё не сгенерирован локально:

```bash
gradle wrapper --gradle-version 8.14.3
```

### 2. Enterprise sandbox layer

Spring Boot-приложение имитирует типовой микросервисный flow:

```text
HTTP -> MongoDB -> WireMock external API -> mapping -> MongoDB -> Kafka -> consumers -> schedulers
```

Подробнее: [docs/enterprise-sandbox.md](docs/enterprise-sandbox.md).

## Быстрый запуск enterprise sandbox

HotSpot:

```bash
bash scripts/run-enterprise-sandbox.sh hotspot
```

OpenJ9:

```bash
bash scripts/run-enterprise-sandbox.sh openj9
```

GraalVM:

```bash
bash scripts/run-enterprise-sandbox.sh graalvm
```

Сервис поднимается вместе с MongoDB, Kafka и WireMock через `infra/docker-compose.yml`.

Проверка API:

```bash
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

Старые Dockerfile для JMH-слоя остаются:

```bash
docker build -f docker/hotspot/Dockerfile -t jvm-research:hotspot .
docker build -f docker/openj9/Dockerfile -t jvm-research:openj9 .
docker build -f docker/graalvm/Dockerfile -t jvm-research:graalvm .
```

Для enterprise sandbox используется корневой `Dockerfile` с runtime image как build arg:

```bash
docker build --build-arg RUNTIME_IMAGE=eclipse-temurin:21-jre -t jvm-research-sandbox:hotspot .
docker build --build-arg RUNTIME_IMAGE=ibm-semeru-runtimes:open-21-jre -t jvm-research-sandbox:openj9 .
docker build --build-arg RUNTIME_IMAGE=ghcr.io/graalvm/jdk-community:21 -t jvm-research-sandbox:graalvm .
```

## Базовая идея исследования

1. Зафиксировать одинаковый код, входные данные и лимиты контейнера.
2. Прогнать синтетические JMH-бенчмарки.
3. Прогнать enterprise sandbox с одинаковым chaos profile.
4. Снять startup/runtime метрики, GC, heap, RSS, p95/p99, throughput и error rate.
5. Повторить несколько прогонов и сравнивать не только среднее время, но и стабильность хвостов.

## Важно

Не делаем вывод по одному запуску и только по среднему времени. Для JVM важны прогрев, JIT-компиляция, GC, контейнерные лимиты, профиль нагрузки и повторяемость результата.

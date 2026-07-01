# JVM Research Sandbox

Песочница для сравнения поведения разных JVM на одинаковом Java-коде: HotSpot, OpenJ9 и GraalVM.

Цель репозитория — быстро получать воспроизводимые замеры, а потом переносить подход на реальные микросервисы.

## Что внутри

```text
.
├── build.gradle.kts
├── settings.gradle.kts
├── docker/
│   ├── hotspot/Dockerfile
│   ├── openj9/Dockerfile
│   └── graalvm/Dockerfile
├── scripts/
│   ├── run-benchmarks.sh
│   ├── run-app.sh
│   └── collect-runtime-metrics.sh
├── src/main/java/dev/pushkin/jvmresearch/SandboxApp.java
├── src/jmh/java/dev/pushkin/jvmresearch/AllocationBenchmark.java
├── src/jmh/java/dev/pushkin/jvmresearch/StringProcessingBenchmark.java
└── docs/
    ├── experiment-plan.md
    └── metrics.md
```

## Быстрый старт

Если Gradle Wrapper ещё не сгенерирован локально:

```bash
gradle wrapper --gradle-version 8.14.3
```

Проверка проекта:

```bash
./gradlew test
./gradlew jmh
```

## Docker-сборка JVM-вариантов

```bash
docker build -f docker/hotspot/Dockerfile -t jvm-research:hotspot .
docker build -f docker/openj9/Dockerfile -t jvm-research:openj9 .
docker build -f docker/graalvm/Dockerfile -t jvm-research:graalvm .
```

Запуск JMH внутри контейнера:

```bash
docker run --rm -v "$PWD/results:/workspace/build/reports/jmh" jvm-research:hotspot ./gradlew --no-daemon clean jmh
docker run --rm -v "$PWD/results:/workspace/build/reports/jmh" jvm-research:openj9 ./gradlew --no-daemon clean jmh
docker run --rm -v "$PWD/results:/workspace/build/reports/jmh" jvm-research:graalvm ./gradlew --no-daemon clean jmh
```

## Базовая идея исследования

1. Зафиксировать одинаковый код, входные данные и лимиты контейнера.
2. Прогнать синтетические JMH-бенчмарки.
3. Прогнать простое sandbox-приложение и снять startup/runtime метрики.
4. Перенести методику на один реальный микросервис.
5. Сравнить throughput, memory footprint, GC, warmup, startup и стабильность p95/p99.

## Важно

Не делаем вывод по одному запуску и только по среднему времени. Для JVM важны прогрев, JIT-компиляция, GC, контейнерные лимиты, профиль нагрузки и повторяемость результата.

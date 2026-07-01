# JVM Research Sandbox

Песочница для сравнения поведения разных JVM на одинаковом Java-коде: рабочий HotSpot baseline, OpenJ9 и GraalVM JIT.

Цель репозитория — быстро получать воспроизводимые замеры, а потом переносить подход на реальные микросервисы.

## Выбранная JVM-матрица

| Variant | JVM type | Image | Role |
|---|---|---|---|
| `hotspot-work` | HotSpot / BellSoft Liberica | `registry.example.invalid/bellsoft/liberica-openjre-alpine:21.0.11-11` | рабочий baseline |
| `openj9` | Eclipse OpenJ9 / IBM Semeru | `ibm-semeru-runtimes:open-21.0.11.0-jdk-jammy` | эталонный OpenJ9 |
| `graalvm-jit` | Oracle GraalVM JDK | `container-registry.oracle.com/graalvm/jdk:21` | GraalVM в JVM/JIT режиме |

Подробности: [`docs/jvm-matrix.md`](docs/jvm-matrix.md).

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
    ├── jvm-matrix.md
    ├── local-runbook.md
    └── metrics.md
```

## Быстрый старт

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

## Docker-сборка JVM-вариантов

```bash
docker build -f docker/hotspot/Dockerfile -t jvm-research:hotspot-work .
docker build -f docker/openj9/Dockerfile -t jvm-research:openj9 .
docker build -f docker/graalvm/Dockerfile -t jvm-research:graalvm-jit .
```

Запуск JMH внутри контейнера:

```bash
docker run --rm -v "$PWD/results:/workspace/build/reports/jmh" jvm-research:hotspot-work ./gradlew --no-daemon clean jmh
docker run --rm -v "$PWD/results:/workspace/build/reports/jmh" jvm-research:openj9 ./gradlew --no-daemon clean jmh
docker run --rm -v "$PWD/results:/workspace/build/reports/jmh" jvm-research:graalvm-jit ./gradlew --no-daemon clean jmh
```

## Базовая идея исследования

1. Зафиксировать одинаковый код, входные данные и лимиты контейнера.
2. Прогнать синтетические JMH-бенчмарки.
3. Прогнать простое sandbox-приложение и снять startup/runtime метрики.
4. Перенести методику на один реальный микросервис.
5. Сравнить throughput, memory footprint, GC, warmup, startup и стабильность p95/p99.

## Важно

Не делаем вывод по одному запуску и только по среднему времени. Для JVM важны прогрев, JIT-компиляция, GC, контейнерные лимиты, профиль нагрузки и повторяемость результата.

Native Image пока не входит в эту матрицу. Это отдельный эксперимент, потому что там сравнивается уже не JVM runtime, а AOT-компиляция и другой способ поставки приложения.

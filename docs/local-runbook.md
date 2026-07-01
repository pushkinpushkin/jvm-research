# Local runbook

## 1. Первый запуск

```bash
gradle wrapper --gradle-version 8.14.3
chmod +x gradlew scripts/*.sh
./gradlew test
```

## 2. Запуск JMH локально

```bash
JVM_NAME=local ./scripts/run-benchmarks.sh
```

Результат появится в:

```text
results/local/jmh-results.json
results/local/java-version.txt
```

## 3. Запуск sandbox-приложения

```bash
SANDBOX_ITERATIONS=20 \
SANDBOX_PAYLOAD_SIZE=100000 \
SANDBOX_SLEEP_MILLIS=250 \
JVM_NAME=local \
./scripts/run-app.sh
```

## 4. Сбор runtime-метрик вручную

В одном терминале:

```bash
JVM_NAME=local ./scripts/run-app.sh
```

Во втором терминале найти PID Java-процесса и запустить:

```bash
SAMPLES=60 INTERVAL_SECONDS=1 OUT=results/local/runtime-metrics.csv ./scripts/collect-runtime-metrics.sh <pid>
```

## 5. Docker

Перед Docker-запуском в репозитории должен быть Gradle Wrapper, то есть файлы `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`.

Сгенерировать wrapper можно так:

```bash
gradle wrapper --gradle-version 8.14.3
```

После этого:

```bash
docker build -f docker/hotspot/Dockerfile -t jvm-research:hotspot .
docker build -f docker/openj9/Dockerfile -t jvm-research:openj9 .
docker build -f docker/graalvm/Dockerfile -t jvm-research:graalvm .
```

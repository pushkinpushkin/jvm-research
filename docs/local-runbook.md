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

## 3. Запуск sandbox-приложения локально

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

## 5. Docker runtime images

Dockerfile-ы запускают уже собранный application distribution и JMH jar. Это важно, потому что рабочий HotSpot baseline — `openjre`, а не JDK. Такой контейнер должен запускать приложение, а не собирать Gradle-проект внутри себя.

Сначала собрать артефакты локально или в CI:

```bash
./gradlew clean test installDist jmhJar
```

После этого собрать образы:

```bash
docker build -f docker/hotspot/Dockerfile -t jvm-research:hotspot-work .
docker build -f docker/openj9/Dockerfile -t jvm-research:openj9 .
docker build -f docker/graalvm/Dockerfile -t jvm-research:graalvm-jit .
```

Запуск sandbox-приложения:

```bash
docker run --rm jvm-research:hotspot-work
docker run --rm jvm-research:openj9
docker run --rm jvm-research:graalvm-jit
```

Запуск JMH jar внутри runtime-контейнера:

```bash
docker run --rm -v "$PWD/results:/results" jvm-research:hotspot-work java -jar /opt/jvm-research/jmh-benchmarks.jar -rf json -rff /results/hotspot-work-jmh.json
docker run --rm -v "$PWD/results:/results" jvm-research:openj9 java -jar /opt/jvm-research/jmh-benchmarks.jar -rf json -rff /results/openj9-jmh.json
docker run --rm -v "$PWD/results:/results" jvm-research:graalvm-jit java -jar /opt/jvm-research/jmh-benchmarks.jar -rf json -rff /results/graalvm-jit-jmh.json
```

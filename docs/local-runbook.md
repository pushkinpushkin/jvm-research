# Local runbook

## Требования

```text
Java 21
Docker Desktop / Docker Engine with Compose v2
curl
python3
k6 optional
```

`k6` не обязателен: если его нет, `scripts/run-experiment.sh` сохранит synthetic smoke и пропустит enterprise load.

## Быстрая проверка проекта

```bash
./gradlew clean test bootJar
```

## Полный JVM experiment run

HotSpot baseline на BellSoft Liberica:

```bash
bash scripts/run-experiment.sh profiles/work-hotspot-baseline.env
```

OpenJ9:

```bash
bash scripts/run-experiment.sh profiles/work-openj9-baseline.env
```

GraalVM JIT:

```bash
bash scripts/run-experiment.sh profiles/work-graalvm-baseline.env
```

С переопределением нагрузки:

```bash
RATE=30 DURATION=10m ORDER_POOL=20000 bash scripts/run-experiment.sh profiles/work-hotspot-baseline.env
```

## Где лежат результаты

```text
results/<RUN_ID>/
  metadata.json
  run-info.json
  health.json
  synthetic-runtime.json
  k6-summary.json
  k6.log
  prometheus-before.txt
  prometheus-after.txt
  app.log
  docker-compose-ps.txt
  docker-stats.txt
  app-logs/gc.log
  jcmd-vm-command-line.txt
  jcmd-vm-flags.txt
  jcmd-vm-system-properties.txt
```

## Что проверить после запуска

```bash
cat results/<RUN_ID>/metadata.json
cat results/<RUN_ID>/run-info.json
cat results/<RUN_ID>/docker-stats.txt
cat results/<RUN_ID>/synthetic-runtime.json
```

В `run-info.json` должны совпадать:

```text
jvmVariant
jvmProfile
runtime inputArguments
maxHeapMb около 512
availableProcessors около 1
```

## Ручной запуск sandbox

```bash
bash scripts/run-enterprise-sandbox.sh hotspot-liberica
bash scripts/run-enterprise-sandbox.sh openj9
bash scripts/run-enterprise-sandbox.sh graalvm-jit
```

Проверка API:

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/run-info
curl -X POST 'http://localhost:8080/synthetic/runtime?iterations=20&payloadSize=100000'
curl -X POST 'http://localhost:8080/orders/generate?count=1000'
curl -X POST 'http://localhost:8080/orders/order-1/process'
```

## Важное ограничение

Локальный запуск не идентичен Kubernetes. Он воспроизводимо моделирует один pod: CPU limit, memory limit, heap, timezone, port, JVM options и runtime image. На macOS Docker работает через VM, поэтому абсолютные цифры RSS/CPU могут отличаться от Linux/Kubernetes node.

# Enterprise sandbox flow

Enterprise sandbox дополняет JMH: JMH показывает микробенчмарки, а sandbox показывает JVM на смеси HTTP, MongoDB, Kafka, WireMock, scheduler'ов, JSON-маппинга и управляемых ошибок.

## Entry point

```text
dev.pushkin.jvmresearch.enterprise.EnterpriseSandboxApplication
```

## Endpoints

```text
POST /synthetic/runtime?iterations=20&payloadSize=100000
POST /orders/generate?count=10000
POST /orders/{orderId}/process
GET /orders/{orderId}
GET /run-info
GET /actuator/prometheus
```

`/run-info` показывает реальные JVM startup arguments, Java/VM vendor/version, heap, processors, run id, JVM profile и work-like reference metadata.

## Work-like pod profile

Локальный запуск моделирует форму одного pod'а:

```text
container cpu limit: 1
container memory limit: 1Gi
java heap: 512Mi
timezone: Europe/Moscow
port: 8080
extraJavaOpts: -Dserver.max-http-request-header-size=30000
```

Через Docker Compose моделируются runtime-ограничения:

```text
cpus: ${CONTAINER_CPU_LIMIT:-1}
mem_limit: ${CONTAINER_MEMORY_LIMIT:-1g}
JAVA_TOOL_OPTIONS: ${JAVA_TOOL_OPTIONS:-}
```

`WORK_*` значения сохраняются как metadata и видны в `/run-info`.

## Запуск sandbox

```bash
bash scripts/run-enterprise-sandbox.sh hotspot-liberica
bash scripts/run-enterprise-sandbox.sh openj9
bash scripts/run-enterprise-sandbox.sh graalvm-jit
```

## Полный эксперимент

```bash
bash scripts/run-experiment.sh profiles/work-hotspot-baseline.env
bash scripts/run-experiment.sh profiles/work-openj9-baseline.env
bash scripts/run-experiment.sh profiles/work-graalvm-baseline.env
```

С переопределением нагрузки:

```bash
RATE=30 DURATION=10m ORDER_POOL=20000 bash scripts/run-experiment.sh profiles/work-hotspot-baseline.env
```

## Метрики

Минимально сравнивать:

```text
- startup time
- throughput / RPS
- p50 / p95 / p99 latency
- error rate
- heap usage
- GC pauses/count
- process RSS
- docker stats
- prometheus before/after
```

Локальный запуск не идентичен Kubernetes, но воспроизводимо моделирует один pod с одинаковыми лимитами и JVM options.

# Enterprise sandbox flow

Этот слой нужен не вместо JMH, а рядом с ним. JMH показывает микроповедение JVM, а enterprise sandbox показывает поведение JVM на смеси HTTP, MongoDB, Kafka, WireMock, scheduler'ов, JSON-маппинга и управляемых ошибок.

## Entry point

Основное приложение одно:

```text
dev.pushkin.jvmresearch.enterprise.EnterpriseSandboxApplication
```

Старый standalone `SandboxApp` удален. Быстрый CPU/allocation smoke теперь живет внутри Spring Boot как HTTP endpoint.

## Компоненты

```text
k6
  -> Spring Boot sandbox-service
      -> synthetic runtime endpoint
      -> run info endpoint
      -> MongoDB
      -> WireMock external API
      -> Kafka producer
      -> Kafka consumers
      -> schedulers
      -> actuator/prometheus metrics
```

## Synthetic runtime endpoint

```text
POST /synthetic/runtime?iterations=20&payloadSize=100000
```

Этот endpoint нужен для быстрого smoke-прогона JVM без основной enterprise-нагрузки. Он выполняет:

```text
- CPU work
- allocation work
- latency samples
- heap used
- GC count
- p50 / p95 / max
```

## Run info endpoint

```text
GET /run-info
```

Этот endpoint нужен, чтобы проверять не только переданные env-переменные, но и то, что JVM реально увидела на старте:

```text
- runId / runProfile / jvmVariant / jvmProfile
- gitSha
- java version/vendor
- VM name/version/vendor
- ManagementFactory RuntimeMXBean inputArguments
- max heap / total heap / free heap
- available processors
- work-like Helm reference
```

## Основной HTTP flow

```text
POST /orders/{orderId}/process
```

Сервис делает:

```text
1. Читает заявку из MongoDB или создает синтетическую.
2. Переводит ее в PROCESSING.
3. Вызывает WireMock: /external/fns-data/{orderId}.
4. Делает копирование и маппинг payload.
5. Обновляет вложенный fnsProcess.
6. Сохраняет документ в MongoDB.
7. Публикует ORDER_STATUS_CHANGED в Kafka.
8. Kafka listener читает событие и пишет историю обработки.
9. При COMPLETED публикуется ACCOUNT_FULLY_OPENED.
10. Business event listener делает dedup и сохраняет eventId в OrderDocument.
```

## Фоновые процессы

```text
StatusPollingScheduler
  -> ищет WAITING_EXTERNAL_STATUS
  -> вызывает WireMock status API
  -> переводит заявку в COMPLETED или FAILED
  -> публикует Kafka event

RetryOrdersScheduler
  -> ищет FAILED
  -> возвращает часть заявок в NEW для повторной обработки

OrderStatusChangedListener
  -> читает order.status.changed
  -> отсекает дубликаты по eventId
  -> пишет техническую историю обработки
  -> для COMPLETED публикует ACCOUNT_FULLY_OPENED

BusinessEventListener
  -> читает business.event.occurred
  -> отсекает дубликаты по eventId
  -> сохраняет eventId в processedBusinessEvents внутри OrderDocument
```

## Workload profile

Ошибки не случайные, а детерминированные: значение считается от `orderId + point + salt`. Поэтому один и тот же orderId стабильно попадает в один и тот же сценарий. Это важно для сравнения JVM-вариантов.

Настройки:

```yaml
sandbox:
  profile:
    enabled: true
    external-api-long-delay-percent: 3
    external-api-error-percent: 5
    external-api-invalid-percent: 2
    external-api-slow-percent: 15
    mongo-conflict-percent: 2
    kafka-duplicate-percent: 5
```

## Work-like pod profile

Профили `profiles/work-*.env` приближают локальный запуск к рабочему Helm values:

```text
container cpu limit: 1
container memory limit: 1Gi
java heap: 512Mi
timezone: Europe/Moscow
port: 8080
extraJavaOpts: -Dserver.max-http-request-header-size=30000
```

Локальный запуск моделирует runtime-ограничения через Docker Compose:

```text
cpus: ${CONTAINER_CPU_LIMIT:-1}
mem_limit: ${CONTAINER_MEMORY_LIMIT:-1g}
JAVA_TOOL_OPTIONS: ${JAVA_TOOL_OPTIONS:-}
```

## Deduplication

`EnterpriseEventPublisher` может отправить один и тот же `BusinessEvent` дважды, если `kafka-duplicate-percent` попал в профиль нагрузки. Consumer-слой защищается двумя уровнями:

```text
1. InMemoryBusinessEventDeduplicationService
   -> быстрый process-local guard по eventId.

2. OrderDocument.processedBusinessEvents
   -> документ заявки хранит уже обработанные business event id.
```

Это не production-grade distributed dedup, но для JVM-песочницы достаточно: появляются Kafka deserialization, consumer threads, повторная обработка, проверка идемпотентности и дополнительная запись в MongoDB.

## Запуск

```bash
bash scripts/run-enterprise-sandbox.sh hotspot-work
bash scripts/run-enterprise-sandbox.sh openj9
bash scripts/run-enterprise-sandbox.sh graalvm-jit
```

Если хочется заранее скачать внешние образы, не запускай обычный `docker compose pull`: `sandbox-service` собирается локально и не существует в registry. Используй один из вариантов:

```bash
docker compose -f infra/docker-compose.yml pull --ignore-buildable
```

или явно только зависимости:

```bash
docker compose -f infra/docker-compose.yml pull mongo kafka wiremock
```

После этого:

```bash
docker compose -f infra/docker-compose.yml up --build
```

Полный запуск эксперимента с сохранением результатов:

```bash
bash scripts/run-experiment.sh profiles/work-hotspot-baseline.env
bash scripts/run-experiment.sh profiles/work-openj9-baseline.env
bash scripts/run-experiment.sh profiles/work-graalvm-baseline.env
```

Нагрузка:

```bash
RATE=20 DURATION=5m ORDER_POOL=10000 bash scripts/run-load.sh
```

## Метрики

Actuator Prometheus endpoint:

```text
GET /actuator/prometheus
```

Минимально сравнивать:

```text
- throughput / RPS
- p50 / p95 / p99 latency
- error rate
- heap usage
- GC pauses/count
- process RSS
- scheduler batch duration
- external API duration
- Mongo read/update duration
- Kafka consumer processing rate
- duplicate event count in logs
```

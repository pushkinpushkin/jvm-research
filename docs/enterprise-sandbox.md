# Enterprise sandbox flow

Этот слой нужен не вместо JMH, а рядом с ним. JMH показывает микроповедение JVM, а enterprise sandbox показывает поведение JVM на смеси HTTP, MongoDB, Kafka, WireMock, scheduler'ов, JSON-маппинга и управляемых ошибок.

## Компоненты

```text
k6
  -> Spring Boot sandbox-service
      -> MongoDB
      -> WireMock external API
      -> Kafka producer
      -> Kafka consumers
      -> schedulers
      -> actuator/prometheus metrics
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
4. Делает тяжелое копирование/маппинг payload.
5. Обновляет вложенный fnsProcess.
6. Сохраняет документ в MongoDB.
7. Публикует ORDER_STATUS_CHANGED в Kafka.
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
```

## Chaos profile

Ошибки не случайные, а детерминированные: bucket считается от `orderId + point + salt`. Поэтому один и тот же orderId стабильно попадает в один и тот же сценарий. Это важно для сравнения JVM-вариантов.

Настройки:

```yaml
sandbox:
  chaos:
    enabled: true
    external-api-long-delay-percent: 3
    external-api-error-percent: 5
    external-api-malformed-percent: 2
    external-api-slow-percent: 15
    mongo-conflict-percent: 2
    kafka-duplicate-percent: 5
```

## Запуск

```bash
bash scripts/run-enterprise-sandbox.sh hotspot
bash scripts/run-enterprise-sandbox.sh openj9
bash scripts/run-enterprise-sandbox.sh graalvm
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
- Kafka consumer lag
- scheduler batch duration
- external API duration
- Mongo read/update duration
```

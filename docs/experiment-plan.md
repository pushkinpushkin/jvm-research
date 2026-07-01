# План JVM-эксперимента

## 1. Цель

Сравнить несколько JVM-вариантов на одинаковой нагрузке:

- HotSpot / Eclipse Temurin
- OpenJ9 / IBM Semeru
- GraalVM JDK

Результат исследования должен помочь решить, есть ли смысл переносить реальные сервисы на альтернативную JVM и какие риски у такого перехода.

## 2. Гипотезы

### H1. OpenJ9 может дать меньший memory footprint

Проверяем RSS, heap usage и общее потребление памяти при одинаковых лимитах.

### H2. GraalVM может отличаться по warmup и throughput

Проверяем поведение на JMH и на длительном запуске sandbox-приложения.

### H3. Startup и warmup могут быть важнее среднего throughput

Для микросервисов в Kubernetes важны не только максимальные операции в секунду, но и время выхода на стабильный режим после старта pod.

## 3. Базовый порядок запуска

Для каждой JVM:

1. Зафиксировать версию JVM через `java -version`.
2. Запустить JMH через `JVM_NAME=hotspot ./scripts/run-benchmarks.sh`.
3. Запустить sandbox-приложение через `JVM_NAME=hotspot ./scripts/run-app.sh`.
4. Сохранить результаты в `results/<jvm-name>/`.
5. Повторить минимум 3 раза.

## 4. Правила честного сравнения

- Одинаковая версия Java language level: Java 21.
- Одинаковые CPU и memory лимиты.
- Одинаковые входные параметры: размер payload, число итераций, JVM options.
- Отдельно сравнивать default options и tuned options.
- Не смешивать результаты локальной машины и Kubernetes.
- Не делать выводы по одному прогону.

## 5. Минимальный набор прогонов

| Сценарий | Что проверяем |
|---|---|
| `./gradlew jmh` | синтетический throughput / average time |
| `scripts/run-app.sh` | startup, warmup, heap, GC count |
| `scripts/collect-runtime-metrics.sh` | RSS, VSZ, CPU, memory percent, threads |
| Kubernetes pod | поведение при лимитах контейнера |

## 6. Следующий этап

После синтетической песочницы выбрать один небольшой реальный микросервис и повторить тот же подход:

- один и тот же код приложения;
- разные base image JVM;
- одинаковые requests/limits;
- одинаковый сценарий нагрузки;
- сбор Micrometer, Prometheus, JFR и GC logs.

# Метрики JVM-исследования

## Основные метрики

| Метрика | Зачем нужна |
|---|---|
| Startup time | Сколько времени нужно приложению до готовности |
| Warmup time | Когда JVM выходит на стабильную производительность |
| Throughput | Сколько операций выполняется за единицу времени |
| Average time | Среднее время операции |
| p95 / p99 | Стабильность под нагрузкой |
| RSS | Реальное потребление памяти процессом |
| Heap used | Использование Java heap |
| GC count / GC time | Частота и стоимость сборок мусора |
| Threads | Количество потоков процесса |
| CPU usage | Нагрузка на CPU |

## Что собирать на каждом прогоне

1. `java -version`.
2. JVM options.
3. CPU и memory лимиты.
4. JMH JSON result.
5. Лог запуска sandbox-приложения.
6. Runtime metrics CSV.
7. GC logs, если включены.
8. JFR, если включён профильный прогон.

## JVM options для первого этапа

Начинаем с default-настроек. После базовой линии можно добавить отдельные профили:

### HotSpot

```text
-Xms256m -Xmx512m
-Xlog:gc*:file=results/hotspot/gc.log:time,uptime,level,tags
```

### OpenJ9

```text
-Xms256m -Xmx512m
-Xverbosegclog:results/openj9/gc.log
```

### GraalVM JDK

```text
-Xms256m -Xmx512m
-Xlog:gc*:file=results/graalvm/gc.log:time,uptime,level,tags
```

## Как читать результат

Хороший кандидат для реального сервиса — это не обязательно JVM с лучшим средним throughput. Важнее сочетание факторов:

- предсказуемый startup;
- быстрый или приемлемый warmup;
- умеренное потребление памяти;
- отсутствие резких p95/p99 скачков;
- понятная диагностика GC/JFR;
- совместимость с текущими библиотеками и CI/CD.

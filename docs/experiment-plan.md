# План исследования runtime и памяти

## Цель

Определить, какой runtime и профиль памяти позволяют снизить фактическое потребление
RAM ненагруженных и слабонагруженных Spring Boot-сервисов при приемлемых startup,
latency, ошибках и эксплуатационных ограничениях. Отдельно проверить, сохраняется ли
выигрыш при длительной рабочей нагрузке.

## Оси эксперимента

| Ось | Значения |
|---|---|
| Runtime | HotSpot Liberica, OpenJ9, GraalVM JIT, GraalVM Native Image |
| Memory | fixed-heap / elastic-heap для JVM; отдельный native-default |
| Workload | idle; low-load RATE=1; load RATE=10; load RATE=25 |
| Ресурсы | одинаковые 2 CPU, 1 GiB на приложение |
| Длительность | smoke 1–3m; основной workload 30m |
| Повторы | минимум 3, с чередованием порядка runtime |

1. Сначала unit tests, JVM/native build и короткие smoke-прогоны всех интеграций.
2. Зафиксировать git commit, образы/digests, хост, версии Docker/k6, параметры.
3. Измерить startup до readiness. Collector запускается после readiness.
4. Idle: чистая MongoDB/Kafka, без seed/synthetic/business requests; scheduler'ы и
   мониторинг работают. Это idle приложения с инфраструктурой, не полностью спящий процесс.
5. Low-load/load: прежний enterprise k6 flow, одинаковые ORDER_POOL/SEED_ORDERS,
   VU limits, задержки/ошибки WireMock, logging и scrapes.
6. Сравнить memory curve и memory возле 5/10/20/30 минут, CPU, heap/GC,
   latency, ошибки, dropped iterations. Отдельно посмотреть k6 time series, если включена.
7. Повторить серии с elastic heap, не смешивая их с fixed baseline.

30 минут — окно наблюдения, а не гарантия завершения JIT-прогрева.
При фиксированном RATE измеряем стоимость одинакового входного бизнес-потока;
это не поиск максимальной пропускной способности. Проверьте фактически выполненные
requests/iterations и `dropped_iterations`: одинаковый заданный RATE ещё не гарантирует
одинаковую фактическую работу.

Collector работает от readiness до конца k6 (включая seed/setup/graceful stop).
`elapsed_seconds` отсчитывается от readiness. DURATION задаёт длительность основной
нагрузки; итоговая длина CSV может быть больше. В idle почти всё окно является наблюдением.
`initial_mib` — первая измеренная точка, не доказанное «стабильное состояние».

Каждый автоматический run использует свежий Compose project и удаляет только его данные.
Другие процессы, параллельные сборки и перегруженный Docker host искажают результат.
Результаты локальной VM и Linux/Kubernetes node анализируются отдельными сериями.

## Интерпретация

Вывод должен отвечать на два разных вопроса:
1. Какой JVM-дистрибутив экономичнее при фиксированном production-like heap?
2. Насколько elastic heap / native поставка снижают idle footprint и какой ценой?

Native build time, доступность диагностики и подтверждённая совместимость входят в решение
о применимости. Сам по себе меньший heap не доказывает меньший расход RAM контейнера.
Исторические результаты 1 CPU / 10m остаются отдельной серией.
Ни один run со status=failed не считается успешной базовой точкой.

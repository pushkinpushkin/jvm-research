# Схема результатов v3

Канонические источники: `scripts/experiment_support.py`, `collect-container-metrics.py`, `phase-report.py`, `validate-run.py`. Артефакты каждого запуска лежат отдельно в `results/<...>/<run-id>/`; каталог не входит в git. Начинай анализ с admission и агрегатов, не с полной временной серии.

## Артефакты

| Файл | Назначение / источник |
|---|---|
| `metadata.json` | Manifest: schemaVersion=3, runId, gitSha/dirty, runtime/profile/image, флаги, host/system, лимиты, сценарий, rate/duration, seed/pool, sampling, trace SHA, SLO, времена, exitCode, comparisonEligible, observedWork |
| `run-info.json` | Фактическая конфигурация работающего runtime, `/run-info` |
| `container-inspect.json`, `container-final.json` | Docker image/container ID, лимиты, время старта, рестарты/OOM/состояние |
| `workload.json` | Seed, детерминированная последовательность ID/режимов/ожидаемых результатов |
| `phases.json` | Границы фаз, epoch seconds |
| `validation.json` | `eligible`, статус, конкретные `reasons`; результат admission, не рейтинг JVM |
| `phase-summary.json` | `phases.<phase>.whole/windows`: агрегаты; `runtimeSamples`: временные точки heap/GC, читать отдельно только при необходимости |
| `runtime-metrics.csv` | Наблюдаемые отсчёты cgroup с elapsed/phase/phase_elapsed |
| `cgroup/`, `prometheus/`, `state/` | Сырые cgroup, Prometheus и прикладное состояние, привязанные ко времени |
| `k6-summary.json`, опциональная серия k6 | HTTP/бизнес-счётчики и latency без seed |
| `state-before.json`, `state-after.json`, `kafka-lag.txt` | Прикладной баланс и завершение фоновой работы; lag двух topics |
| Логи приложения, k6 и инфраструктуры | Диагностика конкретной причины отклонения |

Manifest уже формируется автоматически; не восстанавливай настройки по shell history. Для воспроизводимого baseline дополнительно архивировать версии Docker, ОС/ядро хоста и immutable digests всех зависимостей: текущий manifest сам по себе не фиксирует весь стенд.

## Величины и формулы

| Поле | Единицы | Определение |
|---|---|---|
| `memory_used_bytes` | bytes | working set = memory.current − inactive_file при inactive_file < current, иначе current (правило Docker); не process RSS |
| `memory_current_bytes` | bytes | полный текущий расход cgroup |
| `memory_peak_bytes` | bytes | kernel memory.peak за жизнь cgroup, не пик фазы |
| `memory_swap_bytes`, `oom`, `oom_kill` | bytes / counts | swap.current и memory.events |
| `cpu_usage_usec`, `cpu_throttled_usec` | µs | накопленные cgroup CPU usage / throttled time |
| `cpu_nr_periods`, `cpu_nr_throttled` | counts | накопленные периоды CPU / периоды с throttling |
| `jvm_memory_{used,committed,max}_bytes_heap` | bytes | сумма доступных heap pools Prometheus; nonheap отдельно |
| Доступные `jvm_gc_*`, `process_cpu_seconds_total` | по семантике Prometheus | накопленные count/sum/total; CPU в seconds |
| `business_latency` | ms | длительность только process HTTP; p95/p99 из k6 |
| business / HTTP / mode / fault counters | counts | раздельные метрики полезной работы и ожидаемых/неожиданных результатов |

1 MiB = 1048576 bytes. CPU·s = Δusage_usec/1e6. Среднее занятых CPU = CPU·s/наблюдаемые seconds; доля бюджета 2 CPU = это значение/2. Не суммировать CPU проценты. Отрицательные/недоступные max heap pools не превращать в нулевую память; Native может не предоставлять JVM/GC-метрики.

## Фазы, прогрев и окна

Startup: Docker StartedAt → первый health UP, polling 250 мс после готовности зависимостей. Preparation и seed отдельно. В основной методике нет скрытого отбрасывания прогрева: вся load-latency входит в SLO; память показывается по временным окнам. Дополнительный steady-state анализ требует заранее записанного окна и не заменяет общую latency. Fresh idle не выполняет synthetic warmup.

Sampling по умолчанию 5 секунд. Для каждой фазы: whole и окна 0–60, 60–300, 300–600, 600–1200, 1200–1800, 1800–3600 секунд относительно её начала. В окно входят фактически измеренные точки; `observedSeconds` — расстояние между первой и последней, не обещанная длительность. Вне наблюдения нет экстраполяции; одноточечное окно не даёт timeWeightedMean.

Средняя память = Σ[(vᵢ+vᵢ₊₁)/2 × (tᵢ₊₁−tᵢ)] / (t_last−t_first). `sampleMedian` и `sampleMax` относятся к наблюдаемым точкам, а не всему непрерывному процессу. CPU/throttling в окне — последний накопленный счётчик минус первый. `lifetimeCgroupPeakBytesAtWindowEnd` — полный пик с запуска cgroup; его нельзя интерпретировать как фазовый working-set peak. Короткие startup spikes могут быть пропущены sampling.

## Ошибки и admission

HTTP error rate, `business_processed`, `expected_faults`, `unexpected_results`, scheduler/consumer/outbox errors учитываются отдельно. `iterations = expectedIterations + trace_boundary_skips`, skips ∈ {0,1}; `http_reqs = business_requests = expectedIterations`; dropped=0. Boundary skip возможен только для index=trace.length и не выполняет HTTP. Искусственные отказы не дают права игнорировать неожиданные исключения.

Validator проверяет trace SHA, профиль/лимиты, порядок фаз и покрытие данными, точные counts/режимы/SLO, границы состояния, прикладной баланс, пустой outbox, lag, OOM/swap/restarts и полноту telemetry. Ненулевой exit или отсутствующие обязательные данные отклоняют запуск. Успех k6 недостаточен. Возможность отсутствующих JVM-метрик Native не распространяется на обязательные cgroup/HTTP/async данные.

## Сравнение и доказательства

Сначала `validation.json`, затем manifest и `phase-summary.phases`; heap/GC читать адресно из runtimeSamples. Сравнение допускает только eligible runs с одинаковыми условиями, trace и observedWork. Не смешивать normal/faults, elastic/fixed, разную длительность и хост; не усреднять latency percentiles между повторами. Сохранять разброс повторов и различать факт и объяснение.

Независимый пересчёт формул и реальные проверенные run IDs: [docs/verification-v1.md](docs/verification-v1.md). Влияние collector/HTTP telemetry оценивается до baseline; малые отличия ниже его влияния не трактовать как преимущество runtime.

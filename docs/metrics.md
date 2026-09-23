# Метрики и границы измерений

## Universal/container metrics

`scripts/collect-runtime-metrics.sh --container ID` снимает `docker stats --no-stream`
по умолчанию каждые 5 секунд, на хосте одинаково для JVM и native.
Старый интерфейс с PID сохранён через `collect-process-metrics.sh`.

| CSV поле | Смысл |
|---|---|
| timestamp | UTC ISO timestamp окончания получения Docker stats |
| elapsed_seconds | секунды после успешного readiness |
| phase | preparation / idle / low-load / load |
| cpu_percent | Docker CPU %, 100% соответствует одному полностью занятому ядру |
| memory_used_bytes | Docker CLI usage, на Linux с вычетом inactive file cache |
| memory_limit_bytes | лимит по Docker stats |
| memory_percent | Docker MemPerc |
| pids | Linux tasks (процессы/потоки), не число Java threads |

`memory_used_bytes` — оценка working set по Docker CLI, **не RSS**, не полный
cgroup memory.current и не Java heap. CLI округляет человекочитаемые значения;
перевод MiB/GiB в bytes не восстанавливает утраченную точность.
Отдельный RSS/cgroup total здесь не снимается, чтобы не зависеть от shell/procfs layout
в каждом образе. Не подписывайте этот столбец как RSS в отчёте.
Docker stats обычно занимает около секунды и более: период — целевой, а не real-time гарантия.
При завершении выполняется финальная выборка. Если контейнер исчезает или collector
падает, run завершается ошибкой; подстановка нулей запрещена.

Агрегация `compare-benchmark-root.sh`: арифметическое mean, median, линейно
интерполированный p95, максимальная **измеренная** точка; initial = первая точка.
Пики между выборками могут быть пропущены. Точки 5/10/20/30m берутся ближайшие
в пределах max(2×interval, 5s), только если наблюдение достигло соответствующего времени.
Коротким run значения не экстраполируются. Агрегация охватывает весь post-readiness CSV,
включая setup/seed и финальный sample; фазу можно отфильтровать отдельно.

## Runtime-specific metrics

`prometheus/*.prom` содержит полные Micrometer scrapes с тем же периодом.
Имеющиеся серии позволяют изучать heap used/committed/max, memory pools
(metaspace/code cache), GC count/time, threads, process uptime/CPU и HTTP timers.
Набор зависит от runtime и версии Micrometer. Отсутствующая серия не равна нулю.
Ошибка optional scrape записывается в collector.log, container metrics продолжаются.
Одинаковые scrapes сами создают небольшую нагрузку во всех вариантах, включая idle.

Сохраняются pre/post scrapes, run-info, GC logs и доступные jcmd snapshots.
Для OpenJ9 не вызывается HotSpot VM.flags, используется его исходный verbose GC.
Native не запускает jcmd и не обязан экспортировать JIT/metaspace/HotSpot метрики.
Табличное сравнение использует только общие container и k6 показатели;
runtime-specific ряды остаются отдельно.

NMT не включён в baseline. Дополнительный диагностический run только HotSpot/GraalVM JIT:

```bash
(
  set -a
  source profiles/work-hotspot-elastic.env
  set +a
  export JVM_PROFILE=elastic-nmt MEMORY_PROFILE=elastic-heap-nmt
  export JAVA_TOOL_OPTIONS="$JAVA_TOOL_OPTIONS -XX:NativeMemoryTracking=summary"
  SCENARIO=idle DURATION=3m bash scripts/run-experiment.sh profiles/work-hotspot-elastic.env
)
```

Для `jcmd VM.native_memory summary` нужен JDK-образ с jcmd (HotSpot baseline сейчас JRE).
Смену образа тоже фиксируйте как отдельный диагностический режим. Во время run найдите
контейнер по Compose project из metadata, выполните `docker exec CONTAINER jcmd 1
VM.native_memory summary` и сохраните вывод отдельно. NMT меняет накладные расходы;
не переносите его цифры в общую baseline-выборку и не применяйте флаг к OpenJ9/native.

## Startup, HTTP и metadata

Startup = Docker `State.StartedAt` → первый readiness HTTP UP. Build/pull исключены;
инициализация зависимостей, которую приложение ждёт, входит. Polling 250ms плюс HTTP/CLI
накладные расходы ограничивают точность, особенно для быстрого Native Image.

Сравнение HTTP сохраняет requests, failures, failure %, avg/median/p95/p99/p99.9/max.
`http_req_failed` — k6 Rate: true samples обозначают ошибки. Для legacy summary
failures вычисляются через rate × requests. HTTP requests включают seed-запрос.
`k6TimeSeries=true` позволяет анализировать хвосты/прогрев по окнам;
percentile всего run не показывает эволюцию latency.

Metadata содержит полный SHA, dirty marker, настройки workload, JVM flags, mode,
memory profile, образы, лимиты, sampling, startup, readiness и completion status.
`config` в сравнении — hash условий (включая runtime options/images), а не разрешение
объединить разные runtime в одну статистическую выборку. Все строки остаются отдельными.
Для финальных серий используйте чистый checkout; source.diff не включает untracked files.

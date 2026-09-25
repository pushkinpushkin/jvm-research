# Оставшиеся этапы

## P0 — допуск к пилоту

- На исследовательском хосте зафиксировать Docker/ядро, immutable образы зависимостей и runtime, фактический GC; проверить cgroup v2/memory.peak.

## P1 — HotSpot pilot и стабильность

- Pilot 10–30 минут: fresh idle, редкие запросы, low load; одинаковый пул 1000, drain и post-load idle.
- Оценить влияние sampling 5/15 секунд и variance; пересчитать несколько реальных окон, проверить phase coverage.
- Stability 1–3 часа: drift памяти, границы состояния, GC/JIT, баланс событий. По результату выбрать длительность baseline.
- Архивировать результаты вне краткого checkpoint; предусмотреть хранение дольше 7-дневных smoke artifacts CI.

## P2 — baseline и сравнение

- Признать корректным HotSpot baseline, минимум 3 повтора основных сценариев.
- Затем baseline OpenJ9 → GraalVM JIT → Native; fixed heap отдельно, faults отдельно.
- Сравнить память, CPU, latency, throughput, startup и стабильность по допущенным сопоставимым данным; подтвердить основные выводы повторными runs.

## Позже, только при наблюдаемом эффекте

- Адресный анализ native memory, GC/JIT/code cache и allocator.
- Assembly/JIT allocation benchmark Владимира Воскресенского; не расширять им текущий этап.

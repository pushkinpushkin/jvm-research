# Проверки реализации

## Подтверждённый CI результат — 2026-09-23

[Runtime smoke, run 35875576575](https://github.com/pushkinpushkin/jvm-research/actions/runs/35875576575),
код ветки `275b2a67881531a26fc5eaaca9a4495338843ba3`.
Все пять jobs завершились успешно: tests и четыре runtime smoke.

| Проверка | Результат |
|---|---|
| Java 21: `./gradlew --no-daemon test bootJar` | PASS, включая processAot/compileAotJava |
| Python orchestration/numerical tests | PASS, 8 тестов |
| HotSpot Docker build, idle 10s, load 1m RATE=1 | PASS; 61 HTTP requests, 0 failed |
| OpenJ9 Docker build, idle 10s, load 1m RATE=1 | PASS; 61 HTTP requests, 0 failed |
| GraalVM JIT Docker build, idle 10s, load 1m RATE=1 | PASS; 62 HTTP requests, 0 failed |
| GraalVM Native nativeCompile/Docker build, idle 10s, load 1m RATE=1 | PASS; 62 HTTP requests, 0 failed |
| Readiness, run-info, CSV, metadata, comparison для всех runtime | PASS |
| Внешний HTTP, публикация и обработка Kafka business events | PASS, проверены по application logs |

В load создавались 100 заявок; HTTP requests включают один seed-запрос.
Пограничное количество iterations (60/61) отражает запуск constant-arrival-rate k6;
это короткая функциональная проверка, не сопоставимая производительная серия.
В артефактах каждого job есть полные build/app/collector logs, CSV, metadata и scrapes
(срок хранения GitHub Actions artifacts — 7 дней).

Стандартная AOT-сборка и проверенные бизнес-пути прошли с добавленными binding hints
для RestClient DTO/Kafka BusinessEvent и без RuntimeMXBean inputArguments в native.
Дополнительных обходов или отключения функциональности для прохождения smoke не потребовалось.
Smoke не гарантирует покрытия всех редких AOT-путей, scheduler/error/serialization сценариев.

## Локальная среда разработки изменений

- Python orchestration/numerical tests: 8 тестов, проходят.
- Bash syntax / Python compilation / git diff whitespace: проходят.
- `./gradlew test`, `./gradlew nativeCompile`: попытки заблокированы загрузкой
  Gradle 8.14.3 (`Network is unreachable`). Доступна Java 17, проект требует Java 21.
- Docker builds и настоящие runtime smoke: локально недоступны, Docker не установлен.
- Локальные тесты с fake Docker/k6/curl проверяют сценарии/cleanup/формат результатов;
  они **не подтверждают** native build или Spring/Mongo/Kafka/WireMock совместимость.

## Полноценная проверка

Workflow `.github/workflows/runtime-smoke.yml` выполняет Java tests/bootJar,
затем Docker build и короткие idle/load smoke всех четырёх runtime.
Артефакты содержат build/app/collector logs, metadata, CSV и comparison.
Проверка enterprise flow требует в логах фактических внешних HTTP-запросов,
Kafka публикации и обработки business event.

Повторить локально при доступных Docker, Java 21 и k6:

```bash
./gradlew test
python3 -m unittest discover -s scripts/tests -v
for profile in work-hotspot-fixed work-openj9-fixed work-graalvm-fixed work-graalvm-native; do
  bash scripts/smoke-runtime.sh "profiles/${profile}.env" || break
done
```

Native build/run подтверждён приведённой CI-проверкой. При смене зависимостей, GraalVM
или добавлении динамических типов повторите build и integration smoke.

30-минутные измерения и минимум три повтора каждого выбранного набора условий выполняются
отдельно на контролируемом хосте. CI smoke не является результатом исследования производительности.

## Изменённые и добавленные файлы

- `.dockerignore`
- `.github/workflows/runtime-smoke.yml`
- `.gitignore`
- `Dockerfile.native`
- `README.md`
- `build.gradle.kts`
- `docs/experiment-plan.md`
- `docs/jvm-matrix.md`
- `docs/local-runbook.md`
- `docs/metrics.md`
- `docs/validation.md`
- `infra/docker-compose.yml`
- `load/k6/enterprise-flow.js`
- `profiles/work-graalvm-elastic.env`
- `profiles/work-graalvm-fixed.env`
- `profiles/work-graalvm-native.env`
- `profiles/work-hotspot-elastic.env`
- `profiles/work-hotspot-fixed.env`
- `profiles/work-openj9-elastic.env`
- `profiles/work-openj9-fixed.env`
- `scripts/collect-container-metrics.py`
- `scripts/collect-process-metrics.sh`
- `scripts/collect-runtime-metrics.sh`
- `scripts/compare-benchmark-root.sh`
- `scripts/compare-results.py`
- `scripts/experiment_support.py`
- `scripts/profile-common.sh`
- `scripts/run-enterprise-sandbox.sh`
- `scripts/run-experiment.sh`
- `scripts/smoke-runtime.sh`
- `scripts/tests/test_experiments.py`
- `src/main/java/dev/pushkin/jvmresearch/enterprise/EnterpriseSandboxApplication.java`
- `src/main/java/dev/pushkin/jvmresearch/enterprise/runinfo/RunInfoService.java`
- `src/main/resources/application.yml`

# Проверки реализации

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

В native build/run могут проявиться дополнительные reachability/библиотечные ограничения.
До успешной реальной проверки этот вариант нельзя считать подтверждённо совместимым.
В исходниках заранее обработаны JSON binding внешнего клиента/Kafka и обращение к
RuntimeMXBean для run-info, без отключения бизнес-компонентов.

30-минутные измерения и минимум три повтора каждого выбранного набора условий выполняются
отдельно на контролируемом хосте. CI smoke не является результатом исследования производительности.

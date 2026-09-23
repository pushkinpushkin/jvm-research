# Runtime matrix

| Variant | Режим | Runtime image | Профили нового этапа |
|---|---|---|---|
| hotspot-liberica | HotSpot / Liberica JIT | bellsoft/liberica-openjre-alpine:21.0.11-11 | work-hotspot-fixed / elastic |
| openj9 | Eclipse OpenJ9 / IBM Semeru JIT | ibm-semeru-runtimes:open-21.0.11.0-jdk-jammy | work-openj9-fixed / elastic |
| graalvm-jit | Oracle GraalVM JIT | container-registry.oracle.com/graalvm/jdk:21 | work-graalvm-fixed / elastic |
| graalvm-native | GraalVM Native Image AOT | oraclelinux:9-slim | work-graalvm-native |

Native Image — не четвёртая JVM. Это executable, собранный заранее из того же приложения.
Отсутствие JIT code cache, jcmd и части MXBeans у native — нормальное различие runtime.
Все новые профили используют 2 CPU / 1 GiB. Исторические baseline-файлы оставлены с 1 CPU.

## Native build

`org.graalvm.buildtools.native:0.10.6` включает стандартную задачу `nativeCompile`;
Spring Boot 3.4.4 подключает `processAot`. Reachability metadata repository включён.
Отдельный `Dockerfile.native` использует builder
`container-registry.oracle.com/graalvm/native-image:21-ol9` и совместимую glibc/OL9 runtime-базу.
Entrypoint запускает `/app/jvm-research`; JVM в финальном контейнере не требуется.
`--no-fallback` запрещает незаметную замену native результата JVM-запуском.
JMH остаётся прежним JVM-экспериментом.

Точечные binding hints добавлены для DTO ответов RestClient и Kafka BusinessEvent.
DTO внешнего HTTP-клиента не выводятся из сигнатур MVC-контроллеров; Kafka также выбирает
тип из configuration/type headers. MVC и MongoDB используют обычную Spring AOT обработку.
`RunInfoService` не запрашивает RuntimeMXBean inputArguments в native; там возвращается
пустой список. Бизнес-цепочка, listeners, scheduler'ы и интеграции не отключаются.
Native сборка и короткие idle/load integration smoke подтверждены в CI 2026-09-23
(см. [validation.md](validation.md)); это проверка перечисленных путей, не всех возможных входов.

## Memory profiles

- `fixed-heap`: Xms512m/Xmx512m на трёх JVM — текущая production-like гипотеза.
- `elastic-heap`: Xms32m/Xmx512m на трёх JVM — небольшой стартовый heap с тем же потолком.
  32 MiB — контролируемая экспериментальная настройка, не универсальная рекомендация.
- `native-default`: native heap ergonomics, контейнер ограничен 1 GiB. Это **не**
  утверждение об одинаковом Xmx=512m; сравниваем общий footprint при одинаковом внешнем лимите.

GC каждой JVM оставлен стандартным; исходные GC logs сохранены в JVM-профилях.
Не объединяйте результаты fixed/elastic/native-default. Экономия памяти оценивается вместе
с latency, ошибками и риском OOM. Kubernetes memory request сам по себе не задаёт Xms.

## Воспроизводимость

Language level остаётся Java 21. Образы имеют разные OS/libc; результат относится к
полному runtime-дистрибутиву, а не только алгоритму JIT/GC.
GraalVM `:21`, native builder и OL9 теги плавающие: перед окончательной серией фиксируйте
`RUNTIME_IMAGE` и `NATIVE_BUILDER_IMAGE` через `@sha256:...` для всех повторов.
Metadata сохраняет имена образов и ID собранного app image; build.log сохраняет разрешение
build inputs. Image ID приложения не заменяет фиксацию digest builder и base image.

Сборка и прогоны: [local-runbook.md](local-runbook.md).
Официальная документация:
- https://docs.spring.io/spring-boot/3.4/gradle-plugin/aot.html
- https://graalvm.github.io/native-build-tools/0.10.6/gradle-plugin.html
- https://www.graalvm.org/jdk21/getting-started/container-images/

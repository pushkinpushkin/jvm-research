# JVM matrix

## Зачем нужна матрица

В исследовании сравниваем не абстрактные JVM, а конкретные runtime-варианты, которые можно воспроизвести в публичном окружении:

1. generic HotSpot baseline;
2. альтернативная JVM с другим memory/runtime профилем;
3. GraalVM JIT как популярный эталон для сравнений.

## Выбранные варианты

| Variant | JVM type | Image | Role |
|---|---|---|---|
| `hotspot-generic` | HotSpot / Eclipse Temurin | `eclipse-temurin:21-jre` | generic HotSpot baseline |
| `openj9` | Eclipse OpenJ9 / IBM Semeru | `ibm-semeru-runtimes:open-21.0.11.0-jdk-jammy` | эталонный OpenJ9 runtime для сравнения memory footprint / warmup |
| `graalvm-jit` | Oracle GraalVM JDK | `container-registry.oracle.com/graalvm/jdk:21` | GraalVM в JVM/JIT режиме, без Native Image |

## Почему так

### HotSpot / Eclipse Temurin

Это публичный generic baseline для сравнения. Его задача не победить в бенчмарках, а дать стабильную точку отсчета для HotSpot на Java 21.

### OpenJ9 / IBM Semeru

Берём IBM Semeru, потому что это официальный распространённый способ использовать Eclipse OpenJ9 вместе с OpenJDK class libraries. Версию фиксируем на Java 21.0.11.0, чтобы не сравнивать плавающий latest.

Для первого этапа выбран `jdk-jammy`, потому что текущая песочница собирает Gradle-проект внутри контейнера. Когда появится отдельный application jar, можно добавить runtime-only вариант на JRE.

### GraalVM JDK

Берём GraalVM JDK 21, но именно JVM/JIT режим. Native Image — отдельный класс эксперимента, его нельзя честно сравнивать в одной строке с HotSpot/OpenJ9 JVM runtime.

Для Native Image позже можно сделать отдельную ветку исследования:

- build time;
- image size;
- startup;
- memory;
- ограничения reflection/proxy/resources;
- совместимость со Spring Boot.

## Почему не берём Java 25 как основной baseline

Java 25 уже актуален как новая линия, но предыдущий план исследования завязан на Java 21. Для прикладного вывода важнее сравнить разные JVM на одном LTS уровне, чем смешивать JVM runtime и переход на новую Java-версию.

Java 25 можно добавить позже как отдельную ось:

```text
HotSpot 21 vs HotSpot 25
OpenJ9 21 vs OpenJ9 25
GraalVM 21 vs GraalVM 25
```

Но это уже другое исследование: не только JVM implementation, но и Java version upgrade.

## Правила фиксации версий

1. В Dockerfile используем `ARG BASE_IMAGE`, чтобы можно было переопределить образ без редактирования файла.
2. Для воспроизводимых прогонов сохраняем `java -version` в `results/<variant>/java-version.txt`.
3. После первого успешного pull можно дополнительно зафиксировать digest образа.
4. В отчёте всегда указываем не только JVM, но и OS/base image: Alpine, Jammy, Noble и т.д.

## Команды сборки

```bash
docker build -f docker/hotspot/Dockerfile -t jvm-research:hotspot-generic .
docker build -f docker/openj9/Dockerfile -t jvm-research:openj9 .
docker build -f docker/graalvm/Dockerfile -t jvm-research:graalvm-jit .
```

Переопределить образ можно так:

```bash
docker build \
  -f docker/openj9/Dockerfile \
  --build-arg BASE_IMAGE=ibm-semeru-runtimes:open-21-jdk-jammy \
  -t jvm-research:openj9-floating-21 .
```

## Что сравнивать в отчёте

Минимальная таблица результата:

| Variant | Java version | JVM name | OS base | Startup | Warmup | Throughput | RSS | Heap | GC |
|---|---|---|---|---:|---:|---:|---:|---:|---:|
| hotspot-generic | TBD | TBD | Debian/Ubuntu based | TBD | TBD | TBD | TBD | TBD | TBD |
| openj9 | TBD | TBD | Ubuntu Jammy | TBD | TBD | TBD | TBD | TBD | TBD |
| graalvm-jit | TBD | TBD | Oracle Linux based | TBD | TBD | TBD | TBD | TBD | TBD |

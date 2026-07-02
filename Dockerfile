ARG BUILDER_IMAGE=gradle:8.14-jdk21
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre

FROM ${BUILDER_IMAGE} AS build

WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle --no-daemon clean bootJar

FROM ${RUNTIME_IMAGE}

WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]

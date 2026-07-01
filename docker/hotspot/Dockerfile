ARG BASE_IMAGE=docker-hub.binary.alfabank.ru/bellsoft/liberica-openjre-alpine:21.0.11-11
FROM ${BASE_IMAGE}

WORKDIR /workspace

COPY . .

RUN chmod +x scripts/*.sh || true

CMD ["./gradlew", "--no-daemon", "clean", "jmh"]

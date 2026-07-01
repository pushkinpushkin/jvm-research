ARG BASE_IMAGE=container-registry.oracle.com/graalvm/jdk:21
FROM ${BASE_IMAGE}

WORKDIR /workspace

COPY . .

RUN chmod +x scripts/*.sh || true

CMD ["./gradlew", "--no-daemon", "clean", "jmh"]

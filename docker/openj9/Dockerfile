ARG BASE_IMAGE=ibm-semeru-runtimes:open-21.0.11.0-jdk-jammy
FROM ${BASE_IMAGE}

WORKDIR /workspace

COPY . .

RUN chmod +x scripts/*.sh || true

CMD ["./gradlew", "--no-daemon", "clean", "jmh"]

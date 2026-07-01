FROM eclipse-temurin:21-jdk

WORKDIR /workspace

COPY . .

RUN chmod +x scripts/*.sh || true

CMD ["./gradlew", "--no-daemon", "clean", "jmh"]

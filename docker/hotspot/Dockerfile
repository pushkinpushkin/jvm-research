ARG BASE_IMAGE=docker-hub.binary.alfabank.ru/bellsoft/liberica-openjre-alpine:21.0.11-11
FROM ${BASE_IMAGE}

WORKDIR /opt/jvm-research

COPY build/install/jvm-research/ /opt/jvm-research/
COPY build/libs/*-jmh.jar /opt/jvm-research/jmh-benchmarks.jar

CMD ["/opt/jvm-research/bin/jvm-research"]

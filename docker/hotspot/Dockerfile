ARG BASE_IMAGE=eclipse-temurin:21-jre
FROM ${BASE_IMAGE}

WORKDIR /opt/jvm-research

COPY build/install/jvm-research/ /opt/jvm-research/
COPY build/libs/*-jmh.jar /opt/jvm-research/jmh-benchmarks.jar

CMD ["/opt/jvm-research/bin/jvm-research"]

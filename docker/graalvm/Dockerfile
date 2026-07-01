ARG BASE_IMAGE=container-registry.oracle.com/graalvm/jdk:21
FROM ${BASE_IMAGE}

WORKDIR /opt/jvm-research

COPY build/install/jvm-research/ /opt/jvm-research/
COPY build/libs/*-jmh.jar /opt/jvm-research/jmh-benchmarks.jar

CMD ["/opt/jvm-research/bin/jvm-research"]

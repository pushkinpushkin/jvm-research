ARG BASE_IMAGE=ibm-semeru-runtimes:open-21.0.11.0-jdk-jammy
FROM ${BASE_IMAGE}

WORKDIR /opt/jvm-research

COPY build/install/jvm-research/ /opt/jvm-research/
COPY build/libs/*-jmh.jar /opt/jvm-research/jmh-benchmarks.jar

CMD ["/opt/jvm-research/bin/jvm-research"]

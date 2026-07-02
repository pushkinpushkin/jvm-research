#!/usr/bin/env bash
set -euo pipefail

variant="${1:-hotspot-liberica}"

case "${variant}" in
  hotspot|hotspot-liberica)
    export JVM_VARIANT="hotspot-liberica"
    export RUNTIME_IMAGE="bellsoft/liberica-openjre-alpine:21.0.11-11"
    ;;
  openj9)
    export JVM_VARIANT="openj9"
    export RUNTIME_IMAGE="ibm-semeru-runtimes:open-21.0.11.0-jdk-jammy"
    ;;
  graalvm|graalvm-jit)
    export JVM_VARIANT="graalvm-jit"
    export RUNTIME_IMAGE="container-registry.oracle.com/graalvm/jdk:21"
    ;;
  *)
    echo "Unknown JVM variant: ${variant}" >&2
    echo "Usage: $0 [hotspot-liberica|openj9|graalvm-jit]" >&2
    exit 1
    ;;
esac

cd "$(dirname "$0")/../infra"
docker compose up --build

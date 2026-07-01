#!/usr/bin/env bash
set -euo pipefail

variant="${1:-hotspot}"

case "${variant}" in
  hotspot)
    export JVM_VARIANT="hotspot"
    export RUNTIME_IMAGE="eclipse-temurin:21-jre"
    ;;
  openj9)
    export JVM_VARIANT="openj9"
    export RUNTIME_IMAGE="ibm-semeru-runtimes:open-21-jre"
    ;;
  graalvm)
    export JVM_VARIANT="graalvm"
    export RUNTIME_IMAGE="ghcr.io/graalvm/jdk-community:21"
    ;;
  *)
    echo "Unknown JVM variant: ${variant}" >&2
    echo "Usage: $0 [hotspot|openj9|graalvm]" >&2
    exit 1
    ;;
esac

cd "$(dirname "$0")/../infra"
docker compose up --build

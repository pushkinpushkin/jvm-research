#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

out_dir="${1:-results/host}"
mkdir -p "$out_dir"

capture() {
  local name="$1"
  shift
  if "$@" > "${out_dir}/${name}" 2>&1; then
    return 0
  fi
  printf 'command failed: %q' "$1" > "${out_dir}/${name}"
  shift
  for arg in "$@"; do
    printf ' %q' "$arg" >> "${out_dir}/${name}"
  done
  printf '\n' >> "${out_dir}/${name}"
  return 1
}

{
  date --iso-8601=seconds
  hostname
} > "${out_dir}/identity.txt"

capture uname.txt uname -a || true
capture lscpu.txt lscpu || true
capture free.txt free -h || true
capture df.txt df -h || true
capture swap.txt swapon --show || true
capture docker-version.txt docker version || true
capture docker-info.txt docker info || true
capture docker-compose-version.txt docker compose version || true
capture k6-version.txt k6 version || true
capture python-version.txt python3 --version || true
capture git-version.txt git --version || true

if command -v docker >/dev/null 2>&1 && docker version >/dev/null 2>&1; then
  docker run --rm alpine sh -c 'test -f /sys/fs/cgroup/memory.peak && cat /sys/fs/cgroup/memory.peak' \
    > "${out_dir}/memory-peak.txt" 2>&1 || true
fi

echo "Host snapshot saved to ${out_dir}"

#!/usr/bin/env bash
set -euo pipefail

failures=0
warnings=0

fail() {
  failures=$((failures + 1))
  printf 'FAIL  %s\n' "$*"
}

warn() {
  warnings=$((warnings + 1))
  printf 'WARN  %s\n' "$*"
}

pass() {
  printf 'OK    %s\n' "$*"
}

info() {
  printf 'INFO  %s\n' "$*"
}

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

bytes_to_gib() {
  awk -v bytes="$1" 'BEGIN { printf "%.1f", bytes / 1024 / 1024 / 1024 }'
}

disk_available_bytes() {
  df -PB1 . | awk 'NR == 2 { print $4 }'
}

echo "VPS readiness preflight"
echo

if [[ "$(uname -s)" == "Linux" ]]; then
  pass "Linux host: $(uname -srmo)"
else
  fail "Host must be Linux; got $(uname -s)."
fi

arch="$(uname -m)"
if [[ "$arch" == "x86_64" ]]; then
  pass "Architecture is x86_64."
else
  fail "Architecture must be x86_64; got $arch."
fi

cpu_count="$(getconf _NPROCESSORS_ONLN 2>/dev/null || nproc 2>/dev/null || echo 0)"
if (( cpu_count >= 8 )); then
  pass "CPU count: ${cpu_count} vCPU."
elif (( cpu_count >= 4 )); then
  warn "CPU count: ${cpu_count} vCPU; acceptable for JVM pilots, below preferred 8 vCPU."
else
  fail "CPU count: ${cpu_count} vCPU; expected at least 4, preferred 8."
fi

mem_bytes="$(awk '/MemTotal:/ { print $2 * 1024 }' /proc/meminfo 2>/dev/null || echo 0)"
mem_gib="$(bytes_to_gib "$mem_bytes")"
if awk -v mem="$mem_bytes" 'BEGIN { exit !(mem >= 16 * 1024 * 1024 * 1024) }'; then
  pass "Memory: ${mem_gib} GiB."
elif awk -v mem="$mem_bytes" 'BEGIN { exit !(mem >= 8 * 1024 * 1024 * 1024) }'; then
  warn "Memory: ${mem_gib} GiB; acceptable for JVM pilots, Native Image builds may be constrained."
else
  fail "Memory: ${mem_gib} GiB; expected at least 8 GiB, preferred 16 GiB."
fi

disk_bytes="$(disk_available_bytes)"
disk_gib="$(bytes_to_gib "$disk_bytes")"
if awk -v disk="$disk_bytes" 'BEGIN { exit !(disk >= 50 * 1024 * 1024 * 1024) }'; then
  pass "Available disk in repository filesystem: ${disk_gib} GiB."
else
  warn "Available disk in repository filesystem: ${disk_gib} GiB; preferred 50-80 GiB SSD."
fi

if swapon --show 2>/dev/null | awk 'NR > 1 { found = 1 } END { exit !found }'; then
  warn "Swap is enabled. The app container still uses no swap, but host pressure can distort experiments."
else
  pass "No active swap reported by swapon."
fi

for tool in git python3 docker; do
  if command_exists "$tool"; then
    pass "$tool: $("$tool" --version 2>&1 | head -n 1)"
  else
    fail "Missing required tool: $tool."
  fi
done

if command_exists k6; then
  pass "k6: $(k6 version 2>&1 | head -n 1)"
else
  fail "Missing required tool: k6."
fi

if command_exists docker; then
  if docker version >/dev/null 2>&1; then
    pass "Docker daemon is reachable."
    docker_server_version="$(docker info --format '{{.ServerVersion}}' 2>/dev/null || true)"
    docker_cgroup_version="$(docker info --format '{{.CgroupVersion}}' 2>/dev/null || true)"
    docker_cpus="$(docker info --format '{{.NCPU}}' 2>/dev/null || true)"
    docker_mem="$(docker info --format '{{.MemTotal}}' 2>/dev/null || true)"
    [[ -n "$docker_server_version" ]] && info "Docker Server Version: $docker_server_version"
    [[ -n "$docker_cpus" ]] && info "Docker CPUs: $docker_cpus"
    [[ -n "$docker_mem" ]] && info "Docker Total Memory: $(bytes_to_gib "$docker_mem") GiB"
    if [[ "$docker_cgroup_version" == "2" ]]; then
      pass "Docker Cgroup Version: 2."
    else
      fail "Docker Cgroup Version must be 2; got ${docker_cgroup_version:-unknown}."
    fi
  else
    fail "Docker daemon is not reachable by the current user."
  fi

  if docker compose version >/dev/null 2>&1; then
    pass "Docker Compose: $(docker compose version 2>&1 | head -n 1)"
  else
    fail "Docker Compose v2 plugin is missing or unavailable."
  fi
fi

if command_exists docker && docker version >/dev/null 2>&1; then
  if docker run --rm alpine sh -c 'test -f /sys/fs/cgroup/memory.peak && cat /sys/fs/cgroup/memory.peak >/dev/null' >/dev/null 2>&1; then
    pass "Container cgroup exposes memory.peak."
  else
    fail "Container cgroup does not expose /sys/fs/cgroup/memory.peak."
  fi
fi

echo
if (( failures == 0 )); then
  if (( warnings == 0 )); then
    echo "Result: ready for HotSpot pilot prechecks."
  else
    echo "Result: usable with ${warnings} warning(s); review before baseline."
  fi
else
  echo "Result: not ready; ${failures} failure(s), ${warnings} warning(s)."
fi

exit "$failures"

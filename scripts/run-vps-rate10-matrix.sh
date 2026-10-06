#!/usr/bin/env bash
# bash run-vps-rate10-matrix.sh SERIES_ID [REPO] [--retry-failed]
# bash run-vps-rate10-matrix.sh --dry-run
# Fixed protocol: 3 passes x 4 runtimes; 1h load + drain + 1h idle.
set -Eeuo pipefail
plan=(
  'pass1 1 work-hotspot-elastic'
  'pass1 2 work-openj9-elastic'
  'pass1 3 work-graalvm-elastic'
  'pass1 4 work-graalvm-native'
  'pass2 1 work-graalvm-native'
  'pass2 2 work-graalvm-elastic'
  'pass2 3 work-openj9-elastic'
  'pass2 4 work-hotspot-elastic'
  'pass3 1 work-openj9-elastic'
  'pass3 2 work-hotspot-elastic'
  'pass3 3 work-graalvm-native'
  'pass3 4 work-graalvm-elastic'
)
if [[ ${1:-} == --dry-run ]]; then
  printf '%s\n' 'Each run: RATE=10/1s DURATION=1h POST_IDLE=3600s DRAIN_TIMEOUT=300s' "${plan[@]}"
  exit 0
fi
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
series=${1:-}
[[ $series =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]*$ ]] || die 'Supply a SERIES_ID, e.g. 20261006-rate10-1h-v1'
repo=${2:-$(cd "$(dirname "$0")/.." && pwd -P)}
retry=${3:-}
[[ -z $retry || $retry == --retry-failed ]] || die 'Unknown option; use --retry-failed to archive and retry an incomplete run.'
# Clear inherited experiment/JVM overrides, retaining only the local tool environment.
if [[ ${JVM_MATRIX_CLEAN_ENV:-} != 1 ]]; then
  exec env -i PATH="$PATH" HOME="$HOME" USER="${USER:-root}" LANG=C.UTF-8 \
    TERM="${TERM:-xterm}" JVM_MATRIX_CLEAN_ENV=1 \
    bash "$(realpath "$0")" "$series" "$repo" "$retry"
fi
script_path=$(realpath "$0")
cd "$repo"
repo=$(pwd -P)
for tool in docker git python3 k6 curl flock ss pgrep sha256sum tee; do
  command -v "$tool" >/dev/null || die "Missing tool: $tool"
done
[[ $(uname -s) == Linux ]] || die 'Linux VPS required.'
mkdir -p results/vps-rate10-1h
exec 9>results/vps-rate10-1h/matrix.lock
flock -n 9 || die 'Another matrix wrapper is already active.'
root="$repo/results/vps-rate10-1h/$series"
mkdir -p "$root/control"
exec > >(tee -a "$root/matrix.log") 2>&1
trap 'printf "Stopped at line %s. Results preserved in %s\n" "$LINENO" "$root" >&2' ERR

idle_check() {
  local active ports
  active=$(pgrep -af '[r]un-experiment.sh|[r]un-v1-idle-rare-matrix.sh|[r]un-local-10m-matrix.sh|[k]6 run' || true)
  [[ -z $active ]] || { printf '%s\n' "$active"; die 'Another experiment is active. Do not run in parallel.'; }
  [[ -z $(docker ps -q) ]] || { docker ps; die 'Running containers detected on the research VPS.'; }
  ports=$(ss -H -ltn '( sport = :8080 or sport = :8089 or sport = :27017 or sport = :9092 )')
  [[ -z $ports ]] || { printf '%s\n' "$ports"; die 'Required ports are occupied.'; }
}
fingerprint() {
  git diff --quiet && git diff --cached --quiet || die 'Tracked source files have local modifications.'
  # Include untracked build/source inputs to detect changes on resume.
  python3 - "$script_path" <<'PY'
import hashlib, pathlib, subprocess, sys
paths=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z']).split(b'\0')
h=hashlib.sha256()
for name in sorted(set(p for p in paths if p)):
    p=pathlib.Path(name.decode())
    h.update(name+b'\0'); h.update(p.read_bytes()); h.update(b'\0')
print('source_sha256='+h.hexdigest())
print('wrapper_sha256='+hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
  git rev-parse HEAD
  hostname
  uname -rmo
  docker version --format '{{.Server.Version}}'
  docker compose version
  k6 version
  python3 --version
}
docker info >/dev/null
idle_check
fingerprint > "$root/control/current-fingerprint.txt"
if [[ -f $root/control/fingerprint.txt ]]; then
  cmp "$root/control/fingerprint.txt" "$root/control/current-fingerprint.txt" || die 'Source, wrapper or environment changed. Use the original environment or a new SERIES_ID.'
else
  cp "$root/control/current-fingerprint.txt" "$root/control/fingerprint.txt"
  cp "$script_path" "$root/control/launcher.sh"
  printf '%s\n' "${plan[@]}" > "$root/control/plan.txt"
fi
bash scripts/vps-preflight.sh
profiles=(work-hotspot-elastic work-openj9-elastic work-graalvm-elastic work-graalvm-native)
if [[ ! -f $root/control/prepared.ok ]]; then
  # Preparation may resume, but never refresh images after measurements have begun.
  [[ -z $(find "$root" -name metadata.json -print -quit) ]] || die 'Missing preparation checkpoint with existing measurements.'
  bash scripts/vps-capture-host.sh "$root/control/host"
  docker compose -f infra/docker-compose.yml pull mongo kafka wiremock
  builder=$(sed -n 's/^ARG BUILDER_IMAGE=//p' Dockerfile)
  [[ -n $builder ]] || die 'Missing builder image in Dockerfile.'
  docker pull "$builder"
  printf '%s\n' mongo:7.0 apache/kafka:3.7.1 wiremock/wiremock:3.9.1 "$builder" > "$root/control/image-refs.txt"
  for profile in "${profiles[@]}"; do
    (
      set -a
      source "profiles/$profile.env"
      set +a
      printf '\nPreparing %s\n' "$profile"
      docker pull "$RUNTIME_IMAGE"
      printf '%s\n' "$RUNTIME_IMAGE" >> "$root/control/image-refs.txt"
      if [[ ${RUNTIME_MODE:-jit} == native ]]; then
        docker pull "$NATIVE_BUILDER_IMAGE"
        printf '%s\n' "$NATIVE_BUILDER_IMAGE" >> "$root/control/image-refs.txt"
      fi
      export COMPOSE_PROJECT_NAME=jvm-matrix-prebuild
      export RUN_RESULTS_DIR="$root/control/build-context"
      docker compose -f infra/docker-compose.yml build sandbox-service
    )
  done
  sort -u "$root/control/image-refs.txt" -o "$root/control/image-refs.txt"
  mapfile -t refs < "$root/control/image-refs.txt"
  docker image inspect "${refs[@]}" > "$root/control/images.json"
  touch "$root/control/prepared.ok"
fi
mapfile -t refs < "$root/control/image-refs.txt"
check_images() {
  docker image inspect "${refs[@]}" > "$root/control/current-images.json"
  python3 - "$root/control/images.json" "$root/control/current-images.json" <<'PY'
import json,sys
a,b=(json.load(open(p)) for p in sys.argv[1:])
if [x['Id'] for x in a] != [x['Id'] for x in b]:
    raise SystemExit('Base/dependency images changed. Restore original images or start a new series.')
PY
}
eligible() {
  python3 - "$1/validation.json" <<'PY'
import json,sys
try:
    ok=json.load(open(sys.argv[1])).get('eligible') is True
except (OSError,ValueError):
    ok=False
sys.exit(0 if ok else 1)
PY
}
printf '\nSERIES: %s\nResults: %s\n' "$series" "$root"
for entry in "${plan[@]}"; do
  read -r pass index profile <<< "$entry"
  id="$series-$pass-$index-$profile"
  run_root="$root/$pass"
  run_dir="$run_root/$id"
  marker="$root/control/$id.exit"
  if [[ -f $marker ]] && [[ $(cat "$marker") == 0 ]] && eligible "$run_dir"; then
    printf 'SKIP verified completed run: %s\n' "$id"
    continue
  fi
  if [[ -e $run_dir ]]; then
    [[ $retry == --retry-failed ]] || die "Incomplete/failed run: $run_dir. Inspect its logs, then resume with --retry-failed."
    archive="$root/_attempts/$id-$(date -u +%Y%m%dT%H%M%SZ)-$$"
    mkdir -p "$archive"
    mv "$run_dir" "$archive/"
    if [[ -f $marker ]]; then mv "$marker" "$archive/exit-code.txt"; fi
    printf 'Previous attempt preserved: %s\n' "$archive"
  fi
  idle_check
  fingerprint > "$root/control/current-fingerprint.txt"
  cmp "$root/control/fingerprint.txt" "$root/control/current-fingerprint.txt" || die 'Environment/source changed during series.'
  check_images
  printf '%s\n' "$run_dir" > "$root/current-run.txt"
  printf '\nSTART %s at %s\n' "$id" "$(date -u --iso-8601=seconds)"
  code=0
  env SCENARIO=load RATE=10 RATE_TIME_UNIT=1s DURATION=1h \
    ORDER_POOL=1000 SEED_ORDERS=1000 TRAFFIC_PROFILE=normal TRACE_SEED=20260925 \
    PREALLOCATED_VUS=50 MAX_VUS=50 INTERVAL_SECONDS=5 \
    DRAIN_TIMEOUT_SECONDS=300 POST_IDLE_SECONDS=3600 \
    K6_TIME_SERIES=true COLLECT_PROMETHEUS=true SYNTHETIC_WARMUP=false \
    RUN_ID="$id" RESULTS_ROOT="$run_root" \
    bash scripts/run-experiment.sh "profiles/$profile.env" || code=$?
  printf '%s\n' "$code" > "$marker"
  [[ $code == 0 ]] || die "Runner exit=$code for $id. Series stopped; inspect validation.json and logs."
  eligible "$run_dir" || die "Run is not eligible: $id. Series stopped."
  printf 'PASS %s\n' "$id"
done
printf '\nCOMPLETE: 12/12 successful runs. Results: %s\n' "$root"
date -u --iso-8601=seconds > "$root/completed.txt"

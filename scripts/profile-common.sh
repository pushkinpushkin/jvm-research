#!/usr/bin/env bash
# Source from a strict-mode caller. Exported command-line overrides win over profile defaults.
load_experiment_profile() {
  local file="$1" key value i
  local keys=() values=()
  while IFS= read -r key; do
    if value="$(printenv "$key")"; then
      keys+=("$key")
      values+=("$value")
    fi
  done < <(sed -nE 's/^([A-Z_][A-Z_0-9]*)=.*/\1/p' "$file")
  set -a
  # shellcheck disable=SC1090
  source "$file"
  set +a
  for ((i=0; i<${#keys[@]}; i++)); do
    export "${keys[$i]}=${values[$i]}"
  done
}

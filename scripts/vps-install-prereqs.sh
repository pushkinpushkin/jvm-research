#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/vps-install-prereqs.sh

Installs the host tools expected by the research runner on Debian/Ubuntu:
Docker Engine, Docker Compose v2 plugin, k6, git, curl, and python3.

Run on a fresh VPS with sudo/root. Re-run is intended to be safe.
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "This setup script is intended for Linux VPS hosts." >&2
  exit 1
fi

arch="$(uname -m)"
if [[ "$arch" != "x86_64" ]]; then
  echo "Unsupported architecture: $arch; expected x86_64." >&2
  exit 1
fi

if [[ -r /etc/os-release ]]; then
  # shellcheck disable=SC1091
  source /etc/os-release
else
  echo "Cannot read /etc/os-release." >&2
  exit 1
fi

case "${ID:-}" in
  ubuntu|debian) ;;
  *)
    echo "Unsupported distribution: ${PRETTY_NAME:-unknown}. This script supports Debian/Ubuntu only." >&2
    exit 1
    ;;
esac

sudo_cmd=()
if [[ "${EUID:-$(id -u)}" -ne 0 ]]; then
  command -v sudo >/dev/null || { echo "sudo is required when not running as root." >&2; exit 1; }
  sudo_cmd=(sudo)
fi

codename="${VERSION_CODENAME:-}"
if [[ -z "$codename" && -r /etc/lsb-release ]]; then
  # shellcheck disable=SC1091
  source /etc/lsb-release
  codename="${DISTRIB_CODENAME:-}"
fi
[[ -n "$codename" ]] || { echo "Cannot determine distro codename." >&2; exit 1; }

"${sudo_cmd[@]}" apt-get update
"${sudo_cmd[@]}" apt-get install -y ca-certificates curl gnupg git python3

"${sudo_cmd[@]}" install -m 0755 -d /etc/apt/keyrings
if [[ ! -s /etc/apt/keyrings/docker.asc ]]; then
  curl -fsSL "https://download.docker.com/linux/${ID}/gpg" | "${sudo_cmd[@]}" tee /etc/apt/keyrings/docker.asc >/dev/null
  "${sudo_cmd[@]}" chmod a+r /etc/apt/keyrings/docker.asc
fi

docker_source="/etc/apt/sources.list.d/docker.list"
docker_repo="deb [arch=amd64 signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/${ID} ${codename} stable"
if [[ ! -f "$docker_source" ]] || ! grep -qxF "$docker_repo" "$docker_source"; then
  echo "$docker_repo" | "${sudo_cmd[@]}" tee "$docker_source" >/dev/null
fi

if [[ ! -s /etc/apt/keyrings/grafana.gpg ]]; then
  curl -fsSL https://apt.grafana.com/gpg.key | "${sudo_cmd[@]}" gpg --dearmor -o /etc/apt/keyrings/grafana.gpg
  "${sudo_cmd[@]}" chmod a+r /etc/apt/keyrings/grafana.gpg
fi

grafana_source="/etc/apt/sources.list.d/grafana.list"
grafana_repo="deb [signed-by=/etc/apt/keyrings/grafana.gpg] https://apt.grafana.com stable main"
if [[ ! -f "$grafana_source" ]] || ! grep -qxF "$grafana_repo" "$grafana_source"; then
  echo "$grafana_repo" | "${sudo_cmd[@]}" tee "$grafana_source" >/dev/null
fi

"${sudo_cmd[@]}" apt-get update
"${sudo_cmd[@]}" apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin k6
"${sudo_cmd[@]}" systemctl enable --now docker

target_user="${SUDO_USER:-}"
if [[ -n "$target_user" && "$target_user" != root ]]; then
  "${sudo_cmd[@]}" usermod -aG docker "$target_user"
  echo "Added $target_user to the docker group. Log out and back in before running Docker without sudo."
fi

echo "Prerequisites installed. Run scripts/vps-preflight.sh next."

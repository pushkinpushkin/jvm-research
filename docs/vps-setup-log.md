# VPS setup log

## 2026-09-26 — Selectel Ubuntu resolute

Context: first setup attempt on `root@jvm-research-selectel` after adding repository VPS scripts.

Observed:

- `bash scripts/vps-install-prereqs.sh` installed/confirmed base apt packages, then failed while fetching an external apt repository key:
  - `curl: (22) The requested URL returned error: 403`
  - `gpg: no valid OpenPGP data found.`
- Manual fallback `apt install -y k6` failed:
  - `Unable to locate package k6`

Current conclusion: `scripts/vps-install-prereqs.sh` is not robust enough for this VPS image. It assumes external Docker/Grafana apt repositories are directly reachable and compatible with the distro codename. Ubuntu `resolute` on Selectel needs an explicit fallback path for Docker/Compose and k6 installation.

Follow-up: update the setup script to detect unsupported or unreachable external apt repositories and either use Ubuntu packages for Docker/Compose or stop with clear manual commands. k6 must not be assumed to exist in the default Ubuntu repositories.

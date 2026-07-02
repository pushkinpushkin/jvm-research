#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
RATE="${RATE:-20}"
DURATION="${DURATION:-5m}"
ORDER_POOL="${ORDER_POOL:-10000}"
SEED_ORDERS="${SEED_ORDERS:-10000}"

export BASE_URL RATE DURATION ORDER_POOL SEED_ORDERS
k6 run load/k6/enterprise-flow.js

#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
profile="${1:-profiles/work-hotspot-fixed.env}"
root="${RESULTS_ROOT:-results/verification/$(date -u +%Y%m%dT%H%M%SZ)-$$}"
SCENARIO=idle DURATION=10s RESULTS_ROOT="$root/idle" bash scripts/run-experiment.sh "$profile"
SCENARIO=load RATE=1 DURATION=1m ORDER_POOL=100 SEED_ORDERS=100 RESULTS_ROOT="$root/load" \
  bash scripts/run-experiment.sh "$profile"
python3 - "$root" <<'PY'
import csv, json, sys
from pathlib import Path
root = Path(sys.argv[1])
for metadata in root.rglob('metadata.json'):
    run = metadata.parent
    meta = json.loads(metadata.read_text())
    assert meta['status'] == 'completed', meta
    with (run / 'runtime-metrics.csv').open() as f:
        samples = list(csv.DictReader(f))
    assert samples and all(float(x['memory_used_bytes']) > 0 for x in samples)
    assert meta['startupSeconds'] > 0
    if meta['scenario'] == 'load':
        data = json.loads((run / 'k6-summary.json').read_text())
        assert data['metrics']['http_reqs'].get('count', 0) > 1
        log = (run / 'app.log').read_text()
        for marker in ['External FNS request finished', 'Kafka event published', 'Business event occurred']:
            assert marker in log, f'Missing integration evidence: {marker} in {run}'
print('Runtime smoke artifacts and integration evidence verified')
PY
bash scripts/compare-benchmark-root.sh "$root"

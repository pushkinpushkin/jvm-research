#!/usr/bin/env python3
"""Per-run comparison: never pool percentiles or incompatible experiment configurations."""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import statistics
import sys


def percentile(values, p):
    values = sorted(values)
    if not values:
        return None
    pos = (len(values) - 1) * p
    lo = math.floor(pos)
    hi = math.ceil(pos)
    return values[lo] + (values[hi] - values[lo]) * (pos - lo)


def metric(summary, name):
    item = summary.get('metrics', {}).get(name, {})
    return item.get('values', item)


def read_json(path):
    return json.loads(path.read_text()) if path.exists() else {}


def summarize(run):
    meta = read_json(run / 'metadata.json')
    summary = read_json(run / 'k6-summary.json')
    load = meta.get('load', {})
    container = meta.get('container', {})
    # Exact profile/options retained in the group key: changing flags cannot silently pool samples.
    settings = {key: meta.get(key) for key in ['schemaVersion', 'scenario', 'memoryProfile', 'runtimeMode',
                'gitSha', 'gitDirty', 'samplingIntervalSeconds', 'syntheticWarmup', 'prometheusSampling',
                'javaToolOptions', 'runtimeImage', 'nativeBuilderImage']}
    settings.update(load=load, container=container)
    group = hashlib.sha256(json.dumps(settings, sort_keys=True).encode()).hexdigest()[:10]
    row = {'run': run.name, 'runtime': meta.get('jvmVariant', 'unknown'),
           'status': meta.get('status', 'legacy/unknown'),
           'scenario': meta.get('scenario', load.get('scenario', 'unknown')),
           'memory_profile': meta.get('memoryProfile', 'legacy/unknown'),
           'cpu': container.get('cpuLimit'), 'limit': container.get('memoryLimit'),
           'rate': load.get('rate'), 'duration': load.get('duration'), 'config': group,
           'startup_s': meta.get('startupSeconds')}
    requests = metric(summary, 'http_reqs').get('count')
    failed = metric(summary, 'http_req_failed')
    rate = failed.get('rate', failed.get('value'))
    # In a k6 Rate, 'passes' counts true samples: for http_req_failed those are errors.
    # 'fails' counts false samples (successful HTTP requests), so never use it here.
    failures = round(rate * requests) if rate is not None and requests is not None else failed.get('passes')
    row.update(requests=requests, failures=failures, failure_pct=rate * 100 if rate is not None else None)
    latency = metric(summary, 'http_req_duration')
    for source, key in [('avg', 'avg_ms'), ('med', 'med_ms'), ('p(95)', 'p95_ms'),
                        ('p(99)', 'p99_ms'), ('p(99.9)', 'p99_9_ms'), ('max', 'max_ms')]:
        row[key] = latency.get(source)
    samples = []
    path = run / 'runtime-metrics.csv'
    if path.exists():
        with path.open() as handle:
            for item in csv.DictReader(handle):
                if item.get('memory_used_bytes') and item.get('elapsed_seconds'):
                    samples.append((float(item['elapsed_seconds']), float(item['memory_used_bytes']) / 1024**2))
    values = [value for _, value in samples]
    row.update(samples=len(samples), memory_avg_mib=statistics.mean(values) if values else None,
               memory_med_mib=statistics.median(values) if values else None,
               memory_p95_mib=percentile(values, .95), memory_peak_mib=max(values) if values else None,
               initial_mib=samples[0][1] if samples else None)
    interval = float(meta.get('samplingIntervalSeconds', 5))
    for minute in (5, 10, 20, 30):
        target = minute * 60
        value = None
        if samples and max(t for t, _ in samples) >= target:
            closest = min(samples, key=lambda sample: abs(sample[0] - target))
            if abs(closest[0] - target) <= max(2 * interval, 5):
                value = closest[1]
        row[f'memory_{minute}m_mib'] = value
    return row


def display(value):
    if value is None:
        return 'n/a'
    return f'{value:.2f}' if isinstance(value, float) else str(value)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--csv', action='store_true', help='Print one CSV row per run')
    args = parser.parse_args()
    runs = sorted({p.parent for name in ['metadata.json', 'k6-summary.json'] for p in args.root.rglob(name)})
    if not runs:
        parser.error(f'No run metadata or k6 summaries under {args.root}')
    rows = [summarize(run) for run in runs]
    if args.csv:
        writer = csv.DictWriter(sys.stdout, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    else:
        print('Per-run results; no averaging across runs. Memory = Docker working-set estimate, MiB.')
        print('Missing values are n/a. Failed/legacy runs must be reviewed before drawing conclusions.')
        for title, columns in [
            ('Configuration', ['run', 'runtime', 'status', 'scenario', 'memory_profile', 'cpu', 'limit', 'rate', 'duration', 'config']),
            ('HTTP', ['run', 'requests', 'failures', 'failure_pct', 'avg_ms', 'med_ms', 'p95_ms', 'p99_ms', 'p99_9_ms', 'max_ms']),
            ('Memory', ['run', 'startup_s', 'samples', 'initial_mib', 'memory_avg_mib', 'memory_med_mib',
                        'memory_p95_mib', 'memory_peak_mib', 'memory_5m_mib', 'memory_10m_mib', 'memory_20m_mib', 'memory_30m_mib'])]:
            print('\n' + title)
            print('\t'.join(columns))
            for row in rows:
                print('\t'.join(display(row.get(key)) for key in columns))


if __name__ == '__main__':
    main()

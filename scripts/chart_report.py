#!/usr/bin/env python3
"""Create a self-contained, offline report from existing experiment artifacts."""
import csv
from datetime import datetime
import json
import math
from pathlib import Path


def finite_number(value):
    try:
        number = float(value)
        return round(number, 3) if math.isfinite(number) else None
    except (ValueError, TypeError):
        return None


def memory_points(run):
    path = run / 'runtime-metrics.csv'
    if not path.exists():
        return []
    result = []
    with path.open(newline='') as handle:
        for row in csv.DictReader(handle):
            t = finite_number(row.get('elapsed_seconds'))
            m = finite_number(row.get('memory_used_bytes'))
            if t is None or m is None or t < 0:
                continue
            result.append({'t': t, 'm': round(m / 1048576, 3),
                           'c': finite_number(row.get('cpu_percent')),
                           'phase': row.get('phase') or 'observation'})
    return result


def percentile(values, p):
    values = sorted(values)
    index = (len(values) - 1) * p
    lo, hi = math.floor(index), math.ceil(index)
    return values[lo] + (values[hi] - values[lo]) * (index - lo)


def latency_windows(run):
    """Per-minute p95 from optional k6 JSON line stream. No interpolation across gaps."""
    path = run / 'k6-timeseries.json'
    if not path.exists():
        return []
    first = None
    buckets = {}
    with path.open() as handle:
        for line in handle:
            try:
                entry = json.loads(line)
                if entry.get('type') != 'Point' or entry.get('metric') != 'http_req_duration':
                    continue
                data = entry['data']
                value = finite_number(data.get('value'))
                if value is None:
                    continue
                timestamp = datetime.fromisoformat(data['time'].replace('Z', '+00:00')).timestamp()
            except (KeyError, TypeError, ValueError, json.JSONDecodeError):
                continue
            if first is None:
                first = timestamp
            minute = max(0, int((timestamp - first) // 60))
            buckets.setdefault(minute, []).append(value)
    return [{'t': minute + .5, 'v': round(percentile(values, .95), 2), 'n': len(values)}
            for minute, values in sorted(buckets.items())]


def group_key(meta):
    load = meta.get('load') or {}
    container = meta.get('container') or {}
    return {key: value for key, value in {
        'scenario': meta.get('scenario', load.get('scenario')),
        'rate': load.get('rate'), 'duration': load.get('duration'),
        'orderPool': load.get('orderPool'), 'seedOrders': load.get('seedOrders'),
        'cpu': container.get('cpuLimit'), 'memoryLimit': container.get('memoryLimit'),
        'gitSha': meta.get('gitSha'), 'dirty': meta.get('gitDirty'),
        'sampling': meta.get('samplingIntervalSeconds'),
        'prometheus': meta.get('prometheusSampling'),
        'synthetic': meta.get('syntheticWarmup'),
        'preallocatedVUs': load.get('preallocatedVUs'), 'maxVUs': load.get('maxVUs'),
        'sleepSeconds': load.get('sleepSeconds')}.items() if value is not None}


def build_dataset(runs, rows):
    report = []
    for run, row in zip(runs, rows):
        meta_path = run / 'metadata.json'
        meta = json.loads(meta_path.read_text()) if meta_path.exists() else {}
        report.append({'name': run.name, 'runtime': row['runtime'], 'scenario': row['scenario'],
                       'memoryProfile': row['memory_profile'], 'status': row['status'],
                       'config': group_key(meta),
                       'memory': memory_points(run), 'latency': latency_windows(run),
                       'metrics': {key: finite_number(row.get(key)) for key in [
                           'startup_s', 'memory_avg_mib', 'memory_p95_mib', 'memory_peak_mib',
                           'p95_ms', 'p99_ms', 'failure_pct', 'requests']}})
    return report


def write_report(runs, rows, target):
    target = Path(target)
    data = json.dumps(build_dataset(runs, rows), ensure_ascii=False, separators=(',', ':'), allow_nan=False)
    # JSON is embedded in an HTML script element. Keep user-controlled names inert.
    data = data.replace('&', '\\u0026').replace('<', '\\u003c').replace('>', '\\u003e')
    template = Path(__file__).with_name('report-template.html').read_text()
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(template.replace('__REPORT_DATA__', data), encoding='utf-8')
    return target.resolve()

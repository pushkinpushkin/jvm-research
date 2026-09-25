#!/usr/bin/env python3
"""Fail-closed admission gate. Process completion is not research validity."""
from collections import Counter
import csv
import hashlib
import json
import math
from pathlib import Path
import sys
from experiment_support import update_metadata


def metric(summary, name, key='count'):
    value = summary.get('metrics', {}).get(name, {})
    return value.get('values', value).get(key)


def kafka_lag(text, no_events=False):
    rows = []
    for line in text.splitlines():
        parts = line.split()
        if len(parts) >= 6 and parts[1] in ('order.status.changed', 'business.event.occurred') and parts[2].isdigit():
            lag = parts[5]
            if lag == '-' and no_events and parts[4] == '0': lag = '0'
            if not lag.isdigit(): raise ValueError('Kafka lag is unknown')
            rows.append((parts[1], int(lag)))
    if {x[0] for x in rows} != {'order.status.changed','business.event.occurred'}:
        raise ValueError('Lag evidence missing for one or both topics')
    return sum(lag for _, lag in rows)


def validate(run):
    reasons = []
    def require(condition, message):
        if not condition: reasons.append(message)
    def read(name):
        try: return json.loads((run/name).read_text())
        except (OSError, ValueError): reasons.append(f'Missing/invalid {name}'); return {}
    meta = read('metadata.json')
    require(meta.get('exitCode') == 0, 'Runner did not finish successfully')
    require(meta.get('schemaVersion') == 3, 'Unsupported evidence schema')
    require(meta.get('gitDirty') is False, 'Uncommitted source changes: commit before comparison')
    limits = meta.get('actualContainerLimits',{})
    require(limits.get('nanoCpus') == 2_000_000_000 and limits.get('memoryBytes') == 1073741824, 'Actual resource limits differ from protocol v1: 2 CPU / 1 GiB')
    require(meta.get('prometheusSampling') is True, 'Prometheus/state sampling disabled')
    phases = read('phases.json')
    names = [p['name'] for p in phases] if isinstance(phases, list) else []
    scenario = meta.get('scenario')
    needed = ['startup','preparation',scenario,'finished'] if scenario == 'idle' else ['startup','preparation',scenario,'drain','post-load-idle','finished']
    require(names == needed, 'Incomplete or out-of-order phases')
    trace = read('workload.json')
    trace_file = run/'workload.json'
    if trace_file.exists(): require(hashlib.sha256(trace_file.read_bytes()).hexdigest() == meta.get('workloadSha256'), 'Trace hash mismatch')
    requests = trace.get('requests', [])
    expected = meta.get('expectedIterations')
    require(expected is not None and len(requests) == expected, 'Trace length differs from expected iterations')
    summary = read('k6-summary.json') if scenario != 'idle' else {}
    if scenario != 'idle':
        boundary = metric(summary, 'trace_boundary_skips')
        if boundary is None: boundary = 0  # Older summaries omit an unused counter.
        require(boundary in (0, 1), 'Invalid trace boundary skip count')
        require(isinstance(expected, int) and boundary in (0, 1)
                and metric(summary, 'iterations') == expected + boundary,
                'Completed iterations differ from planned work plus boundary skips')
        for name in ('http_reqs','business_requests'):
            require(metric(summary, name) == expected, f'{name} differs from planned {expected}')
        # k6 may omit a counter that never received samples.
        require(metric(summary, 'dropped_iterations') in (None, 0), 'Dropped iterations')
        require(metric(summary, 'unexpected_results') == 0, 'Unexpected business results or missing counter')
        failures = metric(summary, 'http_req_failed', 'rate')
        if failures is None: failures = metric(summary, 'http_req_failed', 'value')
        require(failures == 0, 'HTTP errors or missing HTTP error metric')
        outcomes = Counter(r['outcome'] for r in requests)
        require(metric(summary, 'business_processed') == outcomes['processed'], 'Processed count differs from trace')
        require(metric(summary, 'expected_faults') == outcomes['expected_fault'], 'Artificial fault count differs from trace')
        for mode, count in Counter(r['mode'] for r in requests).items():
            require(metric(summary, 'mode_'+mode) == count, f'External request mode count differs: {mode}')
        for percentile, limit in [('p(95)','p95Ms'),('p(99)','p99Ms')]:
            actual = metric(summary, 'business_latency', percentile)
            require(isinstance(actual, (int,float)) and math.isfinite(actual) and actual < meta.get('slo',{}).get(limit,0), f'Business latency {percentile} violates SLO or is missing')
    before, after = read('state-before.json'), read('state-after.json')
    counters = after.get('counters', {})
    for key in ('http_unexpected','scheduler_errors','consumer_errors','outbox_errors'):
        require(counters.get(key) == 0, f'{key} nonzero or missing')
    for key in ('events_enqueued','events_published','events_consumed','outbox_deliveries','events_duplicate'):
        require(key in counters, f'Missing event counter {key}')
    if counters:
        require(counters.get('events_enqueued') == counters.get('events_published') == counters.get('events_consumed'), 'Event balance not closed')
        require(counters.get('outbox_deliveries', -1) == counters.get('events_consumed',0) + counters.get('events_duplicate',0), 'Kafka delivery balance not closed')
        require(counters.get('scheduler_runs',0) > 0, 'No scheduler activity')
        if scenario != 'idle':
            require(counters.get('events_consumed',0) > 0, 'No Kafka processing evidence')
            require(counters.get('http_processed',0) + counters.get('http_expected_fault',0) == expected, 'Application HTTP count differs from k6')
            require(before.get('counters',{}).get('http_processed') == 0, 'Business requests occurred before load')
        if meta.get('trafficProfile') == 'normal':
            for key in ('http_expected_fault','scheduler_expected_fault','mongo_expected_fault','synthetic_duplicates'):
                require(counters.get(key) == 0, f'Unexpected fault in normal profile: {key}')
    snapshots = [after]
    for path in sorted((run/'state').glob('*.json')):
        try: snapshots.append(json.loads(path.read_text()))
        except ValueError: reasons.append('Invalid sampled application state')
    require(len(snapshots) > 1, 'No sampled bounds evidence')
    for state in snapshots:
        bounds = state.get('bounds',{})
        require(0 <= bounds.get('historyMax',-1) <= 40, 'History limit violated or unavailable')
        require(0 <= bounds.get('eventIdsMax',-1) <= 1024, 'Event receipt limit violated or unavailable')
        require(0 <= state.get('dedupCacheSize',-1) <= 10000, 'Dedup cache limit violated or unavailable')
        require(0 <= bounds.get('orders',-1) <= meta.get('load',{}).get('seedOrders',-1), 'Order population grew beyond seed')
    require(after.get('bounds',{}).get('orders') == meta.get('load',{}).get('seedOrders'), 'Final order population differs from seed')
    for key in ('pendingEvents','waiting','retryable'):
        require(after.get('bounds',{}).get(key) == 0, f'Background work remains: {key}')
    try:
        require(kafka_lag((run/'kafka-lag.txt').read_text(), no_events=counters.get('events_enqueued') == 0) == 0, 'Nonzero Kafka lag')
    except (OSError, ValueError) as ex: reasons.append(f'Kafka lag unavailable: {ex}')
    final = read('container-final.json')
    if isinstance(final,list) and final:
        require(final[0].get('RestartCount') == 0, 'Container restarted')
        require(final[0].get('State',{}).get('OOMKilled') is False, 'Container OOM state unavailable or OOM killed')
        require(final[0].get('State',{}).get('Running') is True, 'Container stopped')
    else: reasons.append('Final container state missing')
    try:
        with (run/'runtime-metrics.csv').open() as f: samples=list(csv.DictReader(f))
        require(bool(samples), 'No resource samples')
        for item in samples:
            for key in ('memory_used_bytes','memory_current_bytes','memory_peak_bytes','cpu_usage_usec','cpu_throttled_usec','cpu_nr_periods','cpu_nr_throttled'):
                value=float(item.get(key,'')); require(math.isfinite(value) and value >= 0, f'Invalid metric: {key}')
            for key in ('oom','oom_kill','memory_swap_bytes'):
                require(float(item.get(key,'')) == 0, f'{key} nonzero')
        for name in (scenario, 'post-load-idle'):
            if name == 'post-load-idle' and scenario == 'idle': continue
            phase_samples = [x for x in samples if x['phase'] == name]
            require(len(phase_samples) >= 2, f'Insufficient samples in {name}')
            if phase_samples:
                times = [float(x['phase_elapsed_seconds']) for x in phase_samples]
                duration = meta.get('load',{}).get('durationSeconds',0) if name == scenario else meta.get('postIdleSeconds',0)
                tolerance = max(3 * meta.get('samplingIntervalSeconds',5), 3)
                require(times[0] <= tolerance and times[-1] >= duration-tolerance, f'Incomplete sampling coverage for {name}')
                require(all(0 <= b-a <= tolerance for a,b in zip(times,times[1:])), f'Sampling gaps in {name}')
    except (OSError,ValueError,KeyError) as ex: reasons.append(f'Invalid resource evidence: {ex}')
    if meta.get('runtimeMode') == 'jit':
        texts = [(run/'prometheus-before.txt'), (run/'prometheus-after.txt')]
        for path in texts:
            text = path.read_text() if path.exists() else ''
            for name in ('jvm_memory_used_bytes','jvm_memory_committed_bytes','jvm_memory_max_bytes'):
                require(name in text, f'Missing JVM heap metric {name} in {path.name}')
    return {'eligible':not reasons, 'status':'пригоден для сравнения' if not reasons else 'отклонён', 'reasons':list(dict.fromkeys(reasons))}


if __name__ == '__main__':
    run = Path(sys.argv[1])
    try: result = validate(run)
    except Exception as ex: result = {'eligible':False, 'status':'отклонён', 'reasons':[f'Validator could not interpret artifacts: {ex}']}
    (run/'validation.json').write_text(json.dumps(result, indent=2, ensure_ascii=False)+'\n')
    state = json.loads((run/'state-after.json').read_text()) if (run/'state-after.json').exists() else {}
    counters = state.get('counters',{})
    observed = {k:v for k,v in counters.items() if k.startswith('external_') or k in ('events_enqueued','events_consumed','synthetic_duplicates')}
    update_metadata(run/'metadata.json', comparisonEligible=result['eligible'], observedWork=observed)
    print(json.dumps(result, ensure_ascii=False))
    sys.exit(0 if result['eligible'] else 2)

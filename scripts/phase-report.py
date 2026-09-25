#!/usr/bin/env python3
"""Summaries only within observed phases/windows. Raw counters remain authoritative."""
import csv
import json
import math
from pathlib import Path
import re
import statistics
import sys


def weighted_mean(points):
    duration = sum(b[0]-a[0] for a,b in zip(points,points[1:]))
    return sum((b[0]-a[0])*(a[1]+b[1])/2 for a,b in zip(points,points[1:]))/duration if duration else None


def summarize_samples(samples):
    result = {'samples':len(samples)}
    if not samples: return result
    result['observedSeconds'] = float(samples[-1]['elapsed_seconds']) - float(samples[0]['elapsed_seconds'])
    for field in ('memory_used_bytes','memory_current_bytes','memory_swap_bytes'):
        points = [(float(x['elapsed_seconds']),float(x[field])) for x in samples if x.get(field)]
        values = [x[1] for x in points]
        result[field] = {'timeWeightedMean':weighted_mean(points), 'sampleMedian':statistics.median(values) if values else None,
                         'sampleMax':max(values) if values else None}
    for field in ('cpu_usage_usec','cpu_throttled_usec','cpu_nr_periods','cpu_nr_throttled'):
        first,last = samples[0].get(field),samples[-1].get(field)
        result[field+'_delta'] = float(last)-float(first) if first and last else None
    peaks = [float(x['memory_peak_bytes']) for x in samples if x.get('memory_peak_bytes')]
    result['lifetimeCgroupPeakBytesAtWindowEnd'] = peaks[-1] if peaks else None
    return result


def prometheus_values(text):
    values = {}
    for line in text.splitlines():
        if line.startswith('#'): continue
        match = re.match(r'([a-zA-Z_:][a-zA-Z_0-9:]*)(\{.*?\})?\s+([-+\d.eE]+)(?:\s|$)', line)
        if not match: continue
        name, labels, number = match.groups()
        value = float(number)
        if not math.isfinite(value): continue
        if name in ('jvm_memory_used_bytes','jvm_memory_committed_bytes','jvm_memory_max_bytes'):
            area = 'heap' if 'area="heap"' in (labels or '') else 'nonheap'
            if value >= 0: values[name+'_'+area] = values.get(name+'_'+area,0) + value
        elif name.startswith(('jvm_gc_', 'process_cpu_seconds')):
            if name.endswith(('_count','_sum','_total')) or name == 'process_cpu_seconds_total':
                values[name] = values.get(name,0) + value
    return values


def report(run):
    with (run/'runtime-metrics.csv').open() as f: samples = list(csv.DictReader(f))
    result = {'definitions':{'memory_used_bytes':'Docker working set formula: memory.current minus inactive_file',
                            'mean':'Trapezoidal time-weighted mean over observed sample span, no extrapolation',
                            'peak':'cgroup memory.peak is lifetime full usage, not a phase working-set peak',
                            'cpu':'Last minus first cumulative counter in observed span; microseconds',
                            'missing':'Unavailable runtime metrics remain absent, never zero-filled'}, 'phases':{}}
    for phase in dict.fromkeys(x['phase'] for x in samples):
        selected = [x for x in samples if x['phase']==phase]
        windows = {}
        for start,end in [(0,60),(60,300),(300,600),(600,1200),(1200,1800),(1800,3600)]:
            subset = [x for x in selected if start <= float(x['phase_elapsed_seconds']) <= end]
            if subset: windows[f'{start}-{end}s'] = summarize_samples(subset)
        result['phases'][phase] = dict(whole=summarize_samples(selected), windows=windows)
    result['runtimeSamples'] = []
    for path in sorted((run/'prometheus').glob('*.prom')):
        elapsed = float(path.stem)
        sample = next((x for x in samples if float(x['elapsed_seconds']) == elapsed), {})
        result['runtimeSamples'].append({'elapsedSeconds':elapsed, 'phase':sample.get('phase'),
            'phaseElapsedSeconds':float(sample['phase_elapsed_seconds']) if sample else None, **prometheus_values(path.read_text())})
    return result


if __name__ == '__main__':
    run=Path(sys.argv[1])
    (run/'phase-summary.json').write_text(json.dumps(report(run),indent=2)+'\n')

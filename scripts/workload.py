#!/usr/bin/env python3
"""Versioned trace, independent of k6 VU assignment. No mutable PRNG state."""
import hashlib
import json
import math
import os
from pathlib import Path
import sys

PROFILES = {
    'normal': dict(long_delay=0, error=0, bad_response=0, slow=15, mongo=0, duplicate=0),
    'faults': dict(long_delay=3, error=5, bad_response=2, slow=15, mongo=2, duplicate=5),
}

def java_hash(text):
    value = 0
    for char in text:
        value = (31 * value + ord(char)) & 0xffffffff
    return value if value < 0x80000000 else value - 0x100000000


def mode(key, point, config):
    value = java_hash(key + point + 'external') % 100
    bound = 0
    for name in ('long_delay', 'error', 'bad_response', 'slow'):
        bound += config[name]
        if value < bound:
            return name
    return 'ok'


def trace(pool, count, seed, profile):
    if pool < 1 or count < 0 or seed < 0 or seed > 0x7fffffff:
        raise ValueError('Invalid trace parameters')
    config = PROFILES[profile]
    stride = 2 * (seed % pool) + 1
    while math.gcd(stride, pool) != 1:
        stride += 2
    result = []
    for index in range(count):
        order_id = f'order-{(seed + index * stride) % pool}'
        external = mode(order_id, 'fns-data', config)
        conflict = java_hash(order_id + 'http-process' + 'mongo') % 100 < config['mongo']
        result.append(dict(orderId=order_id, mode=external,
                           outcome='expected_fault' if external in ('long_delay','error','bad_response') or conflict else 'processed'))
    return dict(version=1, profile=profile, seed=seed, orderPool=pool, config=config, requests=result)


def write_trace(path, pool, count, seed, profile):
    data = trace(pool, count, seed, profile)
    path.write_text(json.dumps(data, separators=(',', ':')) + '\n')
    return hashlib.sha256(path.read_bytes()).hexdigest()


if __name__ == '__main__':
    from experiment_support import update_metadata, duration_seconds
    folder = Path(sys.argv[1])
    e = os.environ
    total = int(e['RATE']) * duration_seconds(e['DURATION']) / duration_seconds(e['RATE_TIME_UNIT'])
    if not total.is_integer():
        raise SystemExit('RATE * duration / RATE_TIME_UNIT must be an integer')
    digest = write_trace(folder/'workload.json', int(e['ORDER_POOL']), int(total), int(e['TRACE_SEED']), e['TRAFFIC_PROFILE'])
    update_metadata(folder/'metadata.json', workloadSha256=digest, expectedIterations=int(total),
                    trafficProfile=e['TRAFFIC_PROFILE'], traceSeed=int(e['TRACE_SEED']),
                    rateTimeUnit=e['RATE_TIME_UNIT'], slo={'p95Ms':float(e['SLO_P95_MS']), 'p99Ms':float(e['SLO_P99_MS'])})

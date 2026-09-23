#!/usr/bin/env python3
"""Sample the same Docker API/CLI statistics for JVM and Native Image."""
import csv
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import threading
import time
import urllib.request
from experiment_support import utc_now

STOP = threading.Event()


def size_bytes(text):
    match = re.fullmatch(r'([\d.]+)\s*([A-Za-z]+)', text.strip())
    if not match:
        raise ValueError(f'Invalid Docker memory value: {text!r}')
    number, unit = match.groups()
    units = {'B': 1, 'kB': 1000, 'KB': 1000, 'MB': 1000**2, 'GB': 1000**3,
             'TB': 1000**4, 'KiB': 1024, 'MiB': 1024**2, 'GiB': 1024**3, 'TiB': 1024**4}
    return round(float(number) * units[unit])


def sample(container):
    raw = subprocess.check_output(['docker', 'stats', '--no-stream', '--format', '{{json .}}', container],
                                  text=True, timeout=15)
    data = json.loads(raw)
    used, limit = map(size_bytes, data['MemUsage'].split('/'))
    if limit <= 0:
        raise ValueError('Docker returned no usable memory limit; container may have stopped')
    return {'cpu_percent': float(data['CPUPerc'].rstrip('%')), 'memory_used_bytes': used,
            'memory_limit_bytes': limit, 'memory_percent': float(data['MemPerc'].rstrip('%')),
            'pids': int(data['PIDs']) if data.get('PIDs', '').isdigit() else ''}


def collect(container):
    interval = float(os.environ.get('INTERVAL_SECONDS', '5'))
    if interval <= 0:
        raise ValueError('INTERVAL_SECONDS must be positive')
    output = Path(os.environ.get('OUT', 'runtime-metrics.csv'))
    output.parent.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    ready_epoch = float(os.environ.get('READY_EPOCH', str(time.time())))
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: STOP.set())
    fields = ['timestamp', 'elapsed_seconds', 'phase', 'cpu_percent', 'memory_used_bytes',
              'memory_limit_bytes', 'memory_percent', 'pids']
    with output.open('w', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        handle.flush()
        while True:
            final_sample = STOP.is_set()
            tick = time.monotonic()
            # A lost container or Docker error invalidates the run, not a zero-memory sample.
            row = sample(container)
            row.update(timestamp=utc_now(), elapsed_seconds=round(time.time() - ready_epoch, 3),
                       phase=(output.parent / 'phase.txt').read_text().strip()
                       if (output.parent / 'phase.txt').exists() else 'observation')
            writer.writerow(row)
            handle.flush()
            if os.environ.get('COLLECT_PROMETHEUS', 'true') == 'true':
                try:
                    with urllib.request.urlopen(os.environ['BASE_URL'] + '/actuator/prometheus', timeout=3) as response:
                        body = response.read()
                    target = output.parent / 'prometheus'
                    target.mkdir(exist_ok=True)
                    (target / f'{row["elapsed_seconds"]:012.3f}.prom').write_bytes(body)
                except Exception as exc:
                    # Optional runtime-specific telemetry does not invalidate universal metrics.
                    print(f'Prometheus scrape unavailable: {exc}', file=sys.stderr, flush=True)
            if final_sample:
                break
            STOP.wait(max(0, interval - (time.monotonic() - tick)))
    print(f'Metrics saved to {output} ({time.monotonic() - started:.1f}s)', flush=True)


if __name__ == '__main__':
    collect(sys.argv[1])

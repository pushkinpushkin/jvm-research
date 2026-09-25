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


CGROUP_SCRIPT = """
set -eu
cd /sys/fs/cgroup
test -f cgroup.controllers
for f in memory.current memory.peak memory.max memory.swap.current pids.current; do
  printf '%s ' "$f"
  if test -r "$f"; then cat "$f"; else printf 'unavailable\\n'; fi
done
for f in memory.stat memory.events cpu.stat; do
  while read -r key value; do printf '%s.%s %s\\n' "$f" "$key" "$value"; done < "$f"
done
"""


def parse_cgroup(raw):
    values = dict(line.split() for line in raw.splitlines())
    def number(key):
        value = values.get(key)
        return int(value) if value and value.isdigit() else ''
    current = number('memory.current')
    inactive = number('memory.stat.inactive_file')
    if current == '' or inactive == '':
        raise ValueError('cgroup v2 memory counters unavailable')
    working = current - inactive if inactive < current else current
    return {'memory_used_bytes':working, 'memory_limit_bytes':number('memory.max'),
            'memory_current_bytes':current, 'memory_peak_bytes':number('memory.peak'),
            'memory_swap_bytes':number('memory.swap.current'), 'oom':number('memory.events.oom'),
            'oom_kill':number('memory.events.oom_kill'), 'cpu_usage_usec':number('cpu.stat.usage_usec'),
            'cpu_throttled_usec':number('cpu.stat.throttled_usec'), 'cpu_nr_throttled':number('cpu.stat.nr_throttled'),
            'cpu_nr_periods':number('cpu.stat.nr_periods'), 'pids':number('pids.current'),
            'memory_percent':100 * working / number('memory.max')}


def sample(container):
    raw = subprocess.check_output(['docker', 'exec', container, 'sh', '-c', CGROUP_SCRIPT], text=True, timeout=15)
    return parse_cgroup(raw), raw


def collect(container):
    interval = float(os.environ.get('INTERVAL_SECONDS', '5'))
    if interval <= 0:
        raise ValueError('INTERVAL_SECONDS must be positive')
    output = Path(os.environ.get('OUT', 'runtime-metrics.csv'))
    output.parent.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    origin_epoch = float(os.environ.get('OBSERVATION_EPOCH', str(time.time())))
    previous = None
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: STOP.set())
    fields = ['timestamp', 'elapsed_seconds', 'phase', 'cpu_percent', 'memory_used_bytes',
              'memory_limit_bytes', 'memory_percent', 'pids', 'phase_elapsed_seconds',
              'memory_current_bytes', 'memory_peak_bytes', 'memory_swap_bytes', 'oom', 'oom_kill',
              'cpu_usage_usec', 'cpu_throttled_usec', 'cpu_nr_throttled', 'cpu_nr_periods']
    with output.open('w', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        handle.flush()
        while True:
            final_sample = STOP.is_set()
            tick = time.monotonic()
            # A lost container or Docker error invalidates the run, not a zero-memory sample.
            row, raw = sample(container)
            now = time.time()
            row['cpu_percent'] = (100 * (row['cpu_usage_usec'] - previous[1]) / ((now - previous[0]) * 1e6)) if previous else ''
            previous = now, row['cpu_usage_usec']
            raw_dir = output.parent / 'cgroup'
            raw_dir.mkdir(exist_ok=True)
            (raw_dir / f'{now:.6f}.txt').write_text(raw)
            phases_file = output.parent / 'phases.json'
            phases = json.loads(phases_file.read_text()) if phases_file.exists() else []
            phase = phases[-1] if phases else {'name':'startup', 'epoch':origin_epoch}
            row.update(timestamp=utc_now(), elapsed_seconds=round(now - origin_epoch, 3),
                       phase=phase['name'], phase_elapsed_seconds=round(now - phase['epoch'], 3))
            writer.writerow(row)
            handle.flush()
            if os.environ.get('COLLECT_PROMETHEUS', 'true') == 'true':
                try:
                    with urllib.request.urlopen(os.environ['BASE_URL'] + '/actuator/prometheus', timeout=3) as response:
                        body = response.read()
                    target = output.parent / 'prometheus'
                    target.mkdir(exist_ok=True)
                    (target / f'{row["elapsed_seconds"]:012.3f}.prom').write_bytes(body)
                    with urllib.request.urlopen(os.environ['BASE_URL'] + '/research/state', timeout=5) as response:
                        state = response.read()
                    state_dir = output.parent / 'state'
                    state_dir.mkdir(exist_ok=True)
                    (state_dir / f'{row["elapsed_seconds"]:012.3f}.json').write_bytes(state)
                except Exception as exc:
                    # Optional runtime-specific telemetry does not invalidate universal metrics.
                    print(f'Prometheus scrape unavailable: {exc}', file=sys.stderr, flush=True)
            if final_sample:
                break
            STOP.wait(max(0, interval - (time.monotonic() - tick)))
    print(f'Metrics saved to {output} ({time.monotonic() - started:.1f}s)', flush=True)


if __name__ == '__main__':
    collect(sys.argv[1])

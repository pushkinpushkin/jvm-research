#!/usr/bin/env python3
"""Small, dependency-free helpers shared by the experiment runner and collector."""
import datetime as dt
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import time


def duration_seconds(value):
    units = {'h': 3600, 'm': 60, 's': 1, 'ms': .001}
    parts = re.findall(r'(\d+(?:\.\d+)?)(ms|h|m|s)', value)
    if not parts or ''.join(n + u for n, u in parts) != value:
        raise ValueError('Duration must use h/m/s/ms, for example 30m or 1m30s')
    seconds = sum(float(n) * units[u] for n, u in parts)
    if not math.isfinite(seconds) or seconds <= 0:
        raise ValueError('Duration must be positive and finite')
    return seconds


def utc_now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def update_metadata(path, **values):
    path = Path(path)
    data = json.loads(path.read_text()) if path.exists() else {}
    data.update(values)
    tmp = path.with_suffix('.tmp')
    tmp.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n')
    tmp.replace(path)


def initialize(path):
    e = os.environ
    update_metadata(path, schemaVersion=2, runId=e['RUN_ID'], runProfile=e['RUN_PROFILE'],
                    jvmVariant=e['JVM_VARIANT'], jvmProfile=e['JVM_PROFILE'],
                    runtimeMode=e['RUNTIME_MODE'], memoryProfile=e['MEMORY_PROFILE'],
                    scenario=e['SCENARIO'], runtimeImage=e['RUNTIME_IMAGE'],
                    nativeBuilderImage=e.get('NATIVE_BUILDER_IMAGE'),
                    runtimeDockerfile=e['RUNTIME_DOCKERFILE'], gitSha=e['GIT_SHA'],
                    gitDirty=bool(subprocess.check_output(['git', 'status', '--porcelain']).strip()),
                    javaToolOptions=e.get('JAVA_TOOL_OPTIONS', ''),
                    container={'cpuLimit': e['CONTAINER_CPU_LIMIT'], 'memoryLimit': e['CONTAINER_MEMORY_LIMIT']},
                    workHelmReference={k: e.get(v) for k, v in {
                        'cpuLimit': 'WORK_CPU_LIMIT', 'cpuRequest': 'WORK_CPU_REQUEST',
                        'memoryLimit': 'WORK_MEMORY_LIMIT', 'memoryRequest': 'WORK_MEMORY_REQUEST',
                        'timezone': 'WORK_TIMEZONE', 'extraJavaOpts': 'WORK_EXTRA_JAVA_OPTS'}.items()},
                    load={'scenario': e['SCENARIO'], 'rate': int(e['RATE']),
                          'duration': e['DURATION'], 'durationSeconds': duration_seconds(e['DURATION']),
                          'orderPool': int(e['ORDER_POOL']), 'seedOrders': int(e['SEED_ORDERS']),
                          'preallocatedVUs': int(e['PREALLOCATED_VUS']), 'maxVUs': int(e['MAX_VUS']),
                          'sleepSeconds': float(e['SLEEP_SECONDS'])},
                    samplingIntervalSeconds=float(e['INTERVAL_SECONDS']),
                    prometheusSampling=e['COLLECT_PROMETHEUS'] == 'true',
                    syntheticWarmup=e['SYNTHETIC_WARMUP'] == 'true',
                    k6TimeSeries=e['K6_TIME_SERIES'] == 'true',
                    composeProject=e['COMPOSE_PROJECT_NAME'],
                    createdAt=utc_now(), status='preparing')


def ready(path, inspection):
    container = json.loads(Path(inspection).read_text())[0]
    started = container['State']['StartedAt']
    # Docker emits nanoseconds; datetime is limited to microseconds.
    started = re.sub(r'(\.\d{6})\d+', r'\1', started).replace('Z', '+00:00')
    now = dt.datetime.now(dt.timezone.utc)
    start = dt.datetime.fromisoformat(started)
    update_metadata(path, startupSeconds=(now - start).total_seconds(),
                    startupDefinition='Docker State.StartedAt to first HTTP health status UP; polling 250ms',
                    readyAt=now.isoformat(), readyEpoch=time.time(),
                    containerId=container['Id'], imageId=container['Image'],
                    actualContainerLimits={'nanoCpus': container['HostConfig'].get('NanoCpus'),
                                           'memoryBytes': container['HostConfig'].get('Memory')},
                    status='running')


if __name__ == '__main__':
    command, *args = sys.argv[1:]
    if command == 'duration':
        print(duration_seconds(args[0]))
    elif command == 'init':
        initialize(args[0])
    elif command == 'ready':
        ready(*args)
    elif command == 'finish':
        update_metadata(args[0], exitCode=int(args[1]),
                        status='completed' if args[1] == '0' else 'failed', finishedAt=utc_now())

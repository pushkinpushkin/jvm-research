#!/usr/bin/env python3
"""Record one narrowly verified network-only transition in a stopped rate10 series."""
import argparse
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


COMPOSE = 'infra/docker-compose.yml'
WRAPPER = 'scripts/run-vps-rate10-matrix.sh'
ADDITIONS = {'scripts/accept-network-isolation.py',
             'scripts/tests/test_network_isolation_transition.py'}
DOCS = {'CURRENT_STATE.md', 'DECISIONS.md', 'docs/rate10-matrix.md'}


def run(*args):
    return subprocess.check_output(args)


def isolated_compose(original):
    replacements = {
        '"${APP_PORT:-8080}:8080"': '"127.0.0.1:${APP_PORT:-8080}:8080"',
        '    ports:\n      - "27017:27017"\n': '',
        '    ports:\n      - "9092:9092"\n': '',
        '"8089:8080"': '"127.0.0.1:8089:8080"',
    }
    for old, new in replacements.items():
        if original.count(old) != 1:
            raise ValueError('Original Compose does not match the supported network fix')
        original = original.replace(old, new)
    return original


def source_hash(ref=None):
    names = run('git', 'ls-tree', '-r', '--name-only', '-z', ref) if ref else run(
        'git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z')
    digest = hashlib.sha256()
    for name in sorted(set(filter(None, names.split(b'\0')))):
        path = name.decode()
        content = run('git', 'show', f'{ref}:{path}') if ref else Path(path).read_bytes()
        digest.update(name + b'\0' + content + b'\0')
    return digest.hexdigest()


def current_fingerprint():
    lines = [f'source_sha256={source_hash()}',
             'wrapper_sha256=' + hashlib.sha256(Path(WRAPPER).read_bytes()).hexdigest()]
    for command in [('git', 'rev-parse', 'HEAD'), ('hostname',), ('uname', '-rmo'),
                    ('docker', 'version', '--format', '{{.Server.Version}}'),
                    ('docker', 'compose', 'version'), ('k6', 'version'),
                    ('python3', '--version')]:
        lines.append(run(*command).decode().strip())
    return '\n'.join(lines) + '\n'


def validate_source_transition(old, current):
    before, after = old.splitlines(), current.splitlines()
    if len(before) < 9 or len(before) != len(after):
        raise ValueError('Unsupported fingerprint format')
    base = before[2]
    if not re.fullmatch(r'[0-9a-f]{40}', base):
        raise ValueError('Invalid original commit')
    if before[3:] != after[3:]:
        raise ValueError('Host, kernel or tool versions changed')
    if before[1] != after[1]:
        raise ValueError('Matrix wrapper changed')
    if before[0] != f'source_sha256={source_hash(base)}':
        raise ValueError('Original source fingerprint cannot be reproduced from its commit')
    changes = run('git', 'diff', '--name-only', '-z', base, 'HEAD').decode().split('\0')
    changed = set(filter(None, changes))
    if COMPOSE not in changed or changed - {COMPOSE} - DOCS - ADDITIONS:
        raise ValueError('Changes extend beyond the approved network fix and its documentation/helper')
    original_names = set(run('git', 'ls-tree', '-r', '--name-only', base).decode().splitlines())
    if changed & ADDITIONS & original_names:
        raise ValueError('Migration helpers must be additions, not changes to pre-existing code')
    original = run('git', 'show', f'{base}:{COMPOSE}').decode()
    if Path(COMPOSE).read_text() != isolated_compose(original):
        raise ValueError('Compose differs from the exact four approved port changes')


def accept(series, confirmed):
    if not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_.-]*', series):
        raise ValueError('Invalid SERIES_ID')
    root = Path('results/vps-rate10-1h')
    control = root / series / 'control'
    if not (control / 'prepared.ok').is_file():
        raise ValueError('Missing prepared series; nothing will be imported or rebuilt')
    with (root / 'matrix.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if run('git', 'status', '--porcelain', '--untracked-files=normal').strip():
            raise ValueError('Commit changes and remove non-ignored untracked files first')
        if run('docker', 'ps', '-q').strip():
            raise ValueError('Containers are running; stop the experiment first')
        processes = subprocess.run(['pgrep', '-af',
            '[r]un-experiment.sh|[r]un-v1-idle-rare-matrix.sh|[r]un-local-10m-matrix.sh|[k]6 run'],
            capture_output=True)
        if processes.returncode != 1:
            raise ValueError('Experiment process found or process check failed')
        old = (control / 'fingerprint.txt').read_text()
        current = current_fingerprint()
        refs = (control / 'image-refs.txt').read_text().splitlines()
        if not refs:
            raise ValueError('Missing image references')
        expected = json.loads((control / 'images.json').read_text())
        actual = json.loads(run('docker', 'image', 'inspect', *refs))
        if [x['Id'] for x in expected] != [x['Id'] for x in actual]:
            raise ValueError('Base/dependency images changed')
        if old == current:
            print('Fingerprint already matches; no change needed.')
            return
        validate_source_transition(old, current)
        if not confirmed:
            print('CHECK PASSED: exact network-only transition, unchanged host/tools/images/wrapper.\n'
                  'Run again with --apply to archive and accept this transition. No files changed.')
            return
        archive = control / ('network-isolation-' + current.splitlines()[2])
        # Exclusive directory creation prevents overwriting previous transition evidence.
        archive.mkdir()
        (archive / 'fingerprint-before.txt').write_text(old)
        (archive / 'fingerprint-after.txt').write_text(current)
        (archive / 'transition.json').write_text(json.dumps({
            'reason': 'Explicitly approved network isolation after external MongoDB deletion',
            'acceptedAtUtc': datetime.now(timezone.utc).isoformat(),
            'oldCommit': old.splitlines()[2], 'newCommit': current.splitlines()[2],
            'sourceDiff': run('git', 'diff', old.splitlines()[2], 'HEAD', '--', COMPOSE).decode(),
            'priorRuns': 'Preserved unchanged; admission does not establish absence of interference',
        }, ensure_ascii=False, indent=2) + '\n')
        with tempfile.NamedTemporaryFile(mode='w', dir=control, delete=False) as tmp:
            tmp.write(current)
            temporary = tmp.name
        os.replace(temporary, control / 'fingerprint.txt')
        print(f'Accepted network-only transition. Original fingerprint and manifest: {archive}')
        print('Runs, exit markers, images and saved launcher were not changed. Resume with --retry-failed.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('series')
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    # Match the matrix launcher environment; do not inherit Docker/tool overrides.
    clean = {k: os.environ[k] for k in ('PATH', 'HOME') if k in os.environ}
    clean.update(USER=os.environ.get('USER', 'root'), LANG='C.UTF-8',
                 TERM=os.environ.get('TERM', 'xterm'))
    os.environ.clear()
    os.environ.update(clean)
    os.chdir(Path(__file__).resolve().parents[1])
    try:
        accept(args.series, args.apply)
    except (ValueError, OSError, subprocess.CalledProcessError) as exc:
        parser.exit(1, f'Refused: {exc}\n')

"""Exercise orchestration with fake Docker/k6; no containers or workloads run."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'run-vps-rate10-matrix.sh'


@unittest.skipUnless(os.name == 'posix' and shutil.which('flock') and shutil.which('git'),
                     'requires POSIX, git and flock')
class Rate10MatrixTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.repo = self.base / 'repo'
        self.bin = self.base / 'bin'
        self.repo.mkdir()
        self.bin.mkdir()
        self.root = self.repo / 'results/vps-rate10-1h/test-series'
        self.write(self.repo / '.gitignore', 'results/\n')
        self.write(self.repo / 'Dockerfile', 'ARG BUILDER_IMAGE=builder:test\n')
        for name in ('hotspot-elastic', 'openj9-elastic', 'graalvm-elastic', 'graalvm-native'):
            native = name == 'graalvm-native'
            self.write(self.repo / f'profiles/work-{name}.env',
                       f'RUNTIME_IMAGE=runtime:{name}\nJVM_VARIANT={name}\n'
                       + ('RUNTIME_MODE=native\nNATIVE_BUILDER_IMAGE=builder:native\n'
                          if native else 'RUNTIME_MODE=jit\nJAVA_TOOL_OPTIONS=test-jvm-options\n'))
        self.write(self.repo / 'scripts/vps-preflight.sh', '#!/bin/bash\nexit 0\n')
        self.write(self.repo / 'scripts/vps-capture-host.sh', '#!/bin/bash\nmkdir -p "$1"\n')
        self.write(self.repo / 'scripts/run-experiment.sh', '''#!/bin/bash
set -eu
[[ $RATE == 10 && $DURATION == 1h && $POST_IDLE_SECONDS == 3600 ]]
[[ $ORDER_POOL == 1000 && $SEED_ORDERS == 1000 && $MAX_VUS == 50 ]]
[[ -z ${JAVA_TOOL_OPTIONS:-} ]]
printf '%s\\n' "$RUN_ID" >> results/calls.txt
mkdir -p "$RESULTS_ROOT/$RUN_ID"
printf '{}\\n' > "$RESULTS_ROOT/$RUN_ID/metadata.json"
if [[ $RUN_ID == *pass1-2* && -f results/fail ]]; then
  printf '{"eligible":false}\\n' > "$RESULTS_ROOT/$RUN_ID/validation.json"
  exit 2
fi
printf '{"eligible":true}\\n' > "$RESULTS_ROOT/$RUN_ID/validation.json"
''')
        self.write(self.bin / 'docker', '''#!/bin/bash
if [[ $1 == image && $2 == inspect ]]; then
  id=fixed
  [[ ! -f results/change-image ]] || id=changed
  printf '[{"Id":"%s"}]\\n' "$id"
fi
if [[ $1 == version ]]; then echo mock-docker; fi
if [[ $1 == compose && $2 == version ]]; then echo mock-compose; fi
exit 0
''')
        self.write(self.bin / 'k6', '#!/bin/bash\necho mock-k6\n')
        self.write(self.bin / 'ss', '#!/bin/bash\nexit 0\n')
        self.write(self.bin / 'pgrep', '#!/bin/bash\nexit 1\n')
        for command in (['git', 'init', '-q'], ['git', 'add', '.'],
                        ['git', '-c', 'user.name=Test', '-c', 'user.email=test@example.com',
                         'commit', '-qm', 'fixture']):
            subprocess.run(command, cwd=self.repo, check=True, capture_output=True)
        self.env = dict(os.environ, PATH=str(self.bin) + ':' + os.environ['PATH'],
                        JAVA_TOOL_OPTIONS='must-not-leak', RATE='999')

    @staticmethod
    def write(path, content):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        path.chmod(0o755)

    def run_matrix(self, *extra):
        return subprocess.run(['bash', str(SCRIPT), 'test-series', str(self.repo), *extra],
                              env=self.env, capture_output=True, text=True, timeout=30)

    def assert_success(self, result):
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_exact_order_and_resume(self):
        self.assert_success(self.run_matrix())
        calls = (self.repo / 'results/calls.txt').read_text().splitlines()
        expected = ['hotspot-elastic', 'openj9-elastic', 'graalvm-elastic', 'graalvm-native',
                    'graalvm-native', 'graalvm-elastic', 'openj9-elastic', 'hotspot-elastic',
                    'openj9-elastic', 'hotspot-elastic', 'graalvm-native', 'graalvm-elastic']
        self.assertEqual([x.split('-work-')[1] for x in calls], expected)
        self.assert_success(self.run_matrix())
        self.assertEqual((self.repo / 'results/calls.txt').read_text().splitlines(), calls)

    def test_failure_stops_and_explicit_retry_preserves_attempt(self):
        self.write(self.repo / 'results/fail', '')
        failed = self.run_matrix()
        self.assertNotEqual(failed.returncode, 0)
        self.assertIn('Runner exit=2', failed.stdout + failed.stderr)
        self.assertEqual(len((self.repo / 'results/calls.txt').read_text().splitlines()), 2)
        refused = self.run_matrix()
        self.assertNotEqual(refused.returncode, 0)
        self.assertIn('Incomplete/failed', refused.stdout + refused.stderr)
        (self.repo / 'results/fail').unlink()
        self.assert_success(self.run_matrix('--retry-failed'))
        archived = list((self.root / '_attempts').glob('*/*/validation.json'))
        self.assertEqual(len(archived), 1)
        self.assertFalse(json.loads(archived[0].read_text())['eligible'])
        self.assertEqual(len((self.repo / 'results/calls.txt').read_text().splitlines()), 13)

    def test_resume_rejects_changed_images(self):
        self.write(self.repo / 'results/fail', '')
        self.assertNotEqual(self.run_matrix().returncode, 0)
        self.write(self.repo / 'results/change-image', '')
        result = self.run_matrix('--retry-failed')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('images changed', result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()

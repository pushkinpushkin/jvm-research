"""Real Git fixtures; Docker/process discovery mocked, no experiment runs."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'accept-network-isolation.py'
spec = importlib.util.spec_from_file_location('transition', SCRIPT)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
COMPOSE = '''services:
  sandbox-service:
    ports:
      - "${APP_PORT:-8080}:8080"
  mongo:
    ports:
      - "27017:27017"
  kafka:
    ports:
      - "9092:9092"
  wiremock:
    ports:
      - "8089:8080"
'''


class NetworkTransitionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        previous = Path.cwd()
        os.chdir(self.tmp.name)
        self.addCleanup(os.chdir, previous)
        self.git('init', '-q')
        for name, text in {m.COMPOSE: COMPOSE, m.WRAPPER: '# original wrapper\n',
                           '.gitignore': 'results/\n', 'src/App.java': '// original\n'}.items():
            p = Path(name)
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text)
        self.commit()
        self.before = self.fingerprint()
        self.control = Path('results/vps-rate10-1h/series/control')
        self.control.mkdir(parents=True)
        (self.control / 'fingerprint.txt').write_text(self.before)
        (self.control / 'prepared.ok').touch()
        (self.control / 'image-refs.txt').write_text('mongo:7.0\n')
        (self.control / 'images.json').write_text('[{"Id":"fixed"}]')
        self.marker = self.control / 'successful-run.exit'
        self.marker.write_text('0\n')
        Path(m.COMPOSE).write_text(m.isolated_compose(COMPOSE))
        self.commit()

    def git(self, *args):
        return subprocess.check_output(['git', *args])

    def commit(self):
        self.git('add', '.')
        self.git('-c', 'user.name=Test', '-c', 'user.email=test@example.com',
                 'commit', '-qm', 'fixture')

    def fingerprint(self):
        import hashlib
        return '\n'.join([f'source_sha256={m.source_hash()}',
            'wrapper_sha256=' + hashlib.sha256(Path(m.WRAPPER).read_bytes()).hexdigest(),
            self.git('rev-parse', 'HEAD').decode().strip(),
            'host', 'kernel', 'docker', 'compose', 'k6', 'python']) + '\n'

    def invoke(self, apply=False, changed_image=False, running=False):
        original = m.run
        def fake(*args):
            if args[:2] == ('docker', 'ps'):
                return b'container' if running else b''
            if args[:3] == ('docker', 'image', 'inspect'):
                return json.dumps([{'Id': 'changed' if changed_image else 'fixed'}]).encode()
            return original(*args)
        original_subprocess_run = subprocess.run
        def fake_process(args, **kwargs):
            if args[0] == 'pgrep':
                return subprocess.CompletedProcess(args, 1)
            return original_subprocess_run(args, **kwargs)
        current = self.fingerprint()
        with patch.object(m, 'run', side_effect=fake), \
             patch.object(m, 'current_fingerprint', return_value=current), \
             patch.object(m.subprocess, 'run', side_effect=fake_process):
            m.accept('series', apply)

    def test_check_apply_archive_and_idempotence(self):
        self.invoke()
        self.assertEqual((self.control / 'fingerprint.txt').read_text(), self.before)
        self.assertEqual(list(self.control.glob('network-isolation-*')), [])
        self.invoke(apply=True)
        archive, = self.control.glob('network-isolation-*')
        self.assertEqual((archive / 'fingerprint-before.txt').read_text(), self.before)
        self.assertEqual((self.control / 'fingerprint.txt').read_text(), self.fingerprint())
        self.assertEqual(self.marker.read_text(), '0\n')
        self.assertEqual((self.control / 'images.json').read_text(), '[{"Id":"fixed"}]')
        self.invoke(apply=True)
        self.assertEqual(len(list(self.control.glob('network-isolation-*'))), 1)

    def test_rejects_application_change(self):
        Path('src/App.java').write_text('// changed\n')
        self.commit()
        with self.assertRaisesRegex(ValueError, 'beyond'):
            self.invoke(True)
        self.assertEqual((self.control / 'fingerprint.txt').read_text(), self.before)

    def test_rejects_other_compose_change(self):
        p = Path(m.COMPOSE)
        p.write_text(p.read_text() + '\n# unrelated modification\n')
        self.commit()
        with self.assertRaisesRegex(ValueError, 'exact four'):
            self.invoke(True)

    def test_rejects_host_or_tool_change(self):
        with self.assertRaisesRegex(ValueError, 'Host, kernel'):
            m.validate_source_transition(self.before, self.fingerprint().replace('kernel', 'new-kernel'))

    def test_rejects_unreproducible_original(self):
        with self.assertRaisesRegex(ValueError, 'cannot be reproduced'):
            m.validate_source_transition(self.before.replace('source_sha256=', 'source_sha256=bad'),
                                         self.fingerprint())

    def test_rejects_changed_images_or_running_containers(self):
        with self.assertRaisesRegex(ValueError, 'images changed'):
            self.invoke(True, changed_image=True)
        with self.assertRaisesRegex(ValueError, 'Containers are running'):
            self.invoke(True, running=True)

    def test_rejects_dirty_source(self):
        Path('src/App.java').write_text('// uncommitted\n')
        with self.assertRaisesRegex(ValueError, 'Commit changes'):
            self.invoke(True)


if __name__ == '__main__':
    unittest.main()

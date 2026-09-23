"""Checks that chart data preserves missing values and keeps HTML standalone."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from chart_report import build_dataset, latency_windows


class ReportTests(unittest.TestCase):
    def test_idle_without_k6_produces_html_without_invented_latency(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            run = root / 'run1'
            run.mkdir()
            (run / 'metadata.json').write_text(json.dumps({
                'jvmVariant': 'graalvm-native', 'scenario': 'idle', 'status': 'completed',
                'memoryProfile': 'native-default', 'load': {'rate': 0, 'duration': '10s'},
                'container': {'cpuLimit': '2', 'memoryLimit': '1g'}}))
            (run / 'runtime-metrics.csv').write_text('elapsed_seconds,memory_used_bytes,cpu_percent,phase\n0,104857600,1.0,idle\n5,125829120,2.0,idle\n')
            report = root / 'report.html'
            result = subprocess.run(['bash', str(ROOT / 'scripts/compare-benchmark-root.sh'), str(root), '--html', str(report)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertTrue(report.exists())
            contents = report.read_text()
            self.assertIn('Память во времени', contents)
            self.assertIn('100.0', contents)
            self.assertIn('"p95_ms":null', contents)
            self.assertIn('"latency":[]', contents)
            self.assertNotIn('__REPORT_DATA__', contents)

    def test_series_and_k6_windows_without_smearing_across_minutes(self):
        with tempfile.TemporaryDirectory() as directory:
            run = Path(directory)
            (run / 'runtime-metrics.csv').write_text('elapsed_seconds,memory_used_bytes,cpu_percent,phase\n'
                                                        '0,104857600,12.4,load\n5,125829120,17.1,load\n')
            (run / 'k6-timeseries.json').write_text('\n'.join(json.dumps({
                'type': 'Point', 'metric': 'http_req_duration',
                'data': {'time': timestamp, 'value': value}})
                for timestamp, value in [('2026-01-01T00:00:00Z', 10),
                                         ('2026-01-01T00:00:50Z', 30),
                                         ('2026-01-01T00:01:02Z', 100)]) + '\n')
            row = {'runtime': 'hotspot-liberica', 'scenario': 'load', 'memory_profile': 'fixed-heap',
                   'status': 'completed', 'startup_s': 5, 'p95_ms': 50, 'memory_peak_mib': 120}
            data = build_dataset([run], [row])[0]
            self.assertEqual([p['m'] for p in data['memory']], [100, 120])
            self.assertEqual([p['c'] for p in data['memory']], [12.4, 17.1])
            self.assertEqual([p['n'] for p in data['latency']], [2, 1])
            self.assertEqual([p['v'] for p in data['latency']], [29.0, 100.0])

    def test_untrusted_run_name_remains_json_text(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            run = root / '<script onload=alert(1)>'
            run.mkdir()
            (run / 'metadata.json').write_text('{}')
            report = root / 'out.html'
            subprocess.run(['bash', str(ROOT / 'scripts/compare-benchmark-root.sh'), str(root), '--html', str(report)],check=True,capture_output=True)
            contents = report.read_text()
            self.assertNotIn('<script onload=alert(1)>', contents)
            self.assertIn('\\u003cscript', contents)


if __name__ == '__main__':
    unittest.main()

"""Offline checks of orchestration and numerical correctness, not real runtime smoke tests."""
import csv
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from experiment_support import duration_seconds


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / file)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


compare = module('compare', 'compare-results.py')
collector = module('collector', 'collect-container-metrics.py')

FAKE_TOOL = '''#!/usr/bin/env python3
import datetime, json, os, pathlib, sys, time
name = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ['TEST_CALLS'], 'a') as f:
    f.write(json.dumps([name, args]) + '\\n')
if name == 'docker':
    if args[0] == 'inspect':
        print(json.dumps([{'Id':'fake', 'Image':'sha256:fake',
              'State':{'StartedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()},
              'HostConfig':{'NanoCpus':int(float(os.environ['CONTAINER_CPU_LIMIT'])*1e9),'Memory':1073741824}}]))
    elif args[0] == 'stats':
        if os.environ.get('TEST_COLLECTOR_FAIL') == '1': sys.exit(7)
        print(json.dumps({'MemUsage':'100MiB / 1GiB','MemPerc':'9.77%','CPUPerc':'2.0%','PIDs':'30'}))
    elif 'ps' in args and '-q' in args: print('fake')
    elif 'exec' in args: sys.exit(1)
elif name == 'curl':
    if 'prometheus' in args[-1]: print('jvm_memory_used_bytes{area="heap"} 100')
    else: print('{"status":"UP"}')
elif name == 'k6':
    time.sleep(float(os.environ.get('TEST_WORKLOAD_SECONDS', '0.3')))
    target = pathlib.Path(args[args.index('--summary-export')+1])
    target.write_text(json.dumps({'metrics':{'http_reqs':{'count':10}, 'http_req_failed':{'value':0.1},
        'http_req_duration':{'avg':12,'med':10,'p(95)':30,'p(99)':35,'p(99.9)':36,'max':37}}}))
    sys.exit(int(os.environ.get('TEST_K6_EXIT', '0')))
'''


class NumericTests(unittest.TestCase):
    def test_duration(self):
        self.assertEqual(duration_seconds('30m'), 1800)
        self.assertEqual(duration_seconds('1m30s'), 90)
        self.assertEqual(duration_seconds('500ms'), .5)
        for invalid in ('0s', '-1m', '30', '5m;echo hi', 'nan'):
            with self.assertRaises(ValueError): duration_seconds(invalid)

    def test_units_and_percentile(self):
        self.assertEqual(collector.size_bytes('1GiB'), 1073741824)
        self.assertEqual(collector.size_bytes('1GB'), 1000000000)
        self.assertEqual(compare.percentile([100, 200, 300], .95), 290)
        self.assertIsNone(compare.percentile([], .95))

    def test_comparison_missing_and_timepoints(self):
        with tempfile.TemporaryDirectory() as directory:
            run = Path(directory)
            (run / 'metadata.json').write_text(json.dumps({'load':{'rate':0}, 'samplingIntervalSeconds':5}))
            with (run / 'runtime-metrics.csv').open('w') as f:
                f.write('elapsed_seconds,memory_used_bytes\n0,104857600\n300,209715200\n602,314572800\n')
            row = compare.summarize(run)
            self.assertEqual(row['memory_avg_mib'], 200)
            self.assertEqual(row['memory_p95_mib'], 290)
            self.assertEqual(row['memory_5m_mib'], 200)
            self.assertEqual(row['memory_10m_mib'], 300)
            self.assertIsNone(row['memory_20m_mib'])
            self.assertIsNone(row['avg_ms'])
            self.assertIsNone(row['failures'])
            (run / 'k6-summary.json').write_text(json.dumps({'metrics':{'http_reqs':{'count':100}, 'http_req_failed':{'value':.02}}}))
            self.assertEqual(compare.summarize(run)['failures'], 2)
            (run / 'k6-summary.json').write_text(json.dumps({'metrics':{'http_reqs':{'values':{'count':100}}, 'http_req_failed':{'values':{'rate':.03, 'passes':3, 'fails':97}}}}))
            self.assertEqual(compare.summarize(run)['failures'], 3)


class RunnerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.bin = self.directory / 'bin'
        self.bin.mkdir()
        for tool in ['docker', 'curl', 'k6']:
            file = self.bin / tool
            file.write_text(FAKE_TOOL)
            file.chmod(0o755)
        self.env = dict(os.environ, PATH=str(self.bin)+os.pathsep+os.environ['PATH'],
                        TEST_CALLS=str(self.directory / 'calls.jsonl'),
                        RESULTS_ROOT=str(self.directory / 'results'), RUN_ID='test-run',
                        DURATION='1s', INTERVAL_SECONDS='0.1', COLLECT_PROMETHEUS='false',
                        ORDER_POOL='5', SEED_ORDERS='5')
        for key in ['JAVA_TOOL_OPTIONS', 'SCENARIO', 'RATE', 'JVM_VARIANT', 'RUN_PROFILE', 'MEMORY_PROFILE']:
            self.env.pop(key, None)
        self.result = self.directory / 'results/test-run'

    def tearDown(self):
        self.temp.cleanup()

    def run_experiment(self, scenario='idle', profile='work-graalvm-native.env', **env):
        actual = dict(self.env, SCENARIO=scenario, **env)
        result = subprocess.run(['bash', 'scripts/run-experiment.sh', 'profiles/'+profile],
                                cwd=ROOT, env=actual, capture_output=True, text=True, timeout=20)
        return result

    def calls(self):
        return [json.loads(line) for line in (self.directory / 'calls.jsonl').read_text().splitlines()]

    def assert_cleaned(self):
        self.assertTrue(any('down' in args and '-v' in args for name,args in self.calls() if name == 'docker'))
        self.assertIn('Metrics saved to', (self.result / 'collector.log').read_text())

    def test_native_idle_no_business_workload_and_overrides(self):
        result = self.run_experiment(CONTAINER_CPU_LIMIT='3')
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        meta = json.loads((self.result / 'metadata.json').read_text())
        self.assertEqual(meta['runtimeMode'], 'native')
        self.assertEqual(meta['container']['cpuLimit'], '3')
        self.assertEqual(meta['load']['rate'], 0)
        self.assertEqual(meta['load']['seedOrders'], 0)
        self.assertEqual(meta['status'], 'completed')
        self.assertGreaterEqual(meta['startupSeconds'], 0)
        self.assertTrue((self.result / 'runtime-metrics.csv').exists())
        for name,args in self.calls():
            self.assertNotEqual(name, 'k6')
            self.assertNotIn('synthetic/runtime', ' '.join(args))
            self.assertNotIn('jcmd', ' '.join(args))
        self.assert_cleaned()
        self.assertNotEqual(self.run_experiment().returncode, 0)  # no overwriting previous results

    def test_low_load_and_k6_failure_preserved(self):
        result = self.run_experiment('low-load', 'work-hotspot-elastic.env', TEST_K6_EXIT='99')
        self.assertEqual(result.returncode, 99, result.stderr + result.stdout)
        meta = json.loads((self.result / 'metadata.json').read_text())
        self.assertEqual(meta['load']['rate'], 1)
        self.assertEqual(meta['memoryProfile'], 'elastic-heap')
        self.assertEqual(meta['status'], 'failed')
        self.assertEqual(compare.summarize(self.result)['p99_9_ms'], 36)
        self.assert_cleaned()

    def test_collector_failure_invalidates_run(self):
        result = self.run_experiment(TEST_COLLECTOR_FAIL='1', DURATION='3s')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(json.loads((self.result / 'metadata.json').read_text())['status'], 'failed')
        self.assertTrue(any('down' in args for name,args in self.calls() if name == 'docker'))

    def test_native_rejects_inherited_jvm_options(self):
        result = self.run_experiment(JAVA_TOOL_OPTIONS='-Xmx512m')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.result.exists())

    def test_termination_cleans_up(self):
        process = subprocess.Popen(['bash', 'scripts/run-experiment.sh', 'profiles/work-graalvm-native.env'],
            cwd=ROOT, env=dict(self.env, SCENARIO='idle', DURATION='30m'), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            for _ in range(100):
                if (self.result / 'runtime-metrics.csv').exists(): break
                if process.poll() is not None: self.fail(process.communicate())
                time.sleep(.05)
            process.send_signal(signal.SIGTERM)
            stdout,stderr = process.communicate(timeout=10)
            self.assertEqual(process.returncode, 143, stderr+stdout)
            self.assert_cleaned()
            self.assertEqual(json.loads((self.result / 'metadata.json').read_text())['exitCode'], 143)
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate()


if __name__ == '__main__':
    unittest.main()

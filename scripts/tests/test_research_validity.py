import copy
import csv
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPTS=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(SCRIPTS))
from workload import trace, java_hash

def module(name):
    spec=importlib.util.spec_from_file_location(name,SCRIPTS/(name+'.py'))
    obj=importlib.util.module_from_spec(spec);spec.loader.exec_module(obj);return obj

validator=module('validate-run')
report=module('phase-report')
collector=module('collect-container-metrics')

class TraceTests(unittest.TestCase):
    def test_deterministic_across_vu_partitioning(self):
        requests=trace(101,1000,42,'faults')['requests']
        for vus in (1,7,50):
            partitioned={i:requests[i] for vu in range(vus) for i in range(vu,len(requests),vus)}
            self.assertEqual(requests,[partitioned[i] for i in sorted(partitioned)])
        self.assertEqual(101,len({x['orderId'] for x in requests[:101]}))
        self.assertEqual(requests,trace(101,1000,42,'faults')['requests'])
        self.assertNotEqual(requests,trace(101,1000,43,'faults')['requests'])
        self.assertEqual(-1267032887,java_hash('order-1fns-dataexternal'))
    def test_normal_has_no_injected_failures(self):
        self.assertEqual({'processed'},{x['outcome'] for x in trace(1000,1000,20260925,'normal')['requests']})
    def test_raw_counter_math(self):
        raw='memory.current 314572800\nmemory.stat.inactive_file 104857600\nmemory.max 1073741824\ncpu.stat.usage_usec 2500000\n'
        row=collector.parse_cgroup(raw)
        self.assertEqual(209715200,row['memory_used_bytes'])
        self.assertEqual('',row['memory_peak_bytes'])
        # Unequal intervals: (100+200)/2*2 + (200+400)/2*8 = 2700; /10 = 270.
        self.assertEqual(270,report.weighted_mean([(0,100),(2,200),(10,400)]))
        self.assertEqual({'jvm_memory_used_bytes_heap':30},report.prometheus_values('jvm_memory_used_bytes{area="heap",id="a"} 10\njvm_memory_used_bytes{area="heap",id="b"} 20\n'))

class AdmissionTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.run=Path(self.temp.name)
        workload=trace(10,10,42,'normal');self.write('workload.json',workload)
        self.meta=dict(gitDirty=False,actualContainerLimits={'nanoCpus':2000000000,'memoryBytes':1073741824},schemaVersion=3,scenario='load',exitCode=0,prometheusSampling=True,trafficProfile='normal',expectedIterations=10,
            workloadSha256=hashlib.sha256((self.run/'workload.json').read_bytes()).hexdigest(),samplingIntervalSeconds=5,postIdleSeconds=20,
            load={'seedOrders':10,'durationSeconds':10},slo={'p95Ms':1500,'p99Ms':2000})
        self.write('metadata.json',self.meta)
        self.write('phases.json',[dict(name=x,epoch=i*10) for i,x in enumerate(['startup','preparation','load','drain','post-load-idle','finished'])])
        metrics={x:{'count':10} for x in ('http_reqs','iterations','business_requests','business_processed')}
        metrics.update(unexpected_results={'count':0},expected_faults={'count':0},http_req_failed={'value':0},business_latency={'p(95)':1200,'p(99)':1400})
        from collections import Counter
        metrics.update({'mode_'+k:{'count':v} for k,v in Counter(r['mode'] for r in workload['requests']).items()})
        self.summary={'metrics':metrics};self.write('k6-summary.json',self.summary)
        self.state={'bounds':dict(orders=10,historyMax=40,eventIdsMax=3,pendingEvents=0,waiting=0,retryable=0),'dedupCacheSize':30,
            'counters':dict(http_unexpected=0,scheduler_errors=0,consumer_errors=0,outbox_errors=0,events_enqueued=30,events_published=30,events_consumed=30,
                            outbox_deliveries=30,events_duplicate=0,scheduler_runs=4,http_processed=10,http_expected_fault=0,scheduler_expected_fault=0,mongo_expected_fault=0,synthetic_duplicates=0)}
        before=copy.deepcopy(self.state);before['counters']['http_processed']=0
        self.write('state-before.json',before);self.write('state-after.json',self.state);(self.run/'state').mkdir();self.write('state/10.json',self.state)
        self.write('container-final.json',[{'RestartCount':0,'State':{'Running':True,'OOMKilled':False}}])
        (self.run/'kafka-lag.txt').write_text('jvm-research-sandbox order.status.changed 0 20 20 0\njvm-research-sandbox business.event.occurred 0 10 10 0\n')
        rows=[]
        for phase,duration,offset in [('load',10,0),('post-load-idle',20,20)]:
            for i in range(0,duration+1,5):
                rows.append(dict(phase=phase,elapsed_seconds=i+offset,phase_elapsed_seconds=i,memory_used_bytes=100,memory_current_bytes=150,memory_peak_bytes=200,
                    cpu_usage_usec=(i+offset)*1000,cpu_throttled_usec=0,cpu_nr_throttled=0,cpu_nr_periods=i,oom=0,oom_kill=0,memory_swap_bytes=0))
        with (self.run/'runtime-metrics.csv').open('w') as f:
            writer=csv.DictWriter(f,fieldnames=rows[0]);writer.writeheader();writer.writerows(rows)
    def write(self,name,data): (self.run/name).write_text(json.dumps(data))
    def tearDown(self): self.temp.cleanup()
    def test_complete_evidence_is_eligible(self): self.assertEqual([],validator.validate(self.run)['reasons'])
    def test_single_boundary_tick_is_not_business_work(self):
        self.summary['metrics']['trace_boundary_skips']={'count':1}
        self.summary['metrics']['iterations']={'count':11}
        self.write('k6-summary.json',self.summary)
        self.assertTrue(validator.validate(self.run)['eligible'])

    def test_boundary_allowance_cannot_hide_incomplete_or_extra_work(self):
        for iterations, skips, requests in [(11,0,10),(10,1,10),(12,2,10),(11,1,9),(11,1,11)]:
            with self.subTest(iterations=iterations,skips=skips,requests=requests):
                summary=copy.deepcopy(self.summary)
                for name,value in [('iterations',iterations),('trace_boundary_skips',skips),('http_reqs',requests),('business_requests',requests)]:
                    summary['metrics'][name]={'count':value}
                self.write('k6-summary.json',summary)
                self.assertFalse(validator.validate(self.run)['eligible'])

    def test_independent_defects_reject_data(self):
        for change in ('http_error','dropped','lag','event_loss','scheduler','missing_memory'):
            with self.subTest(change=change):
                original={p:p.read_bytes() for p in self.run.iterdir() if p.is_file()}
                if change in ('http_error','dropped'):
                    summary=copy.deepcopy(self.summary);summary['metrics'][{'http_error':'http_req_failed','dropped':'dropped_iterations'}[change]]={'value':.1,'count':1};self.write('k6-summary.json',summary)
                if change=='lag': (self.run/'kafka-lag.txt').write_text('unavailable')
                if change in ('event_loss','scheduler'):
                    state=copy.deepcopy(self.state);state['counters']['events_consumed' if change=='event_loss' else 'scheduler_errors']=1;self.write('state-after.json',state)
                if change=='missing_memory': (self.run/'runtime-metrics.csv').unlink()
                self.assertFalse(validator.validate(self.run)['eligible'])
                for path,data in original.items():path.write_bytes(data)
    def test_incomplete_window_is_not_extrapolated(self):
        result=report.report(self.run)
        self.assertNotIn('1200-1800s',result['phases']['load']['windows'])
        self.assertEqual(100, result['phases']['load']['whole']['memory_used_bytes']['timeWeightedMean'])
        self.assertEqual(10000,result['phases']['load']['whole']['cpu_usage_usec_delta'])

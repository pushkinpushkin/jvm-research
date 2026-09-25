import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Trend } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const requests = new SharedArray('research trace', () => JSON.parse(open(__ENV.TRACE_FILE)).requests);
const count = new Counter('business_requests');
const boundary = new Counter('trace_boundary_skips');
const unexpected = new Counter('unexpected_results');
const processed = new Counter('business_processed');
const expectedFaults = new Counter('expected_faults');
const modes = Object.fromEntries(['ok', 'slow', 'long_delay', 'error', 'bad_response'].map(m => [m, new Counter(`mode_${m}`)]));
const latency = new Trend('business_latency', true);
export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)', 'p(99.9)'],
  scenarios: { steady_enterprise_flow: {
    executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 1),
    timeUnit: __ENV.RATE_TIME_UNIT || '1s', duration: __ENV.DURATION || '30m',
    preAllocatedVUs: Number(__ENV.PREALLOCATED_VUS || 50), maxVUs: Number(__ENV.MAX_VUS || 200),
    gracefulStop: '30s',
  }},
  thresholds: {
    trace_boundary_skips: ['count<=1'], http_req_failed: ['rate==0'], unexpected_results: ['count==0'], dropped_iterations: ['count==0'],
    business_latency: [`p(95)<${__ENV.SLO_P95_MS || 1500}`, `p(99)<${__ENV.SLO_P99_MS || 2000}`],
  },
};

// Seeding belongs to the runner's preparation phase, outside this k6 process.
export default function () {
  const index = exec.scenario.iterationInTest;
  // constant-arrival-rate can schedule a final tick exactly at duration.
  // Only that one index is a no-op; all planned requests remain mandatory.
  if (index === requests.length) { boundary.add(1); return; }
  boundary.add(0);
  const item = requests[index];
  if (!item) { unexpected.add(1); throw new Error(`Trace exhausted at ${index}`); }
  const response = http.post(`${baseUrl}/orders/${item.orderId}/process`, null,
    { tags: { name: 'POST /orders/:id/process', mode: item.mode, phase: 'load' }, timeout: '15s' });
  count.add(1); modes[item.mode].add(1); latency.add(response.timings.duration);
  let body = {};
  try { body = response.json(); } catch (_) { /* classified below */ }
  const valid = response.status === 200 && body.orderId === item.orderId && body.outcome === item.outcome
    && body.status === (item.outcome === 'processed' ? 'WAITING_EXTERNAL_STATUS' : 'FAILED');
  unexpected.add(valid ? 0 : 1);
  processed.add(valid && body.outcome === 'processed' ? 1 : 0);
  expectedFaults.add(valid && body.outcome === 'expected_fault' ? 1 : 0);
  check(response, { 'matches expected business result': () => valid });
}

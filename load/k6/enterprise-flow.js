import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const orderPool = Number(__ENV.ORDER_POOL || 10000);

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'p(99.9)'],

  scenarios: {
    steady_enterprise_flow: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 10),
      timeUnit: '1s',
      duration: __ENV.DURATION || '10m',
      preAllocatedVUs: Number(__ENV.PREALLOCATED_VUS || 50),
      maxVUs: Number(__ENV.MAX_VUS || 200),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.20'],
    http_req_duration: ['p(95)<2000'],
  },
};

export function setup() {
  const count = Number(__ENV.SEED_ORDERS || orderPool);
  const response = http.post(`${baseUrl}/orders/generate?count=${count}`);
  check(response, {
    'seed orders accepted': (r) => r.status >= 200 && r.status < 300,
  });
}

export default function () {
  const orderId = `order-${Math.floor(Math.random() * orderPool)}`;
  const response = http.post(`${baseUrl}/orders/${orderId}/process`);

  check(response, {
    'process returned 2xx': (r) => r.status >= 200 && r.status < 300,
    'process has status': (r) => Boolean(r.json('status')),
  });

  sleep(Number(__ENV.SLEEP_SECONDS || 0.1));
}

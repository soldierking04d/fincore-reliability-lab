import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    steady_settlement: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 100),
      timeUnit: '1s',
      duration: __ENV.DURATION || '60s',
      preAllocatedVUs: 20,
      maxVUs: 200,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(99)<500'],
  },
};

const baseUrl = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const payer = __ENV.PAYER_ACCOUNT_ID;
const payee = __ENV.PAYEE_ACCOUNT_ID;
const fee = __ENV.FEE_ACCOUNT_ID;

export function setup() {
  if (!payer || !payee || !fee) {
    throw new Error('PAYER_ACCOUNT_ID, PAYEE_ACCOUNT_ID and FEE_ACCOUNT_ID are required');
  }
}

export default function () {
  const key = `${__VU}-${__ITER}-${Date.now()}`;
  const response = http.post(`${baseUrl}/api/settlements`, JSON.stringify({
    messageId: `msg-${key}`,
    businessKey: `order-${key}`,
    payerAccountId: payer,
    payeeAccountId: payee,
    feeAccountId: fee,
    asset: 'USDT',
    amount: 1,
    fee: 0.001,
  }), { headers: { 'Content-Type': 'application/json' } });
  check(response, { accepted: (r) => r.status === 202 });
}


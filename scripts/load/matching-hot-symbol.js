import http from "k6/http";
import { check } from "k6";

const baseUrl = __ENV.FINCORE_BASE_URL || "http://127.0.0.1:8080";
const runId = __ENV.RUN_ID || "manual";
const symbol = `HOT${runId}-USDT`;
const totalOrders = Number(__ENV.ORDERS || 2000);

export const options = {
  scenarios: {
    hot_symbol_burst: {
      executor: "shared-iterations",
      exec: "placeOrder",
      vus: Number(__ENV.VUS || 50),
      iterations: totalOrders,
      maxDuration: "40s",
    },
    verify_book: {
      executor: "per-vu-iterations",
      exec: "verifyBook",
      vus: 1,
      iterations: 1,
      startTime: "42s",
      maxDuration: "10s",
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.001"],
    http_req_duration: ["p(95)<2000"],
    checks: ["rate==1"],
  },
};

export function placeOrder() {
  const id = `${runId}-${__VU}-${__ITER}`;
  const response = http.post(
    `${baseUrl}/api/matching/orders`,
    JSON.stringify({
      clientOrderId: `load-${id}`,
      userId: `seller-${id}`,
      symbol,
      side: "SELL",
      type: "LIMIT",
      price: 100,
      quantity: 1,
    }),
    { headers: { "Content-Type": "application/json" } },
  );
  check(response, {
    "订单成功接收 / order accepted": (r) => r.status === 201,
  });
}

export function verifyBook() {
  const response = http.get(
    `${baseUrl}/api/matching/books/${symbol}?depth=1`,
  );
  const body = response.status === 200 ? response.json() : {};
  const actual = body.asks?.[0]?.orderCount || 0;
  check(response, {
    "订单簿未丢单 / no order lost": (r) =>
      r.status === 200 && actual === totalOrders,
  });
}

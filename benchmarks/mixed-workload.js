import http from "k6/http";
import { check, sleep } from "k6";
import { Rate } from "k6/metrics";

const baseUrl = __ENV.BASE_URL || "http://127.0.0.1:8080";
const duration = __ENV.DURATION || "60s";
const overloaded = new Rate("fincore_overloaded");

export const options = {
  scenarios: {
    settlement_commands: {
      executor: "constant-arrival-rate",
      exec: "submitSettlement",
      rate: Number(__ENV.SETTLEMENT_RATE || 150),
      timeUnit: "1s",
      duration,
      preAllocatedVUs: 40,
      maxVUs: 300,
    },
    hot_symbol_matching: {
      executor: "constant-arrival-rate",
      exec: "placeOrder",
      rate: Number(__ENV.MATCHING_RATE || 100),
      timeUnit: "1s",
      duration,
      preAllocatedVUs: 30,
      maxVUs: 200,
    },
    market_reads: {
      executor: "constant-arrival-rate",
      exec: "readMarket",
      rate: Number(__ENV.READ_RATE || 100),
      timeUnit: "1s",
      duration,
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    fincore_overloaded: ["rate<0.01"],
    "http_req_duration{scenario:settlement_commands}": ["p(95)<250", "p(99)<800"],
    "http_req_duration{scenario:hot_symbol_matching}": ["p(95)<1000", "p(99)<3000"],
    "http_req_duration{scenario:market_reads}": ["p(95)<200", "p(99)<500"],
    checks: ["rate>0.99"],
  },
};

/** 创建独立资金账户，避免压测与已有实验数据相互污染。 */
export function setup() {
  const runId = `${Date.now()}`;
  const payer = createAccount(`perf-payer-${runId}`, "USER", 100000000);
  const payee = createAccount(`perf-payee-${runId}`, "USER", 0);
  const fee = createAccount(`perf-fee-${runId}`, "SYSTEM_FEE", 0);
  return { runId, payer, payee, fee, symbol: `PERF${runId}-USDT` };
}

/** 按固定到达率提交 Kafka 结算命令，业务键和消息键均保持唯一。 */
export function submitSettlement(data) {
  const key = `${data.runId}-${__VU}-${__ITER}`;
  const response = http.post(`${baseUrl}/api/settlements`, JSON.stringify({
    messageId: `perf-msg-${key}`,
    businessKey: `perf-order-${key}`,
    payerAccountId: data.payer,
    payeeAccountId: data.payee,
    feeAccountId: data.fee,
    asset: "USDT",
    amount: 1,
    fee: 0.001,
  }), jsonParams("settlement"));
  overloaded.add(response.status === 429 || response.status === 503);
  check(response, { "结算命令已接收": (result) => result.status === 202 });
}

/** 对单一热点交易对持续写入限价单，验证 Lane 背压和数据库跨实例锁。 */
export function placeOrder(data) {
  const key = `${data.runId}-${__VU}-${__ITER}`;
  const response = http.post(`${baseUrl}/api/matching/orders`, JSON.stringify({
    clientOrderId: `perf-match-${key}`,
    userId: `perf-user-${key}`,
    symbol: data.symbol,
    side: __ITER % 2 === 0 ? "SELL" : "BUY",
    type: "LIMIT",
    price: 100,
    quantity: 1,
  }), jsonParams("matching"));
  overloaded.add(response.status === 429 || response.status === 503);
  check(response, { "撮合请求成功或被明确背压": (result) =>
    result.status === 201 || result.status === 429 || result.status === 503 });
}

/** 并行读取盘口与最近成交，确保查询流量不会占用撮合写 Lane。 */
export function readMarket(data) {
  const responses = http.batch([
    ["GET", `${baseUrl}/api/matching/books/${data.symbol}?depth=20`, null,
      { tags: { endpoint: "book" } }],
    ["GET", `${baseUrl}/api/matching/trades/${data.symbol}?limit=50`, null,
      { tags: { endpoint: "trades" } }],
  ]);
  check(responses[0], { "盘口可查询": (result) => result.status === 200 });
  check(responses[1], { "成交可查询": (result) => result.status === 200 });
}

/** 输出可归档的机器可读汇总，便于比较不同线程和 GC 配置。 */
export function handleSummary(data) {
  return {
    "/reports/latest-k6-summary.json": JSON.stringify(data, null, 2),
    stdout: `FinCore mixed workload complete: ${duration}\n`,
  };
}

/** 创建一个测试账户并返回账户编号。 */
function createAccount(ownerId, accountType, openingBalance) {
  const response = http.post(`${baseUrl}/api/accounts`, JSON.stringify({
    ownerId,
    asset: "USDT",
    accountType,
    openingBalance,
  }), jsonParams("account-setup"));
  if (response.status !== 200 && response.status !== 201) {
    throw new Error(`cannot create performance account: ${response.status} ${response.body}`);
  }
  sleep(0.05);
  return response.json("accountId");
}

/** 生成带业务标签的 JSON 请求参数。 */
function jsonParams(operation) {
  const headers = { "Content-Type": "application/json" };
  if (__ENV.FINCORE_ADMIN_TOKEN) {
    headers["X-FinCore-Admin-Token"] = __ENV.FINCORE_ADMIN_TOKEN;
  }
  return {
    headers,
    tags: { operation },
  };
}

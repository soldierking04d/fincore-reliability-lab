import test from 'node:test';
import assert from 'node:assert/strict';
import { plan, runComparison, parseArgs, exactInteger } from './settlement-comparison.mjs';

// 这是实验工具的协议测试替身，不是 PostgreSQL / Kafka 性能证据。
function fixture({ unresolved = false, corrupt = false, unknownAck = false } = {}) {
  const accounts = new Map(); const orders = new Map(); const calls = [];
  let next = 0;
  const uuid = () => `00000000-0000-4000-8000-${String(++next).padStart(12, '0')}`;
  const response = (body, status = 200) => new Response(JSON.stringify(body), { status });
  function account(asset, type, opening = '0') {
    const a = { accountId: uuid(), asset, accountType: type, openingBalance: String(opening), balance: String(opening) };
    accounts.set(a.accountId, a); return a;
  }
  const fetchImpl = async (url, init) => {
    const u = new URL(url); const body = init.body ? JSON.parse(init.body) : null;
    calls.push({ path: u.pathname, method: init.method, body, redirect: init.redirect });
    if (u.pathname === '/api/fees/shards') {
      return response(Array.from({ length: Number(u.searchParams.get('count')) }, () => account(u.searchParams.get('asset'), 'SYSTEM_FEE_SHARD')));
    }
    if (u.pathname === '/api/fees/route') {
      const all = [...accounts.values()].filter(a => a.accountType === 'SYSTEM_FEE_SHARD');
      const index = Number(u.searchParams.get('businessKey').split('-').at(-1));
      return response(all[index % all.length]);
    }
    if (u.pathname === '/api/accounts' && init.method === 'POST') {
      return response(account(body.asset, body.accountType, body.openingBalance), 201);
    }
    if (u.pathname === '/api/settlements' && init.method === 'POST') {
      assert.equal(body.amount, '10'); assert.equal(body.fee, '1');
      orders.set(body.businessKey, body);
      if (!unresolved) {
        for (const [id, delta] of [[body.payerAccountId, -11n], [body.payeeAccountId, 10n], [body.feeAccountId, 1n]]) {
          const a = accounts.get(id); a.balance = String(BigInt(a.balance) + delta);
        }
      }
      return response({ businessKey: body.businessKey, status: unknownAck ? 'UNKNOWN' : 'ACCEPTED' }, unknownAck ? 503 : 202);
    }
    if (u.pathname.startsWith('/api/settlements/')) {
      const businessKey = decodeURIComponent(u.pathname.split('/').at(-1));
      return response({ businessKey, status: unresolved ? 'PROCESSING' : 'SUCCESS' });
    }
    if (u.pathname.endsWith('/ledger-summary')) {
      const a = accounts.get(u.pathname.split('/')[3]);
      return response({ account_id: a.accountId, opening_balance: a.openingBalance,
        balance: String(BigInt(a.balance) + (corrupt ? 1n : 0n)),
        expected_balance: a.balance, ledger_delta: String(BigInt(a.balance) - BigInt(a.openingBalance)) });
    }
    throw new Error(`unexpected fixture path: ${u.pathname}`);
  };
  let now = 0;
  return { calls, fetchImpl, clock: () => ++now, pause: async ms => { now += ms; } };
}
const options = { execute: true, confirmIsolated: true, baseUrl: 'http://127.0.0.1:18080', count: 8, concurrency: 3,
  scenario: 'shared-fee', deadlineMs: 10000, pollMs: 100 };

test('默认仅计划，不连接任何 HTTP，不创建账户', async () => {
  const f = fixture(); const result = await runComparison({}, f);
  assert.equal(result.status, 'PLAN_ONLY'); assert.equal(f.calls.length, 0);
  assert.equal(plan({ scenario: 'hot-payer', count: 8 }).payerCount, 1);
  assert.equal(plan({ scenario: 'sharded-fee', count: 8 }).feeShardCount, 16);
});

test('必须显式确认隔离；拒绝公网、DNS名称、代理路径、凭据和查询参数', async () => {
  const f = fixture();
  await assert.rejects(runComparison({ ...options, confirmIsolated: false }, f));
  for (const baseUrl of ['http://124.223.164.254:8080', 'http://localhost:8080', 'https://127.0.0.1:8080',
    'http://user:pass@127.0.0.1:8080', 'http://127.0.0.1:8080/api', 'http://127.0.0.1:8080/?x=1']) {
    await assert.rejects(runComparison({ ...options, baseUrl }, f), baseUrl);
  }
  assert.equal(f.calls.length, 0);
});

test('数量、并发、轮询和时限都有上限，未知参数拒绝', () => {
  for (const bad of [{ count: 0 }, { count: 1001 }, { concurrency: 33 }, { concurrency: 0 },
    { pollMs: 0 }, { deadlineMs: 301000 }, { scenario: 'production' }]) assert.throws(() => plan(bad));
  assert.throws(() => parseArgs(['--unknown']));
  assert.throws(() => parseArgs(['--count', '1e3']));
  assert.throws(() => parseArgs(['--run', '--run']));
});

for (const scenario of ['shared-fee', 'sharded-fee', 'hot-payer']) {
  test(`${scenario} 使用真实 API 形状与独立整数预期核验账户和账本`, async () => {
    const f = fixture(); const r = await runComparison({ ...options, scenario }, f);
    assert.equal(r.status, 'VERIFIED_LAB_RUN'); assert.equal(r.successCount, 8);
    assert.equal(r.reconciliation.status, 'MATCHED'); assert.equal(r.unresolvedCount, 0);
    assert.equal(r.plannedFeeDistribution.length, scenario === 'shared-fee' ? 1 : 16);
    assert.equal(r.plannedFeeDistribution.reduce((sum, row) => sum + row.plannedCommands, 0), 8);
    assert.equal(r.observedFinalLatencyMs.samples, 8);
    assert.equal(r.measurementModel, 'BOUNDED_CLOSED_LOOP_OBSERVATION_UPPER_BOUND');
    const submissions = f.calls.filter(c => c.path === '/api/settlements' && c.method === 'POST');
    assert.equal(submissions.length, 8); assert.equal(new Set(submissions.map(c => c.body.businessKey)).size, 8);
    assert.equal(new Set(submissions.map(c => c.body.payerAccountId)).size, scenario === 'hot-payer' ? 1 : 8);
    assert.ok(f.calls.every(c => c.redirect === 'error'));
    assert.equal(f.calls.some(c => c.path === '/api/fees/aggregate'), false);
  });
}

test('受理全部202但未观察到终态不能通过，不重发不核算不确定余额', async () => {
  const f = fixture({ unresolved: true });
  const r = await runComparison({ ...options, count: 2, deadlineMs: 1000 }, f);
  assert.equal(r.acceptedCount, 2); assert.equal(r.successCount, 0); assert.equal(r.status, 'INCONCLUSIVE');
  assert.equal(r.reconciliation.status, 'NOT_RUN_UNRESOLVED');
  assert.equal(f.calls.filter(c => c.path === '/api/settlements' && c.method === 'POST').length, 2);
});

test('Broker响应未知仍查询原业务键，成功结果不被误算为拒绝或重复发单', async () => {
  const f = fixture({ unknownAck: true }); const r = await runComparison(options, f);
  assert.equal(r.acceptedCount, 0); assert.equal(r.ackUnknownCount, 8); assert.equal(r.successCount, 8);
  assert.equal(r.status, 'VERIFIED_WITH_ACK_WARNINGS');
  assert.equal(f.calls.filter(c => c.path === '/api/settlements' && c.method === 'POST').length, 8);
});

test('账本或余额不匹配必须失败，不能用HTTP成功掩盖', async () => {
  const r = await runComparison(options, fixture({ corrupt: true }));
  assert.equal(r.status, 'FAILED_RECONCILIATION'); assert.equal(r.reconciliation.status, 'MISMATCH');
});

test('核账接口不可用属于证据未取得，不能误报资金差异', async () => {
  const f = fixture(); const original = f.fetchImpl;
  f.fetchImpl = (url, init) => url.endsWith('/ledger-summary')
    ? Promise.resolve(new Response('{}', { status: 503 })) : original(url, init);
  const r = await runComparison(options, f);
  assert.equal(r.status, 'INCONCLUSIVE_RECONCILIATION');
  assert.equal(r.reconciliation.mismatchedAccounts.length, 0);
  assert.ok(r.reconciliation.unverifiedAccounts.length > 0);
});

test('原始JSON中的极小资金差额不能被Number舍入成通过', async () => {
  const f = fixture(); const original = f.fetchImpl;
  f.fetchImpl = async (url, init) => {
    const response = await original(url, init);
    if (!url.endsWith('/ledger-summary')) return response;
    const raw = (await response.text()).replace(/"balance":"(\d+)"/, '"balance":$1.000000000000000001');
    return new Response(raw);
  };
  const r = await runComparison(options, f);
  assert.equal(r.status, 'FAILED_RECONCILIATION');
});

test('任何写入前输出本轮恢复标识，提交前输出稳定业务键清单', async () => {
  const f = fixture(); const progress = [];
  const r = await runComparison(options, { ...f, onProgress: event => progress.push({ ...event, priorCalls: f.calls.length }) });
  assert.equal(progress[0].phase, 'MANIFEST'); assert.equal(progress[0].priorCalls, 0);
  assert.equal(progress[0].runId, r.runId);
  assert.equal(progress.find(p => p.phase === 'PREPARED').businessKeys.length, options.count);
  assert.deepEqual(progress.find(p => p.phase === 'PREPARED').commands, r.commands);
  assert.equal(r.commands.length, options.count); assert.equal(r.databaseAudit.status, 'NOT_RUN');
});

test('金额比较只接受精确整数，拒绝浮点或不安全Number', () => {
  assert.equal(exactInteger('9007199254740993.000000000000000000'), 9007199254740993n);
  assert.equal(exactInteger('-11.000'), -11n);
  assert.equal(exactInteger('0E-18'), 0n);
  for (const value of [Number.MAX_SAFE_INTEGER + 1, 0.1, '0.1', '1e3', null]) assert.throws(() => exactInteger(value));
});

test('准备阶段协议错误保留现场且停止，不向结算发送请求', async () => {
  const f = fixture(); f.fetchImpl = async () => new Response('{}', { status: 500 });
  const r = await runComparison(options, f);
  assert.equal(r.status, 'SETUP_FAILED'); assert.ok(r.runId); assert.equal(r.successCount, 0);
});

test('准备阶段有全局时限，不因逐请求正常就无限累计等待', async () => {
  const f = fixture(); let elapsed = 0;
  const r = await runComparison(options, { ...f, clock: () => { elapsed += 31000; return elapsed; } });
  assert.equal(r.status, 'SETUP_FAILED');
  assert.equal(f.calls.filter(c => c.path === '/api/settlements').length, 0);
  assert.ok(f.calls.length < 3);
});

test('核账阶段超过总时限，剩余账户归未核实且不继续联网', async () => {
  const f = fixture(); const original = f.fetchImpl; let elapsed = 0; let expired = false; let checks = 0;
  const r = await runComparison(options, { ...f, clock: () => { elapsed += expired ? 61000 : 1; return elapsed; },
    fetchImpl: async (url, init) => {
      const response = await original(url, init);
      if (url.endsWith('/ledger-summary')) { expired = true; checks++; }
      return response;
    } });
  assert.equal(checks, 1); assert.equal(r.status, 'INCONCLUSIVE_RECONCILIATION');
  assert.equal(r.reconciliation.checkedAccounts, 1);
  assert.equal(r.reconciliation.unverifiedAccounts.length, r.accounts.length - 1);
  assert.deepEqual(r.reconciliation.mismatchedAccounts, []);
});

test('真实JSON整数和BigDecimal科学计数零均按精确金额解析', async () => {
  const f = fixture(); const original = f.fetchImpl;
  f.fetchImpl = async (url, init) => {
    const response = await original(url, init);
    if (!url.endsWith('/ledger-summary')) return response;
    const raw = (await response.text()).replace(/"(opening_balance|balance|expected_balance|ledger_delta)":"(-?\d+)"/g,
      (_match, key, value) => `"${key}":${value === '0' ? '0E-18' : value}`);
    return new Response(raw);
  };
  const r = await runComparison(options, f);
  assert.equal(r.status, 'VERIFIED_LAB_RUN'); assert.equal(r.reconciliation.status, 'MATCHED');
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { validateManifest, generateAuditSql, auditSnapshot } from './settlement-database-audit.mjs';

// Deliberately a protocol fixture, not a PostgreSQL/Kafka execution result.
export function auditFixture() {
  const runId = '11111111-2222-4333-8444-555555555555'; const asset = 'PF1111111122224333';
  const id = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
  const accounts = [1, 2, 3].map(n => ({ accountId: id(n), opening: n === 1 ? '9007199254740993' : '0' }));
  const commands = [0, 1].map(n => ({ businessKey: `perf-${runId}-${n}`, messageId: `msg-perf-${runId}-${n}`,
    asset, amount: '10', fee: '1', payerAccountId: id(1), payeeAccountId: id(2), feeAccountId: id(3) }));
  const report = { runId, asset, count: 2, commands, accounts,
    observations: commands.map(c => ({ businessKey: c.businessKey, status: 'SUCCESS' })) };
  const snapshot = { schemaVersion: 1, runId, asset, database: 'fincore_performance', user: 'fincore_audit',
    isolationMarker: 'synthetic-only', readOnly: 'on', isolation: 'repeatable read', capturedAt: '2026-09-21T00:00:00Z',
    accounts: accounts.map((a, n) => ({ account_id: a.accountId, asset, opening_balance: a.opening,
      balance: String(BigInt(a.opening) + [-22n, 20n, 2n][n]) })),
    orders: commands.map(c => ({ business_key: c.businessKey, message_id: c.messageId, asset, status: 'SUCCESS',
      payer_account_id: c.payerAccountId, payee_account_id: c.payeeAccountId, fee_account_id: c.feeAccountId,
      amount: '10.000000000000000000', fee: '1.000000000000000000' })),
    transactions: commands.map((c, n) => ({ transaction_id: id(n + 10), business_key: c.businessKey, asset, transaction_type: 'SETTLEMENT' })),
    entries: commands.flatMap((c, n) => [[c.payerAccountId, 'DEBIT', '11'], [c.payeeAccountId, 'CREDIT', '10'],
      [c.feeAccountId, 'CREDIT', '1']].map(([account_id, direction, amount], k) => ({ entry_id: id(30 + n * 3 + k),
      transaction_id: id(n + 10), account_id, direction, amount }))),
    inbox: commands.map(c => ({ message_id: c.messageId, message_type: 'SETTLEMENT_COMMAND',
      processed_at: '2026-09-21T00:00:00Z', payload: JSON.stringify(c) })),
    outbox: commands.map((c, n) => ({ event_id: id(20 + n), aggregate_id: c.businessKey,
      event_type: 'SETTLEMENT_SUCCEEDED', status: 'PUBLISHED', published_at: '2026-09-21T00:00:00Z',
      payload: JSON.stringify({ businessKey: c.businessKey, status: 'SUCCESS' }) })) };
  return { report, snapshot };
}
const codes = result => result.issues.map(i => i.code);

test('逐单三分录、借贷、Inbox、Outbox、全账户及大整数全部匹配才通过', () => {
  const { report, snapshot } = auditFixture(); const r = auditSnapshot(report, snapshot);
  assert.equal(r.status, 'VERIFIED_DATABASE_AUDIT'); assert.equal(r.successCount, 2);
  assert.deepEqual(r.issues, []); assert.deepEqual(r.pending, []);
});
test('缺失业务键不允许空集合真值通过', () => {
  const { report, snapshot } = auditFixture();
  for (const table of ['orders', 'transactions', 'entries', 'inbox', 'outbox']) snapshot[table] = [];
  snapshot.accounts.forEach(a => { a.balance = a.opening_balance; });
  const r = auditSnapshot(report, snapshot);
  assert.equal(r.status, 'INCONCLUSIVE_DATABASE_AUDIT'); assert.equal(r.pending.filter(p => p.code === 'MISSING_ORDER').length, 2);
});
test('重复且互相抵消的借贷分录仍失败，即使总净额和余额正确', () => {
  const { report, snapshot } = auditFixture(); const p = snapshot.entries[1];
  snapshot.entries.push({ ...p, entry_id: 'duplicate-credit' }, { ...p, entry_id: 'offsetting-debit', direction: 'DEBIT' });
  const r = auditSnapshot(report, snapshot);
  assert.equal(r.status, 'FAILED_DATABASE_AUDIT'); assert.ok(codes(r).includes('POSTING_NOT_EXACTLY_ONCE'));
  assert.equal(codes(r).includes('ACCOUNT_OR_LEDGER_DELTA_MISMATCH'), false);
});
test('业务事务头及Outbox重复不能通过', () => {
  const { report, snapshot } = auditFixture();
  snapshot.transactions.push({ ...snapshot.transactions[0], transaction_id: 'second-transaction' });
  snapshot.outbox.push({ ...snapshot.outbox[0], event_id: 'second-event' });
  const r = auditSnapshot(report, snapshot);
  assert.ok(codes(r).includes('TRANSACTION_NOT_UNIQUE')); assert.ok(codes(r).includes('OUTBOX_NOT_UNIQUE'));
});
test('未知HTTP终态只能由同键权威数据库终态消解，不重发', () => {
  const { report, snapshot } = auditFixture(); report.observations[0].status = 'UNKNOWN';
  const resolved = auditSnapshot(report, snapshot);
  assert.equal(resolved.status, 'VERIFIED_DATABASE_AUDIT');
  assert.deepEqual(resolved.resolvedUnknownBusinessKeys, [report.commands[0].businessKey]);
  snapshot.orders[0].status = 'PROCESSING';
  assert.notEqual(auditSnapshot(report, snapshot).status, 'VERIFIED_DATABASE_AUDIT');
});
test('命令清单包含从未提交的键，漏单不能被成功子集掩盖', () => {
  const { report, snapshot } = auditFixture(); report.observations.pop();
  const key = report.commands[1].businessKey;
  snapshot.orders = snapshot.orders.filter(o => o.business_key !== key);
  const r = auditSnapshot(report, snapshot);
  assert.notEqual(r.status, 'VERIFIED_DATABASE_AUDIT'); assert.ok(r.pending.some(p => p.key === key));
});
test('Outbox必须唯一已发布并有时间戳，后续只读快照可验证收敛', () => {
  const { report, snapshot } = auditFixture(); snapshot.outbox[0].status = 'PROCESSING'; snapshot.outbox[0].published_at = null;
  const r = auditSnapshot(report, snapshot);
  assert.equal(r.status, 'INCONCLUSIVE_DATABASE_AUDIT'); assert.equal(r.pending[0].code, 'OUTBOX_NOT_PUBLISHED');
  snapshot.outbox[0].status = 'PUBLISHED'; snapshot.outbox[0].published_at = snapshot.capturedAt;
  assert.equal(auditSnapshot(report, snapshot).status, 'VERIFIED_DATABASE_AUDIT');
});
test('出账金额和借贷平衡偏差均失败，包括1e-18差异', () => {
  for (const amount of ['12', '11.000000000000000001']) {
    const { report, snapshot } = auditFixture(); snapshot.entries[0].amount = amount;
    const r = auditSnapshot(report, snapshot);
    assert.equal(r.status, 'FAILED_DATABASE_AUDIT'); assert.ok(codes(r).includes('UNBALANCED_JOURNAL'));
    assert.ok(codes(r).includes('POSTING_NOT_EXACTLY_ONCE'));
  }
});
test('余额被舍入或外部订单混入本轮资产均拒绝', () => {
  const { report, snapshot } = auditFixture(); snapshot.accounts[0].balance = Number(snapshot.accounts[0].balance);
  snapshot.orders.push({ ...snapshot.orders[0], business_key: 'unexpected-business-key' });
  const r = auditSnapshot(report, snapshot);
  assert.ok(codes(r).includes('ACCOUNT_OR_LEDGER_DELTA_MISMATCH')); assert.ok(codes(r).includes('UNEXPECTED_SCOPED_ROW'));
});
test('错误账户映射、Inbox缺失、Outbox载荷错键分别阻止通过', () => {
  for (const mutate of [s => { s.orders[0].payer_account_id = s.orders[0].payee_account_id; },
    s => { s.inbox.pop(); }, s => { s.outbox[0].payload = '{}'; }]) {
    const { report, snapshot } = auditFixture(); mutate(snapshot);
    assert.equal(auditSnapshot(report, snapshot).status, 'FAILED_DATABASE_AUDIT');
  }
});
test('清单必须全集、唯一、只含本轮合成业务键，不接受SQL注入或生产资产', () => {
  for (const mutate of [r => { r.commands.pop(); }, r => { r.commands[1] = r.commands[0]; },
    r => { r.asset = 'USDT'; }, r => { r.runId = "x'; DROP TABLE account;--"; },
    r => { r.accounts.push(r.accounts[0]); }, r => { r.observations.push(r.observations[0]); }]) {
    const { report } = auditFixture(); mutate(report); assert.throws(() => validateManifest(report));
  }
});
test('SQL包含业务键全集，只读事务和身份约束，不含任何DML或行锁', () => {
  const { report } = auditFixture(); const sql = generateAuditSql(report);
  for (const c of report.commands) assert.ok(sql.includes(c.businessKey));
  assert.match(sql, /REPEATABLE READ READ ONLY/); assert.match(sql, /current_user = 'fincore_audit'/);
  assert.match(sql, /current_database\(\) = 'fincore_performance'/);
  assert.match(sql, /statement_timeout = '15s'/); assert.match(sql, /ROLLBACK;/);
  assert.match(sql, /i.payload IS JSON OBJECT/); assert.match(sql, /i.payload::jsonb->>'businessKey'/);
  assert.match(sql, /i.payload::jsonb->>'asset'/);
  assert.doesNotMatch(sql, /\b(INSERT|UPDATE|DELETE|TRUNCATE|CREATE|DROP|COPY)\b/i);
});
test('同业务载荷的额外Inbox别名消息不能从全集核验漏掉', () => {
  const { report, snapshot } = auditFixture();
  snapshot.inbox.push({ ...snapshot.inbox[0], message_id: 'alias-outside-run-prefix' });
  const r = auditSnapshot(report, snapshot);
  assert.equal(r.status, 'FAILED_DATABASE_AUDIT'); assert.ok(codes(r).includes('UNEXPECTED_SCOPED_ROW'));
});
test('Inbox载荷数字保留原始精度，拒绝被舍入的1e-18差额', () => {
  const { report, snapshot } = auditFixture();
  snapshot.inbox[0].payload = snapshot.inbox[0].payload.replace('"amount":"10"', '"amount":10.000000000000000001');
  assert.ok(codes(auditSnapshot(report, snapshot)).includes('INBOX_PAYLOAD_MISMATCH'));
});
test('数据库身份与只读隔离字段错误不接受证据', () => {
  for (const [field, value] of [['database', 'production'], ['user', 'postgres'], ['readOnly', 'off'],
    ['runId', 'different'], ['isolationMarker', ''], ['isolation', 'read committed']]) {
    const { report, snapshot } = auditFixture(); snapshot[field] = value; assert.throws(() => auditSnapshot(report, snapshot));
  }
});
test('默认CLI只展示离线说明；禁止生产连接串选项', () => {
  const cli = new URL('./settlement-database-audit.mjs', import.meta.url);
  const help = spawnSync(process.execPath, [cli.pathname], { encoding: 'utf8' });
  assert.equal(help.status, 0); assert.match(help.stdout, /默认离线/);
  assert.equal(spawnSync(process.execPath, [cli.pathname, '--database-url', 'postgres://production'], { encoding: 'utf8' }).status, 1);
});
test('隔离Compose启用内网与只读审计初始化，无宿主DB或Broker端口', () => {
  const compose = readFileSync(new URL('../../infra/performance/compose.isolated.yml', import.meta.url), 'utf8');
  assert.match(compose, /internal: true/); assert.match(compose, /01-audit-role.sql:ro/);
  assert.equal((compose.match(/ports:/g) ?? []).length, 1); assert.match(compose, /127.0.0.1:18080:8080/);
});

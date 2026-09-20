import { readFile, stat } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import { exactInteger } from './settlement-comparison.mjs';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const requireValue = (ok, message) => { if (!ok) throw new Error(message); };
const TABLES = ['accounts', 'orders', 'transactions', 'entries', 'inbox', 'outbox'];

/** Only this tool's complete synthetic run manifest is accepted; no SQL, URL or credentials are inputs. */
export function validateManifest(report) {
  requireValue(report && UUID.test(report.runId), '缺少本轮 runId');
  requireValue(report.asset === `PF${report.runId.replaceAll('-', '').slice(0, 16)}`, '合成资产不属于本轮');
  requireValue(Number.isSafeInteger(report.count) && report.count >= 1 && report.count <= 1000, '本轮 count 无效');
  requireValue(Array.isArray(report.commands) && report.commands.length === report.count, '命令全集不完整');
  requireValue(Array.isArray(report.accounts) && report.accounts.length > 0 && report.accounts.length <= 2016, '账户全集无效');
  const accounts = new Map();
  for (const a of report.accounts) {
    requireValue(a && UUID.test(a.accountId) && !accounts.has(a.accountId), '账户编号无效或重复');
    requireValue(typeof a.opening === 'string' && exactInteger(a.opening) >= 0n, '期初余额无效');
    accounts.set(a.accountId, { accountId: a.accountId, opening: String(exactInteger(a.opening)) });
  }
  const commands = report.commands.map((c, i) => {
    const businessKey = `perf-${report.runId}-${i}`;
    requireValue(c && c.businessKey === businessKey && c.messageId === `msg-${businessKey}`
      && c.asset === report.asset && c.amount === '10' && c.fee === '1', '命令身份或合成金额错误');
    const ids = [c.payerAccountId, c.payeeAccountId, c.feeAccountId];
    requireValue(new Set(ids).size === 3 && ids.every(id => accounts.has(id)), '命令账户映射错误');
    return { businessKey, messageId: c.messageId, asset: c.asset, amount: c.amount, fee: c.fee,
      payerAccountId: c.payerAccountId, payeeAccountId: c.payeeAccountId, feeAccountId: c.feeAccountId };
  });
  const observations = report.observations ?? [];
  requireValue(Array.isArray(observations) && observations.length <= commands.length, '观察集合无效');
  const seen = new Set(); const expected = new Set(commands.map(c => c.businessKey));
  for (const o of observations) {
    requireValue(o && expected.has(o.businessKey) && !seen.has(o.businessKey)
      && ['SUCCESS', 'FAILED', 'UNKNOWN'].includes(o.status), '观察业务键无效或重复');
    seen.add(o.businessKey);
  }
  return { runId: report.runId, asset: report.asset, count: report.count, commands,
    accounts: [...accounts.values()], observations: observations.map(o => ({ businessKey: o.businessKey, status: o.status })) };
}

/** Generates SQL only. It never connects, creates files, replays commands or updates database state. */
export function generateAuditSql(report) {
  const m = validateManifest(report);
  // Every interpolated field has been validated/canonicalized; payload cannot contain the SQL delimiter.
  const manifest = JSON.stringify({ runId: m.runId, asset: m.asset, accounts: m.accounts,
    businessKeys: m.commands.map(c => c.businessKey) });
  return `-- Synthetic settlement audit: run ${m.runId}; source commands ${m.count}.
-- Execute only in the dedicated NEW Compose project; output is one JSON snapshot.
-- PUBLISHED means the publisher recorded a broker acknowledgement, not downstream exactly-once consumption.
\\set ON_ERROR_STOP on
\\set QUIET 1
\\pset format unaligned
\\pset tuples_only on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '15s';
SET LOCAL lock_timeout = '2s';
SET LOCAL search_path = pg_catalog, public;
SELECT 1 / CASE WHEN current_database() = 'fincore_performance'
  AND current_user = 'fincore_audit'
  AND current_setting('fincore.isolated_performance', true) = 'synthetic-only'
  AND current_setting('transaction_read_only') = 'on'
  THEN 1 ELSE 0 END AS isolation_guard \\gset
WITH manifest AS (SELECT $audit_manifest$${manifest}$audit_manifest$::jsonb AS value),
scope AS (SELECT value->>'runId' AS run_id, value->>'asset' AS asset,
  'perf-' || (value->>'runId') || '-' AS prefix FROM manifest),
expected_accounts AS (
  SELECT (a->>'accountId')::uuid AS account_id
  FROM manifest, jsonb_array_elements(value->'accounts') a
),
run_accounts AS MATERIALIZED (
  SELECT a.* FROM public.account a, scope s
  WHERE a.asset=s.asset OR a.account_id IN (SELECT account_id FROM expected_accounts)
),
run_orders AS MATERIALIZED (
  SELECT o.* FROM public.settlement_order o, scope s
  WHERE o.asset=s.asset OR starts_with(o.business_key, s.prefix)
    OR o.payer_account_id IN (SELECT account_id FROM run_accounts)
    OR o.payee_account_id IN (SELECT account_id FROM run_accounts)
    OR o.fee_account_id IN (SELECT account_id FROM run_accounts)
),
run_transactions AS MATERIALIZED (
  SELECT t.* FROM public.ledger_transaction t, scope s
  WHERE t.asset=s.asset OR starts_with(t.business_key, s.prefix)
    OR t.business_key IN (SELECT business_key FROM run_orders)
    OR t.transaction_id IN (SELECT e.transaction_id FROM public.ledger_entry e
      WHERE e.account_id IN (SELECT account_id FROM run_accounts))
),
run_entries AS MATERIALIZED (
  SELECT e.* FROM public.ledger_entry e
  WHERE e.transaction_id IN (SELECT transaction_id FROM run_transactions)
    OR e.account_id IN (SELECT account_id FROM run_accounts)
),
run_inbox AS MATERIALIZED (
  SELECT i.* FROM public.inbox_message i, scope s
  WHERE starts_with(i.message_id, 'msg-' || s.prefix)
    OR i.message_id IN (SELECT message_id FROM run_orders)
    -- A replay can use an unrelated messageId; scope by its business payload as well.
    -- Malformed unrelated payloads must not abort the synthetic run's read-only snapshot.
    OR CASE WHEN i.payload IS JSON OBJECT THEN
      (i.payload::jsonb->>'asset') = s.asset
      OR starts_with(i.payload::jsonb->>'businessKey', s.prefix)
      OR (i.payload::jsonb->>'businessKey') IN (SELECT business_key FROM run_orders)
      ELSE false END
),
run_outbox AS MATERIALIZED (
  SELECT o.* FROM public.outbox_event o, scope s
  WHERE starts_with(o.aggregate_id, s.prefix)
    OR o.aggregate_id IN (SELECT business_key FROM run_orders)
    OR o.aggregate_id IN (SELECT business_key FROM run_transactions)
)
SELECT jsonb_build_object(
  'schemaVersion', 1, 'runId', s.run_id, 'asset', s.asset,
  'database', current_database(), 'user', current_user,
  'isolationMarker', current_setting('fincore.isolated_performance'),
  'readOnly', current_setting('transaction_read_only'),
  'isolation', current_setting('transaction_isolation'), 'capturedAt', clock_timestamp(),
  'accounts', COALESCE((SELECT jsonb_agg(jsonb_build_object(
    'account_id', account_id, 'asset', asset, 'opening_balance', opening_balance::text,
    'balance', balance::text)) FROM run_accounts), '[]'::jsonb),
  'orders', COALESCE((SELECT jsonb_agg(jsonb_build_object(
    'business_key', business_key, 'message_id', message_id, 'asset', asset, 'status', status,
    'payer_account_id', payer_account_id, 'payee_account_id', payee_account_id,
    'fee_account_id', fee_account_id, 'amount', amount::text, 'fee', fee::text))
    FROM run_orders), '[]'::jsonb),
  'transactions', COALESCE((SELECT jsonb_agg(jsonb_build_object(
    'transaction_id', transaction_id, 'business_key', business_key,
    'transaction_type', transaction_type, 'asset', asset)) FROM run_transactions), '[]'::jsonb),
  'entries', COALESCE((SELECT jsonb_agg(jsonb_build_object(
    'entry_id', entry_id, 'transaction_id', transaction_id, 'account_id', account_id,
    'direction', direction, 'amount', amount::text)) FROM run_entries), '[]'::jsonb),
  'inbox', COALESCE((SELECT jsonb_agg(jsonb_build_object(
    'message_id', message_id, 'message_type', message_type, 'processed_at', processed_at, 'payload', payload))
    FROM run_inbox), '[]'::jsonb),
  'outbox', COALESCE((SELECT jsonb_agg(jsonb_build_object(
    'event_id', event_id, 'aggregate_id', aggregate_id, 'event_type', event_type,
    'status', status, 'published_at', published_at, 'payload', payload)) FROM run_outbox), '[]'::jsonb)
) FROM scope s;
ROLLBACK;
`;
}

/** No reliance on aggregate net zero: each expected posting must appear exactly once. */
export function auditSnapshot(report, snapshot) {
  const m = validateManifest(report);
  requireValue(snapshot && snapshot.schemaVersion === 1 && snapshot.runId === m.runId && snapshot.asset === m.asset,
    '快照身份不匹配');
  requireValue(snapshot.database === 'fincore_performance' && snapshot.user === 'fincore_audit'
    && snapshot.isolationMarker === 'synthetic-only' && snapshot.readOnly === 'on'
    && snapshot.isolation === 'repeatable read', '快照缺少独立实验库只读身份');
  for (const name of TABLES) requireValue(Array.isArray(snapshot[name]) && snapshot[name].length <= 20000
    && snapshot[name].every(row => row && typeof row === 'object'), `快照 ${name} 无效`);
  const issues = []; const pending = []; const resolvedUnknown = [];
  const fail = (code, key) => issues.push({ code, key });
  const wait = (code, key) => pending.push({ code, key });
  const keys = new Set(m.commands.map(c => c.businessKey));
  const messageIds = new Set(m.commands.map(c => c.messageId));
  const accounts = new Map(m.accounts.map(a => [a.accountId, a]));
  const observations = new Map(m.observations.map(o => [o.businessKey, o.status]));
  const delta = new Map(m.accounts.map(a => [a.accountId, 0n]));
  const postedDelta = new Map(m.accounts.map(a => [a.accountId, 0n]));
  const group = (rows, field) => {
    const result = new Map();
    for (const row of rows) { const list = result.get(row[field]) ?? []; list.push(row); result.set(row[field], list); }
    return result;
  };
  const orders = group(snapshot.orders, 'business_key');
  const transactions = group(snapshot.transactions, 'business_key');
  const entries = group(snapshot.entries, 'transaction_id');
  const inbox = group(snapshot.inbox, 'message_id');
  const outbox = group(snapshot.outbox, 'aggregate_id');
  const dbAccounts = group(snapshot.accounts, 'account_id');
  // Database constraints are necessary but not substituted for observing the complete returned sets.
  for (const [table, field] of [['accounts', 'account_id'], ['orders', 'business_key'],
    ['transactions', 'transaction_id'], ['entries', 'entry_id'], ['inbox', 'message_id'], ['outbox', 'event_id']]) {
    for (const [key, rows] of group(snapshot[table], field)) {
      if (typeof key !== 'string' || rows.length !== 1) fail('DUPLICATE_OR_INVALID_ROW_ID', `${table}:${key}`);
    }
  }
  for (const [table, field, allowed] of [['orders', 'business_key', keys], ['transactions', 'business_key', keys],
    ['outbox', 'aggregate_id', keys], ['inbox', 'message_id', messageIds], ['accounts', 'account_id', new Set(accounts.keys())]]) {
    for (const row of snapshot[table]) if (!allowed.has(row[field])) fail('UNEXPECTED_SCOPED_ROW', `${table}:${row[field]}`);
  }
  const transactionIds = new Set(snapshot.transactions.map(t => t.transaction_id));
  for (const e of snapshot.entries) {
    if (!transactionIds.has(e.transaction_id) || !accounts.has(e.account_id)) fail('UNEXPECTED_ENTRY_SCOPE', e.entry_id);
    try {
      const amount = exactInteger(e.amount);
      requireValue(typeof e.amount === 'string' && amount > 0n && ['DEBIT', 'CREDIT'].includes(e.direction), '分录金额无效');
      if (postedDelta.has(e.account_id)) postedDelta.set(e.account_id,
        postedDelta.get(e.account_id) + (e.direction === 'CREDIT' ? amount : -amount));
    } catch { fail('INVALID_ENTRY_AMOUNT_OR_DIRECTION', e.entry_id); }
  }
  let successCount = 0;
  for (const c of m.commands) {
    const key = c.businessKey; const os = orders.get(key) ?? []; const ts = transactions.get(key) ?? [];
    const bs = outbox.get(key) ?? []; const ins = inbox.get(c.messageId) ?? [];
    if (os.length === 0) { wait('MISSING_ORDER', key); continue; }
    if (os.length !== 1) { fail('ORDER_NOT_UNIQUE', key); continue; }
    const o = os[0];
    try {
      requireValue(o.message_id === c.messageId && o.asset === c.asset && o.payer_account_id === c.payerAccountId
        && o.payee_account_id === c.payeeAccountId && o.fee_account_id === c.feeAccountId
        && typeof o.amount === 'string' && typeof o.fee === 'string'
        && exactInteger(o.amount) === 10n && exactInteger(o.fee) === 1n, '订单不匹配');
    } catch { fail('ORDER_COMMAND_MISMATCH', key); }
    if (!['SUCCESS', 'FAILED'].includes(o.status)) { wait('ORDER_NOT_FINAL', key); continue; }
    if (observations.has(key) && observations.get(key) !== 'UNKNOWN' && observations.get(key) !== o.status) {
      fail('TERMINAL_STATUS_CHANGED', key);
    }
    if (ins.length !== 1 || ins[0].message_type !== 'SETTLEMENT_COMMAND' || !ins[0].processed_at) {
      fail('INBOX_NOT_PROCESSED_UNIQUE', key);
    }
    for (const received of ins) {
      try {
        const p = JSON.parse(received.payload, (_key, value, context) => typeof value === 'number' ? context?.source : value);
        requireValue(p.businessKey === key && p.messageId === c.messageId && p.asset === m.asset
          && p.payerAccountId === c.payerAccountId && p.payeeAccountId === c.payeeAccountId
          && p.feeAccountId === c.feeAccountId && exactInteger(p.amount) === 10n && exactInteger(p.fee) === 1n,
        'Inbox命令载荷不匹配');
      } catch { fail('INBOX_PAYLOAD_MISMATCH', key); }
    }
    if (o.status === 'FAILED') {
      wait('BUSINESS_FAILED', key);
      if (ts.length || bs.length) fail('FAILED_ORDER_HAS_FINANCIAL_EFFECTS', key);
      continue;
    }
    successCount++;
    if (observations.get(key) === 'UNKNOWN') resolvedUnknown.push(key);
    for (const [id, value] of [[c.payerAccountId, -11n], [c.payeeAccountId, 10n], [c.feeAccountId, 1n]]) {
      delta.set(id, delta.get(id) + value);
    }
    if (ts.length !== 1) fail('TRANSACTION_NOT_UNIQUE', key);
    const postings = ts.flatMap(t => entries.get(t.transaction_id) ?? []);
    if (ts.some(t => t.asset !== c.asset || t.transaction_type !== 'SETTLEMENT')) fail('TRANSACTION_IDENTITY', key);
    const expectedPostings = [[c.payerAccountId, 'DEBIT', 11n], [c.payeeAccountId, 'CREDIT', 10n], [c.feeAccountId, 'CREDIT', 1n]];
    if (postings.length !== 3) fail('ENTRY_COUNT', key);
    for (const [id, direction, amount] of expectedPostings) {
      const matching = postings.filter(p => {
        try { return p.account_id === id && p.direction === direction && exactInteger(p.amount) === amount; }
        catch { return false; }
      });
      if (matching.length !== 1) fail('POSTING_NOT_EXACTLY_ONCE', `${key}:${direction}:${id}`);
    }
    try {
      const signed = postings.reduce((sum, p) => sum + (p.direction === 'CREDIT' ? 1n : -1n) * exactInteger(p.amount), 0n);
      if (signed !== 0n) fail('UNBALANCED_JOURNAL', key);
    } catch { fail('UNBALANCED_JOURNAL', key); }
    if (bs.length !== 1) fail('OUTBOX_NOT_UNIQUE', key);
    for (const b of bs) {
      let payload;
      try { payload = JSON.parse(b.payload); } catch { /* Mismatch below; never execute payload. */ }
      if (b.event_type !== 'SETTLEMENT_SUCCEEDED' || payload?.businessKey !== key || payload?.status !== 'SUCCESS') {
        fail('OUTBOX_PAYLOAD_MISMATCH', key);
      }
      if (b.status !== 'PUBLISHED' || !b.published_at) wait('OUTBOX_NOT_PUBLISHED', key);
    }
  }
  for (const a of m.accounts) {
    const rows = dbAccounts.get(a.accountId) ?? [];
    if (rows.length !== 1) { fail('ACCOUNT_NOT_UNIQUE_OR_MISSING', a.accountId); continue; }
    try {
      const row = rows[0]; const opening = exactInteger(a.opening);
      requireValue(row.asset === m.asset && typeof row.opening_balance === 'string' && typeof row.balance === 'string'
        && exactInteger(row.opening_balance) === opening && exactInteger(row.balance) === opening + delta.get(a.accountId)
        && postedDelta.get(a.accountId) === delta.get(a.accountId), '账户或账本差异');
    } catch { fail('ACCOUNT_OR_LEDGER_DELTA_MISMATCH', a.accountId); }
  }
  return { status: issues.length ? 'FAILED_DATABASE_AUDIT' : pending.length ? 'INCONCLUSIVE_DATABASE_AUDIT' : 'VERIFIED_DATABASE_AUDIT',
    runId: m.runId, asset: m.asset, expectedCommands: m.count, successCount, issues, pending,
    resolvedUnknownBusinessKeys: resolvedUnknown, capturedAt: snapshot.capturedAt ?? null,
    boundary: '只证明此只读数据库快照覆盖本轮合成业务键与分录；PUBLISHED 不证明下游恰好消费一次，不构成性能或生产容量证据。' };
}

async function jsonFile(path) {
  requireValue((await stat(path)).size <= 16 * 1024 * 1024, '输入超过16MiB上限');
  return JSON.parse(await readFile(path, 'utf8'));
}
const HELP = `只读数据库审计（默认离线；不联网、不写文件、不接受连接串/密钥）
node scripts/performance/settlement-database-audit.mjs --report run.json --sql
  仅向 stdout 输出只读 SQL。也接受保留的 PREPARED JSON 清单，必须含完整 commands/accounts/count。
  在专用 Compose 项目以 fincore_audit 执行 SQL，保存一份 snapshot.json，再运行：
node scripts/performance/settlement-database-audit.mjs --report run.json --snapshot snapshot.json
  只读取本地文件。退出码：通过 0；审计差异/未收敛 2；参数或证据无效 1。
  Outbox 未发布时可稍后重取只读快照；禁止重发结算命令，禁止指向生产库。`;
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const args = process.argv.slice(2); const values = {}; const seen = new Set();
    for (let i = 0; i < args.length; i++) {
      const flag = args[i]; requireValue(!seen.has(flag), '重复参数'); seen.add(flag);
      if (['--sql', '--help'].includes(flag)) values[flag] = true;
      else {
        requireValue(['--report', '--snapshot'].includes(flag) && args[i + 1] && !args[i + 1].startsWith('--'), '参数无效');
        values[flag] = args[++i];
      }
    }
    if (args.length === 0 || values['--help']) console.log(HELP);
    else {
      requireValue(values['--report'] && Boolean(values['--sql']) !== Boolean(values['--snapshot']), '选择SQL生成或快照审计');
      const report = await jsonFile(values['--report']);
      if (values['--sql']) process.stdout.write(generateAuditSql(report));
      else {
        const result = auditSnapshot(report, await jsonFile(values['--snapshot']));
        console.log(JSON.stringify(result, null, 2));
        if (result.status !== 'VERIFIED_DATABASE_AUDIT') process.exitCode = 2;
      }
    }
  } catch { console.error('审计参数或证据无效；请查看 --help。未联网、未重发、未自动修改资金。'); process.exitCode = 1; }
}

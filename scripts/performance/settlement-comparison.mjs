import { randomUUID } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { pathToFileURL } from 'node:url';

const SCENARIOS = ['shared-fee', 'sharded-fee', 'hot-payer'];
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const requireValue = (condition, message) => { if (!condition) throw new Error(message); };
function integer(value, min, max, label) {
  requireValue(Number.isSafeInteger(value) && value >= min && value <= max, `${label} 必须在 ${min}..${max}`);
  return value;
}

/** 默认只输出计划。显式执行也只能访问字面回环地址；回环地址不是隔离环境的身份证明。 */
export function plan(input = {}) {
  const scenario = input.scenario ?? 'shared-fee';
  requireValue(SCENARIOS.includes(scenario), '不支持的场景');
  const count = integer(input.count ?? 64, 1, 1000, 'count');
  const concurrency = integer(input.concurrency ?? 4, 1, 32, 'concurrency');
  const deadlineMs = integer(input.deadlineMs ?? 60000, 1000, 300000, 'deadlineMs');
  const pollMs = integer(input.pollMs ?? 200, 50, 2000, 'pollMs');
  const url = new URL(input.baseUrl ?? 'http://127.0.0.1:18080');
  requireValue(url.protocol === 'http:' && url.hostname === '127.0.0.1' && url.port !== ''
    && Number(url.port) >= 1024 && !url.username && !url.password && !url.search && !url.hash && url.pathname === '/',
  '仅允许 http://127.0.0.1:显式端口；禁止公网、域名、凭据、路径和查询参数');
  requireValue(input.execute !== true || input.confirmIsolated === true, '实际执行必须带 --confirm-isolated，确认独立实验库且没有真实账户');
  return { status: 'PLAN_ONLY', scenario, count, concurrency, deadlineMs, pollMs, baseUrl: url.origin,
    execute: input.execute === true, payerCount: scenario === 'hot-payer' ? 1 : count,
    payeeCount: count, feeShardCount: scenario === 'shared-fee' ? 1 : 16,
    amount: '10', fee: '1', measurementModel: 'BOUNDED_CLOSED_LOOP_OBSERVATION_UPPER_BOUND',
    notice: '只使用新合成资产；不复用账户、不删除数据、不自动重发、不归集。计划本身不发送网络请求。' };
}

/** 本实验特意只用整数资金，拒绝小数和不安全 Number，不能将它当作通用 NUMERIC 解析器。 */
export function exactInteger(value) {
  if (typeof value === 'number') {
    requireValue(Number.isSafeInteger(value), '响应资金不是精确整数');
    return BigInt(value);
  }
  // Jackson 的 BigDecimal 零值可能输出 0E-18；只放行精确零，不把任意指数或小数近似成整数。
  if (typeof value === 'string' && /^-?0(?:\.0+)?[eE][+-]?\d{1,3}$/.test(value)) return 0n;
  requireValue(typeof value === 'string' && value.length <= 80 && /^-?(?:0|[1-9]\d*)(?:\.0+)?$/.test(value), '响应资金不是规范整数');
  return BigInt(value.split('.')[0]);
}
function distribution(samples) {
  const sorted = [...samples].sort((a, b) => a - b);
  const percentile = p => sorted.length ? sorted[Math.ceil(p * sorted.length) - 1] : null;
  return { samples: sorted.length, p50: percentile(0.5), p95: percentile(0.95), p99: percentile(0.99),
    maximum: sorted.at(-1) ?? null, lowSampleWarning: sorted.length < 1000 };
}

/**
 * 所有写入走现有 HTTP → Kafka → 带 Fence 的 Worker，绝不直接调用资金服务或 SQL。
 * Worker 槽位覆盖提交和终态查询，因此这是有限并发闭环实验，不是固定到达率容量测试。
 * 请求超时可能已被 Broker 接收：只查询同一个 businessKey，永不换键补发。
 */
export async function runComparison(input = {}, {
  fetchImpl = globalThis.fetch, clock = () => performance.now(),
  pause = ms => new Promise(resolve => setTimeout(resolve, ms)),
  onProgress = () => {},
} = {}) {
  const config = plan(input);
  if (!config.execute) return config;
  requireValue(JSON.parse('1', (_key, value, context) => context?.source ?? value) === '1',
    '执行要求支持精确 JSON 数字来源的 Node 24；运行时不兼容，未写入');
  const runId = randomUUID(); const asset = `PF${runId.replaceAll('-', '').slice(0, 16)}`;
  const accounts = new Map(); const commands = []; const observations = [];
  const report = { ...config, runId, asset, status: 'PREPARING', successCount: 0, accounts: [], commands, observations,
    databaseAudit: { status: 'NOT_RUN', tool: 'scripts/performance/settlement-database-audit.mjs' },
    boundary: '工具协议测试不是数据库实测；实际运行也不等于生产容量、完整资金审计或真实资产交易。' };
  // 在首个写请求之前留下恢复标识；即使创建请求响应丢失，也能按合成资产查找现场。
  onProgress({ phase: 'MANIFEST', runId, asset, scenario: config.scenario, plannedCount: config.count });
  let phaseDeadline = clock() + 60000;
  // 网络请求（包括响应体）最多八秒；既不跟随重定向，也不输出上游正文和异常中的秘密。
  async function request(path, method = 'GET', body = undefined, budgetMs = 8000) {
    const remaining = Math.min(8000, budgetMs, phaseDeadline - clock());
    requireValue(remaining > 0, '阶段时限已到');
    const response = await fetchImpl(`${config.baseUrl}${path}`, { method, redirect: 'error',
      signal: AbortSignal.timeout(Math.max(1, Math.floor(remaining))),
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    const chunks = []; let size = 0;
    for await (const chunk of response.body ?? []) {
      size += chunk.byteLength;
      if (size > 262144) throw new Error('响应超过限制');
      chunks.push(chunk);
    }
    let data;
    // Node 24 保留 JSON 数字的原始词法值，禁止先转 double 再核账，否则 1e-18 差额会被吞掉。
    try { data = JSON.parse(Buffer.concat(chunks).toString('utf8'),
      (_key, value, context) => typeof value === 'number' ? context.source : value); }
    catch { throw new Error('响应不是 JSON'); }
    return { status: response.status, data };
  }
  function register(row, opening) {
    requireValue(row && UUID.test(row.accountId) && row.asset === asset && !accounts.has(row.accountId), '账户响应身份错误');
    requireValue(exactInteger(row.balance) === opening, '账户初始余额不符');
    accounts.set(row.accountId, { accountId: row.accountId, opening, expected: opening });
    report.accounts.push({ accountId: row.accountId, opening: String(opening) });
    return row.accountId;
  }
  async function create(role, index, opening) {
    const r = await request('/api/accounts', 'POST', { ownerId: `perf-${runId}-${role}-${index}`,
      asset, accountType: 'USER', openingBalance: String(opening) });
    requireValue(r.status === 201, '创建实验账户失败');
    return register(r.data, opening);
  }
  try {
    const shardReply = await request(`/api/fees/shards?asset=${asset}&count=${config.feeShardCount}`, 'POST');
    requireValue(shardReply.status === 200 && Array.isArray(shardReply.data)
      && shardReply.data.length === config.feeShardCount, '手续费分片准备失败');
    const feeIds = new Set(shardReply.data.map(row => register(row, 0n)));
    const sharedPayer = config.scenario === 'hot-payer' ? await create('payer', 0, BigInt(config.count) * 11n + 100n) : null;
    for (let i = 0; i < config.count; i++) {
      const payerAccountId = sharedPayer ?? await create('payer', i, 111n);
      const payeeAccountId = await create('payee', i, 0n);
      const businessKey = `perf-${runId}-${i}`;
      const route = await request(`/api/fees/route?asset=${asset}&count=${config.feeShardCount}&businessKey=${businessKey}`);
      requireValue(route.status === 200 && route.data?.asset === asset && feeIds.has(route.data.accountId), '手续费路由必须属于本次新建分片');
      commands.push({ messageId: `msg-${businessKey}`, businessKey, payerAccountId, payeeAccountId,
        feeAccountId: route.data.accountId, asset, amount: '10', fee: '1' });
    }
    // 统计实际路由，不把“存在16个分片”误当作流量平均；零命中分片也保留。
    const counts = new Map([...feeIds].map(id => [id, 0]));
    for (const command of commands) counts.set(command.feeAccountId, counts.get(command.feeAccountId) + 1);
    report.plannedFeeDistribution = [...counts].map(([accountId, plannedCommands]) => ({ accountId, plannedCommands }));
  } catch {
    return { ...report, status: 'SETUP_FAILED', error: '准备未完成；保留已有合成账户。检查本地服务/权限/返回格式，不自动清理或重试。' };
  }

  onProgress({ phase: 'PREPARED', runId, asset, count: config.count, businessKeys: commands.map(c => c.businessKey), commands,
    accounts: report.accounts,
    plannedFeeDistribution: report.plannedFeeDistribution });
  const started = clock(); const deadline = started + config.deadlineMs; let cursor = 0;
  phaseDeadline = deadline;
  async function worker() {
    while (cursor < commands.length && clock() < deadline) {
      const command = commands[cursor++]; const began = clock();
      const observation = { businessKey: command.businessKey, status: 'UNKNOWN', accepted: false, ackHttp: null };
      observations.push(observation);
      try {
        const ack = await request('/api/settlements', 'POST', command, deadline - clock());
        observation.ackHttp = ack.status;
        observation.accepted = ack.status === 202 && ack.data?.businessKey === command.businessKey && ack.data.status === 'ACCEPTED';
        observation.ackMs = clock() - began;
      } catch { /* 未收到确认也不能断言没发送；以下只读查询继续追踪原业务键。 */ }
      while (clock() < deadline) {
        try {
          const state = await request(`/api/settlements/${encodeURIComponent(command.businessKey)}`, 'GET', undefined, deadline - clock());
          if (state.status === 200 && state.data?.businessKey === command.businessKey
            && ['SUCCESS', 'FAILED'].includes(state.data.status)) {
            observation.status = state.data.status; observation.observedFinalMs = clock() - began;
            if (state.data.status === 'SUCCESS') {
              accounts.get(command.payerAccountId).expected -= 11n;
              accounts.get(command.payeeAccountId).expected += 10n;
              accounts.get(command.feeAccountId).expected += 1n;
            }
            break;
          }
        } catch { /* 404、临时查询失败均不释放资金、不重发；超出整体时限后保留 UNKNOWN。 */ }
        if (clock() < deadline) await pause(Math.min(config.pollMs, deadline - clock()));
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(config.concurrency, config.count) }, worker));
  const elapsedMs = Math.max(1, clock() - started);
  report.successCount = observations.filter(o => o.status === 'SUCCESS').length;
  report.failedCount = observations.filter(o => o.status === 'FAILED').length;
  report.acceptedCount = observations.filter(o => o.accepted).length;
  report.ackUnknownCount = observations.length - report.acceptedCount;
  report.unresolvedCount = observations.filter(o => o.status === 'UNKNOWN').length;
  report.notSubmittedCount = commands.length - observations.length;
  report.elapsedMs = elapsedMs;
  report.observedCompletedPerSecond = report.successCount * 1000 / elapsedMs;
  report.acceptanceLatencyMs = distribution(observations.filter(o => o.accepted).map(o => o.ackMs));
  report.observedFinalLatencyMs = distribution(observations.filter(o => o.status === 'SUCCESS').map(o => o.observedFinalMs));
  report.reconciliation = { status: 'NOT_RUN_UNRESOLVED' };
  if (report.unresolvedCount) return { ...report, status: 'INCONCLUSIVE' };

  // 所有已发送命令均已观察到终态后才独立核账。此过程不计入测量窗口，也不自动调平余额。
  const discrepancies = []; const unverified = [];
  phaseDeadline = clock() + 60000;
  for (const row of accounts.values()) {
    try {
      const r = await request(`/api/accounts/${row.accountId}/ledger-summary`);
      const s = r.data;
      const fields = ['opening_balance', 'balance', 'expected_balance', 'ledger_delta'];
      // 查询失败/字段丢失是证据未知；合法数字与预期整数不相等才是可确认的资金差异。
      requireValue(r.status === 200 && s?.account_id === row.accountId && fields.every(key =>
        typeof s[key] === 'string' && s[key].length <= 80
        && /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(s[key])), '核账证据不可用');
      try {
        if (exactInteger(s.opening_balance) !== row.opening || exactInteger(s.balance) !== row.expected
          || exactInteger(s.expected_balance) !== row.expected
          || exactInteger(s.ledger_delta) !== row.expected - row.opening) discrepancies.push(row.accountId);
      } catch { discrepancies.push(row.accountId); }
    } catch { unverified.push(row.accountId); }
  }
  report.reconciliation = { status: discrepancies.length ? 'MISMATCH' : unverified.length ? 'UNVERIFIED' : 'MATCHED',
    checkedAccounts: accounts.size - unverified.length, totalAccounts: accounts.size,
    mismatchedAccounts: discrepancies, unverifiedAccounts: unverified,
    boundary: '核对账户级余额与账本净额；不替代逐笔唯一分录、Outbox、最终回执和数据库故障验收。' };
  const complete = report.successCount === config.count && !report.notSubmittedCount;
  report.status = discrepancies.length ? 'FAILED_RECONCILIATION' : unverified.length ? 'INCONCLUSIVE_RECONCILIATION' : !complete ? 'INCOMPLETE_LAB_RUN'
    : report.ackUnknownCount ? 'VERIFIED_WITH_ACK_WARNINGS' : 'VERIFIED_LAB_RUN';
  return report;
}

export function parseArgs(args) {
  const result = {}; const seen = new Set();
  const options = { '--scenario': 'scenario', '--count': 'count', '--concurrency': 'concurrency',
    '--deadline-ms': 'deadlineMs', '--poll-ms': 'pollMs', '--base-url': 'baseUrl' };
  for (let i = 0; i < args.length; i++) {
    const flag = args[i]; requireValue(!seen.has(flag), '重复参数'); seen.add(flag);
    if (flag === '--run') result.execute = true;
    else if (flag === '--confirm-isolated') result.confirmIsolated = true;
    else if (flag === '--help') result.help = true;
    else {
      requireValue(Object.hasOwn(options, flag) && i + 1 < args.length, '未知参数或缺少参数值');
      let value = args[++i]; const name = options[flag];
      if (['count', 'concurrency', 'deadlineMs', 'pollMs'].includes(name)) {
        requireValue(/^\d+$/.test(value), '数值参数必须是十进制整数'); value = Number(value);
      }
      result[name] = value;
    }
  }
  return result;
}
const HELP = `隔离结算对照工具（Node 24；默认只生成计划）
node scripts/performance/settlement-comparison.mjs [--scenario shared-fee|sharded-fee|hot-payer]
  [--count 64] [--concurrency 4] [--deadline-ms 60000] [--poll-ms 200]
  [--base-url http://127.0.0.1:18080] [--run --confirm-isolated]
只有 --run 与 --confirm-isolated 同时出现才实际创建合成账户、投递Kafka结算。
必须是独立本地实验实例与实验库，不能是生产服务的SSH隧道、反代或已有真实账户环境。
输出为JSON；保留runId、asset和业务键。UNKNOWN时不重发、不删除。没有身份凭据输入。`;
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const args = parseArgs(process.argv.slice(2));
    if (args.help) console.log(HELP);
    else {
      const report = await runComparison(args, { onProgress: event => console.error(JSON.stringify(event)) });
      console.log(JSON.stringify(report, null, 2));
      if (!['PLAN_ONLY', 'VERIFIED_LAB_RUN'].includes(report.status)) process.exitCode = 2;
    }
  } catch { console.error('参数无效或实验工具失败；请查看 --help。未自动重试，检查并保留本轮现场。'); process.exitCode = 1; }
}

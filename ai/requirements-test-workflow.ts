/**
 * 内部需求与测试教学内核。
 * 将“产品待决项”与“已经确认的验收条件”分开，再生成有来源、数据和预期的测试计划。
 * 全部资料和候选为固定合成样本；执行对象仅为本地状态参考模型，没有 LLM 或邮件发送。
 * 测试计划的预期独立声明，不读取模型执行结果来反向生成 PASS。
 */
export type SendingCancel = 'unresolved' | 'reject' | 'stop_remaining';
export type Reschedule = 'unresolved' | 'allow' | 'deny';
export type Decisions = {
  sendingCancel: SendingCancel;
  reschedule: Reschedule;
};
export const initialDecisions: Decisions = {
  sendingCancel: 'unresolved',
  reschedule: 'unresolved',
};
export const NOW = '2026-09-10T01:59:00Z';
export const DUE = '2026-09-10T02:00:00Z';
export const LATER = '2026-09-10T03:00:00Z';
export const rawRequest = '活动邮件要能预约发送，也能取消。下周活动前做好。';
export const baselineRules = [
  {
    id: 'B1',
    text: '教学补充纪要：必须选择带时区的未来时间；不能静默修正过去时间。',
  },
  {
    id: 'B2',
    text: '教学补充纪要：尚未开始的预约可取消；取消后不能启动发送。',
  },
  {
    id: 'B3',
    text: '教学质量约定：同一请求重试不产生第二次状态变化；请求号不能用于不同内容。',
  },
];
export type Requirement = {
  id: string;
  acId: string;
  title: string;
  status: 'confirmed' | 'pending';
  source: string;
  acceptance: string;
};
export function decisionVersion(d: Decisions): string {
  return `demo-v1/${d.sendingCancel}/${d.reschedule}`;
}
/** 这两处选择代表产品在教学中的决定，不是由 AI 自动补全需求。 */
export function requirements(d: Decisions): Requirement[] {
  return [
    {
      id: 'REQ-01',
      acId: 'AC-01',
      title: '预约时间',
      status: 'confirmed',
      source: 'B1',
      acceptance:
        '带时区的未来时间可以预约；时间恰好等于现在、早于现在或缺时区时拒绝。',
    },
    {
      id: 'REQ-02',
      acId: 'AC-02',
      title: '未开始取消',
      status: 'confirmed',
      source: 'B2',
      acceptance:
        'SCHEDULED → CANCELLED；重复相同取消返回原结果，取消后的计划不能开始发送。',
    },
    {
      id: 'REQ-03',
      acId: 'AC-03',
      title: '发送中取消',
      status: d.sendingCancel === 'unresolved' ? 'pending' : 'confirmed',
      source: '产品决定 D1',
      acceptance:
        d.sendingCancel === 'unresolved'
          ? '待产品确认：拒绝，还是仅停止剩余未发送部分？'
          : d.sendingCancel === 'reject'
            ? '发送中拒绝取消；保留原状态与已发送数量，不提示撤回成功。'
            : '停止尚未开始发送的剩余部分；已发送数量不变。参考模型没有在途邮件，真实在途边界仍需研发确认。',
    },
    {
      id: 'REQ-04',
      acId: 'AC-04',
      title: '修改预约',
      status: d.reschedule === 'unresolved' ? 'pending' : 'confirmed',
      source: '产品决定 D2',
      acceptance:
        d.reschedule === 'unresolved'
          ? '待产品确认：未开始的预约能否改到另一个未来时间？'
          : d.reschedule === 'allow'
            ? '未开始时允许改约，版本增加；旧版本任务不能按旧时间启动。'
            : '未开始时也不支持改约；返回明确提示，原预约时间和版本不变。',
    },
    {
      id: 'REQ-05',
      acId: 'AC-05',
      title: '重复与状态边界',
      status: 'confirmed',
      source: 'B3',
      acceptance:
        '同请求同内容返回原结果；同请求不同内容冲突；已发送状态不能取消。',
    },
  ];
}
export type Plan = {
  state: 'SCHEDULED' | 'SENDING' | 'CANCELLED' | 'STOPPED' | 'SENT';
  version: number;
  scheduledAt: string;
  sent: number;
  total: number;
};
export type Command =
  | { type: 'cancel'; requestId: string }
  | { type: 'start'; expectedVersion: number; now: string }
  | { type: 'reschedule'; requestId: string; at: string; now: string };
export type Outcome = {
  code: string;
  state: string;
  version: number;
  sent: number;
  stopped: number;
  scheduledAt: string;
};
export type Simulation = {
  plan: Plan;
  receipts: { key: string; payload: string; outcome: Outcome }[];
  outcome: Outcome;
};
export function makePlan(overrides: Partial<Plan> = {}): Plan {
  return {
    state: 'SCHEDULED',
    version: 7,
    scheduledAt: DUE,
    sent: 0,
    total: 10,
    ...overrides,
  };
}
function snapshot(plan: Plan, code: string): Outcome {
  return {
    code,
    state: plan.state,
    version: plan.version,
    sent: plan.sent,
    stopped: plan.state === 'STOPPED' ? plan.total - plan.sent : 0,
    scheduledAt: plan.scheduledAt,
  };
}
export function createSimulation(plan = makePlan()): Simulation {
  return { plan: { ...plan }, receipts: [], outcome: snapshot(plan, 'READY') };
}
function validInstant(value: string): number | null {
  const match =
    /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,3})?(Z|[+-]\d{2}:\d{2})$/.exec(
      value,
    );
  if (!match) return null;
  const [year, month, day, hour, minute, second] = match
    .slice(1, 7)
    .map(Number);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  // Date.parse 会把某些不存在的日期进位；先检查日历，禁止静默修正业务输入。
  if (
    month < 1 ||
    month > 12 ||
    day < 1 ||
    day > days[month - 1] ||
    hour > 23 ||
    minute > 59 ||
    second > 59
  )
    return null;
  const zone = match[7];
  if (
    zone !== 'Z' &&
    (Number(zone.slice(1, 3)) > 23 || Number(zone.slice(4, 6)) > 59)
  )
    return null;
  const ms = Date.parse(value);
  return Number.isFinite(ms) ? ms : null;
}
/** 这里只验证明确的 ISO 时刻，不处理自然语言时间，也不代替生产的日期/时区组件。 */
export function scheduleCheck(at: string, now: string): string {
  const scheduled = validInstant(at),
    clock = validInstant(now);
  if (scheduled === null || clock === null) return 'INVALID_TIME';
  return scheduled > clock ? 'SCHEDULED' : 'NOT_FUTURE';
}
/**
 * 原子、单进程参考转移。重复请求记录在内存中；真实队列/数据库/外部发送结果需要另行验证。
 * 失败不改计划；改约通过版本阻止旧任务。取消/开始竞态仅枚举先后顺序，不冒充真实并发测试。
 */
export function applyCommand(
  s: Simulation,
  c: Command,
  d: Decisions,
): Simulation {
  const payload =
    c.type === 'cancel'
      ? JSON.stringify({ type: c.type, requestId: c.requestId })
      : c.type === 'reschedule'
        ? JSON.stringify({
            type: c.type,
            requestId: c.requestId,
            at: c.at,
            now: c.now,
          })
        : JSON.stringify({
            type: c.type,
            expectedVersion: c.expectedVersion,
            now: c.now,
          });
  const key = 'requestId' in c ? c.requestId : null;
  const old = key ? s.receipts.find((r) => r.key === key) : undefined;
  if (old)
    return old.payload === payload
      ? { ...s, outcome: { ...old.outcome } }
      : { ...s, outcome: snapshot(s.plan, 'REQUEST_CONFLICT') };
  let plan = { ...s.plan };
  let code = 'UNKNOWN';
  if (c.type === 'start') {
    if (plan.state !== 'SCHEDULED') code = 'NOT_SCHEDULED';
    else if (c.expectedVersion !== plan.version) code = 'STALE_VERSION';
    else {
      const now = validInstant(c.now),
        due = validInstant(plan.scheduledAt);
      if (now === null || due === null) code = 'INVALID_TIME';
      else if (now < due) code = 'NOT_DUE';
      else {
        plan = { ...plan, state: 'SENDING', version: plan.version + 1 };
        code = 'STARTED';
      }
    }
  } else if (c.type === 'cancel') {
    if (plan.state === 'SCHEDULED') {
      plan = { ...plan, state: 'CANCELLED', version: plan.version + 1 };
      code = 'CANCELLED';
    } else if (plan.state === 'SENDING') {
      if (d.sendingCancel === 'unresolved') code = 'RULE_UNRESOLVED';
      else if (d.sendingCancel === 'reject') code = 'SENDING_CANCEL_REJECTED';
      else {
        plan = { ...plan, state: 'STOPPED', version: plan.version + 1 };
        code = 'STOPPED_REMAINING';
      }
    } else code = 'STATE_REJECTED';
  } else {
    if (plan.state !== 'SCHEDULED') code = 'STATE_REJECTED';
    else if (d.reschedule === 'unresolved') code = 'RULE_UNRESOLVED';
    else if (d.reschedule === 'deny') code = 'RESCHEDULE_REJECTED';
    else {
      const check = scheduleCheck(c.at, c.now);
      if (check !== 'SCHEDULED') code = check;
      else {
        plan = { ...plan, scheduledAt: c.at, version: plan.version + 1 };
        code = 'RESCHEDULED';
      }
    }
  }
  const outcome = snapshot(plan, code);
  // 未决规则不是最终业务判断，不记录为可重放的已决请求。
  const receipts =
    key && code !== 'RULE_UNRESOLVED'
      ? [...s.receipts, { key, payload, outcome: { ...outcome } }]
      : s.receipts;
  return { plan, receipts, outcome };
}
export type TestCase = {
  id: string;
  reqId: string;
  acId: string;
  category: string;
  name: string;
  given: string;
  when: string;
  expectedBehavior: string;
  expected: Outcome | null;
  initial: Plan;
  commands: Command[];
  schedule?: { at: string; now: string };
};
function expectation(
  code: string,
  state: Plan['state'] = 'SCHEDULED',
  version = 7,
  sent = 0,
  scheduledAt = DUE,
  stopped = 0,
): Outcome {
  return { code, state, version, sent, stopped, scheduledAt };
}
export function testCases(d: Decisions): TestCase[] {
  const normal = makePlan();
  const tc = (
    id: string,
    reqId: string,
    category: string,
    name: string,
    given: string,
    commands: Command[],
    expected: Outcome | null,
    when: string,
    expectedBehavior: string,
    initial = normal,
  ): TestCase => ({
    id,
    reqId,
    acId: reqId.replace('REQ', 'AC'),
    category,
    name,
    given,
    commands,
    expected,
    when,
    expectedBehavior,
    initial: { ...initial },
  });
  const timeCases = [
    ['TC-01', '未来时间', '2026-09-10T10:00:00+08:00', 'SCHEDULED'],
    ['TC-02', '时间等于现在', NOW, 'NOT_FUTURE'],
    ['TC-03', '过去时间', '2026-09-10T01:58:00Z', 'NOT_FUTURE'],
    ['TC-04', '没有时区', '2026-09-10T10:00:00', 'INVALID_TIME'],
  ].map(([id, name, at, code]) => ({
    ...tc(
      id,
      'REQ-01',
      '时间边界',
      name,
      `当前 ${NOW}`,
      [],
      expectation(code),
      '提交预约时间',
      `返回 ${code}；不得静默修改输入`,
    ),
    schedule: { at, now: NOW },
  }));
  return [
    ...timeCases,
    tc(
      'TC-05',
      'REQ-02',
      '正常',
      '取消尚未开始的预约',
      'SCHEDULED / v7 / 已发送 0',
      [{ type: 'cancel', requestId: 'C-1' }],
      expectation('CANCELLED', 'CANCELLED', 8),
      '取消一次',
      'CANCELLED / v8 / 已发送 0',
    ),
    tc(
      'TC-06',
      'REQ-02',
      '重复',
      '相同取消请求重试',
      'SCHEDULED / v7 / 已发送 0',
      [
        { type: 'cancel', requestId: 'C-1' },
        { type: 'cancel', requestId: 'C-1' },
      ],
      expectation('CANCELLED', 'CANCELLED', 8),
      '相同请求 C-1 提交两次',
      '返回同一结果，版本仍为 8',
    ),
    tc(
      'TC-07',
      'REQ-02',
      '顺序竞争',
      '取消先于开始发送',
      'SCHEDULED / v7 / 已发送 0',
      [
        { type: 'cancel', requestId: 'C-1' },
        { type: 'start', expectedVersion: 7, now: DUE },
      ],
      expectation('NOT_SCHEDULED', 'CANCELLED', 8),
      '先取消，再模拟到点任务',
      '开始被拒绝；最终 CANCELLED，已发送仍为 0',
    ),
    tc(
      'TC-08',
      'REQ-03',
      '顺序竞争',
      '开始先于取消',
      'SCHEDULED / v7 / 已发送 0',
      [
        { type: 'start', expectedVersion: 7, now: DUE },
        { type: 'cancel', requestId: 'C-2' },
      ],
      d.sendingCancel === 'unresolved'
        ? null
        : d.sendingCancel === 'reject'
          ? expectation('SENDING_CANCEL_REJECTED', 'SENDING', 8)
          : expectation('STOPPED_REMAINING', 'STOPPED', 9, 0, DUE, 10),
      '先到点开始，再取消',
      requirements(d)[2].acceptance,
    ),
    tc(
      'TC-09',
      'REQ-03',
      '状态',
      '部分已经发送时取消',
      'SENDING / v8 / 已发送 3，共 10',
      [{ type: 'cancel', requestId: 'C-3' }],
      d.sendingCancel === 'unresolved'
        ? null
        : d.sendingCancel === 'reject'
          ? expectation('SENDING_CANCEL_REJECTED', 'SENDING', 8, 3)
          : expectation('STOPPED_REMAINING', 'STOPPED', 9, 3, DUE, 7),
      '取消剩余批次',
      d.sendingCancel === 'stop_remaining'
        ? 'STOPPED / 已发仍为 3，停止剩余 7；不是撤回已发送邮件'
        : requirements(d)[2].acceptance,
      makePlan({ state: 'SENDING', version: 8, sent: 3 }),
    ),
    tc(
      'TC-10',
      'REQ-04',
      '变更',
      '修改未来预约时间',
      'SCHEDULED / v7',
      [{ type: 'reschedule', requestId: 'R-1', at: LATER, now: NOW }],
      d.reschedule === 'unresolved'
        ? null
        : d.reschedule === 'allow'
          ? expectation('RESCHEDULED', 'SCHEDULED', 8, 0, LATER)
          : expectation('RESCHEDULE_REJECTED'),
      '改约到 03:00Z',
      requirements(d)[3].acceptance,
    ),
    tc(
      'TC-11',
      'REQ-04',
      '迟到任务',
      '改约后旧版本任务启动',
      'SCHEDULED / v7',
      [
        { type: 'reschedule', requestId: 'R-1', at: LATER, now: NOW },
        { type: 'start', expectedVersion: 7, now: DUE },
      ],
      d.reschedule === 'unresolved'
        ? null
        : d.reschedule === 'allow'
          ? expectation('STALE_VERSION', 'SCHEDULED', 8, 0, LATER)
          : expectation('STARTED', 'SENDING', 8),
      '尝试改约，再运行原 v7 到点任务',
      d.reschedule === 'allow'
        ? '旧版本被拒绝，新预约保持 SCHEDULED'
        : d.reschedule === 'deny'
          ? '改约被拒绝，原计划仍有效，可以到点开始'
          : '待确认是否允许改约',
    ),
    tc(
      'TC-12',
      'REQ-05',
      '请求冲突',
      '同一请求号不同内容',
      'SCHEDULED / v7',
      [
        { type: 'cancel', requestId: 'SHARED' },
        { type: 'reschedule', requestId: 'SHARED', at: LATER, now: NOW },
      ],
      expectation('REQUEST_CONFLICT', 'CANCELLED', 8),
      '先取消，再误用相同请求号改约',
      '冲突；不修改已取消状态',
    ),
    tc(
      'TC-13',
      'REQ-05',
      '终态',
      '已发送完成后取消',
      'SENT / 已发送 10，共 10',
      [{ type: 'cancel', requestId: 'C-4' }],
      expectation('STATE_REJECTED', 'SENT', 9, 10),
      '对已完成计划取消',
      '拒绝，已发送事实不变',
      makePlan({ state: 'SENT', version: 9, sent: 10 }),
    ),
  ];
}
export type Report = {
  version: string;
  target: 'local_reference_model';
  rows: {
    id: string;
    status: 'pass' | 'fail' | 'blocked';
    expected: Outcome | null;
    actual: Outcome | null;
  }[];
};
/** 只执行已确认的预期；未知条件保留 blocked，不混入通过分子，也不生成假测试成绩。 */
export function runTestPlan(d: Decisions): Report {
  const rows = testCases(d).map((t) => {
    if (!t.expected)
      return {
        id: t.id,
        status: 'blocked' as const,
        expected: null,
        actual: null,
      };
    let actual: Outcome;
    if (t.schedule)
      actual = expectation(scheduleCheck(t.schedule.at, t.schedule.now));
    else {
      let s = createSimulation(t.initial);
      for (const c of t.commands) s = applyCommand(s, c, d);
      actual = s.outcome;
    }
    return {
      id: t.id,
      status:
        JSON.stringify(actual) === JSON.stringify(t.expected)
          ? ('pass' as const)
          : ('fail' as const),
      expected: t.expected,
      actual,
    };
  });
  return { version: decisionVersion(d), target: 'local_reference_model', rows };
}
export function changedArtifacts(before: Decisions, after: Decisions) {
  const changed: string[] = [];
  if (before.sendingCancel !== after.sendingCancel)
    changed.push('REQ-03', 'AC-03', 'TC-08', 'TC-09');
  if (before.reschedule !== after.reschedule)
    changed.push('REQ-04', 'AC-04', 'TC-10', 'TC-11');
  return changed;
}
export function reportIsCurrent(report: Report | null, d: Decisions): boolean {
  return !!report && report.version === decisionVersion(d);
}
export const manualOnlyChecks = [
  {
    id: 'IT-01',
    title: '真实队列与数据库竞争',
    expected:
      '取消和开始并发提交时，只能形成一条符合当前产品规则的状态路径；需要实际服务测试。',
  },
  {
    id: 'IT-02',
    title: '发送超时与结果未知',
    expected:
      '产品先确认查询与重试规则，再验证供应商回报迟到、部分失败和重复发送；当前待确认、未执行。',
  },
  {
    id: 'IT-03',
    title: '界面文案与权限',
    expected:
      '检查使用者权限、时区显示、取消/部分停止提示；需要页面与权限服务验证。',
  },
  {
    id: 'IT-04',
    title: '真实在途邮件边界',
    expected:
      '停止剩余发送时，已提交给供应商但尚未回报的邮件怎样计数和展示，需产品/研发确认；当前未执行。',
  },
];

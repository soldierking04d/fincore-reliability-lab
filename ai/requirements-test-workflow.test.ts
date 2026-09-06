import test from 'node:test';
import assert from 'node:assert/strict';
import {
  DUE,
  LATER,
  NOW,
  applyCommand,
  baselineRules,
  changedArtifacts,
  createSimulation,
  decisionVersion,
  initialDecisions,
  makePlan,
  manualOnlyChecks,
  rawRequest,
  reportIsCurrent,
  requirements,
  runTestPlan,
  scheduleCheck,
  testCases,
} from './requirements-test-workflow.ts';
import type {
  Command,
  Decisions,
  Outcome,
  Reschedule,
  SendingCancel,
} from './requirements-test-workflow.ts';

// Node 24：node --test ai/requirements-test-workflow.test.ts
// 预期在测试资产中独立声明，不能从被测执行结果反向生成通过结论。
// 所有命令只作用于本地状态模型；顺序枚举不代表真实队列并发或邮件集成测试。
const clone = <T>(value: T): T => structuredClone(value);
const due = '2026-09-10T02:00:00Z';
const later = '2026-09-10T03:00:00Z';
const chosen: Decisions = {
  sendingCancel: 'stop_remaining',
  reschedule: 'allow',
};
const cancel: Command = { type: 'cancel', requestId: 'C-TEST' };
const reschedule: Command = {
  type: 'reschedule',
  requestId: 'R-TEST',
  at: later,
  now: '2026-09-10T01:59:00Z',
};
const literal = (code: string, changes: Partial<Outcome> = {}): Outcome => ({
  code,
  state: 'SCHEDULED',
  version: 7,
  sent: 0,
  stopped: 0,
  scheduledAt: due,
  ...changes,
});

// 13 行预期逐项明确；不读取 testCases、applyCommand 或 runTestPlan 的结果。
function independentRows(d: Decisions): Record<string, Outcome | null> {
  return {
    'TC-01': literal('SCHEDULED'),
    'TC-02': literal('NOT_FUTURE'),
    'TC-03': literal('NOT_FUTURE'),
    'TC-04': literal('INVALID_TIME'),
    'TC-05': literal('CANCELLED', { state: 'CANCELLED', version: 8 }),
    'TC-06': literal('CANCELLED', { state: 'CANCELLED', version: 8 }),
    'TC-07': literal('NOT_SCHEDULED', { state: 'CANCELLED', version: 8 }),
    'TC-08':
      d.sendingCancel === 'unresolved'
        ? null
        : d.sendingCancel === 'reject'
          ? literal('SENDING_CANCEL_REJECTED', { state: 'SENDING', version: 8 })
          : literal('STOPPED_REMAINING', {
              state: 'STOPPED',
              version: 9,
              stopped: 10,
            }),
    'TC-09':
      d.sendingCancel === 'unresolved'
        ? null
        : d.sendingCancel === 'reject'
          ? literal('SENDING_CANCEL_REJECTED', {
              state: 'SENDING',
              version: 8,
              sent: 3,
            })
          : literal('STOPPED_REMAINING', {
              state: 'STOPPED',
              version: 9,
              sent: 3,
              stopped: 7,
            }),
    'TC-10':
      d.reschedule === 'unresolved'
        ? null
        : d.reschedule === 'allow'
          ? literal('RESCHEDULED', { version: 8, scheduledAt: later })
          : literal('RESCHEDULE_REJECTED'),
    'TC-11':
      d.reschedule === 'unresolved'
        ? null
        : d.reschedule === 'allow'
          ? literal('STALE_VERSION', { version: 8, scheduledAt: later })
          : literal('STARTED', { state: 'SENDING', version: 8 }),
    'TC-12': literal('REQUEST_CONFLICT', { state: 'CANCELLED', version: 8 }),
    'TC-13': literal('STATE_REJECTED', { state: 'SENT', version: 9, sent: 10 }),
  };
}

for (const sendingCancel of [
  'unresolved',
  'reject',
  'stop_remaining',
] as SendingCancel[]) {
  for (const reschedule of ['unresolved', 'allow', 'deny'] as Reschedule[]) {
    void test(`决定组合 ${sendingCancel}/${reschedule}：13 行独立预期`, () => {
      const decisions = { sendingCancel, reschedule };
      const expected = independentRows(decisions);
      const report = runTestPlan(decisions);
      const plan = testCases(decisions);
      assert.equal(report.rows.length, 13);
      assert.equal(plan.length, 13);
      assert.deepEqual(
        report.rows.map((r) => r.id),
        Object.keys(expected),
      );
      assert.equal(report.target, 'local_reference_model');
      let blocked = 0;
      for (const row of report.rows) {
        const wanted = expected[row.id];
        assert.deepEqual(
          plan.find((t) => t.id === row.id)!.expected,
          wanted,
          `${row.id} 计划预期`,
        );
        assert.deepEqual(row.expected, wanted, `${row.id} 报告预期`);
        assert.deepEqual(row.actual, wanted, `${row.id} 独立核验实际结果`);
        assert.equal(row.status, wanted === null ? 'blocked' : 'pass', row.id);
        if (wanted === null) blocked++;
      }
      const pending =
        (sendingCancel === 'unresolved' ? 2 : 0) +
        (reschedule === 'unresolved' ? 2 : 0);
      assert.equal(blocked, pending);
      assert.equal(
        report.rows.filter((r) => r.status === 'pass').length,
        13 - pending,
      );
      if (pending === 4)
        assert.equal(report.rows.filter((r) => r.status === 'pass').length, 9);
    });
  }
}

void test('原始需求、补充来源、REQ/AC 与全部测试行可双向追溯', () => {
  assert.match(rawRequest, /预约发送.*取消/);
  assert.deepEqual(
    baselineRules.map((b) => b.id),
    ['B1', 'B2', 'B3'],
  );
  const req = requirements(initialDecisions);
  assert.deepEqual(
    req.map((r) => [r.id, r.acId, r.source]),
    [
      ['REQ-01', 'AC-01', 'B1'],
      ['REQ-02', 'AC-02', 'B2'],
      ['REQ-03', 'AC-03', '产品决定 D1'],
      ['REQ-04', 'AC-04', '产品决定 D2'],
      ['REQ-05', 'AC-05', 'B3'],
    ],
  );
  const expectedReqIds = [
    'REQ-01',
    'REQ-01',
    'REQ-01',
    'REQ-01',
    'REQ-02',
    'REQ-02',
    'REQ-02',
    'REQ-03',
    'REQ-03',
    'REQ-04',
    'REQ-04',
    'REQ-05',
    'REQ-05',
  ];
  const plan = testCases(initialDecisions);
  assert.deepEqual(
    plan.map((t) => t.reqId),
    expectedReqIds,
  );
  for (const t of plan) {
    const parent = req.find((r) => r.id === t.reqId);
    assert.ok(parent);
    assert.equal(t.acId, parent.acId);
    for (const text of [
      t.given,
      t.when,
      t.expectedBehavior,
      t.category,
      t.name,
    ])
      assert.ok(text.length > 0);
  }
});

void test('未知项保持待决，不自动替产品选择，确认后同一请求可重新判断', () => {
  const before = clone(initialDecisions);
  assert.deepEqual(
    requirements(initialDecisions).map((r) => r.status),
    ['confirmed', 'confirmed', 'pending', 'pending', 'confirmed'],
  );
  assert.match(requirements(initialDecisions)[2].acceptance, /待产品确认/);
  assert.match(requirements(initialDecisions)[3].acceptance, /待产品确认/);
  const sending = createSimulation(
    makePlan({ state: 'SENDING', version: 8, sent: 3 }),
  );
  const waiting = applyCommand(sending, cancel, initialDecisions);
  assert.deepEqual(waiting.plan, sending.plan);
  assert.equal(waiting.outcome.code, 'RULE_UNRESOLVED');
  assert.deepEqual(waiting.receipts, []);
  assert.equal(
    applyCommand(waiting, cancel, chosen).outcome.code,
    'STOPPED_REMAINING',
  );
  const pendingChange = applyCommand(
    createSimulation(),
    reschedule,
    initialDecisions,
  );
  assert.equal(pendingChange.outcome.code, 'RULE_UNRESOLVED');
  assert.deepEqual(pendingChange.receipts, []);
  assert.equal(
    applyCommand(pendingChange, reschedule, chosen).outcome.code,
    'RESCHEDULED',
  );
  assert.deepEqual(initialDecisions, before);
});

void test('时间边界按真实时刻比较，等于现在、过去、缺时区和非法输入被拒绝', () => {
  assert.equal(NOW, '2026-09-10T01:59:00Z');
  assert.equal(DUE, due);
  assert.equal(LATER, later);
  const checks: [string, string, string][] = [
    ['2026-09-10T10:00:00+08:00', NOW, 'SCHEDULED'],
    [NOW, NOW, 'NOT_FUTURE'],
    ['2026-09-10T09:59:00+08:00', NOW, 'NOT_FUTURE'],
    ['2026-09-10T01:58:59Z', NOW, 'NOT_FUTURE'],
    ['2026-09-10T10:00:00', NOW, 'INVALID_TIME'],
    ['not-a-date', NOW, 'INVALID_TIME'],
    [DUE, 'not-a-date', 'INVALID_TIME'],
    ['2026-09-10T10:00:00+25:00', NOW, 'INVALID_TIME'],
  ];
  for (const [at, now, code] of checks)
    assert.equal(scheduleCheck(at, now), code, `${at} / ${now}`);
});

void test('发送中取消两个产品选择都保留已发 3；仅停止剩余的选择报告 7', () => {
  const start = createSimulation(
    makePlan({ state: 'SENDING', version: 8, sent: 3, total: 10 }),
  );
  const rejected = applyCommand(start, cancel, {
    sendingCancel: 'reject',
    reschedule: 'unresolved',
  });
  assert.deepEqual(
    rejected.outcome,
    literal('SENDING_CANCEL_REJECTED', {
      state: 'SENDING',
      version: 8,
      sent: 3,
    }),
  );
  assert.deepEqual(rejected.plan, start.plan);
  const stopped = applyCommand(start, cancel, chosen);
  assert.deepEqual(
    stopped.outcome,
    literal('STOPPED_REMAINING', {
      state: 'STOPPED',
      version: 9,
      sent: 3,
      stopped: 7,
    }),
  );
  assert.equal(stopped.plan.sent, 3);
  assert.equal(stopped.outcome.sent + stopped.outcome.stopped, 10);
  assert.match(
    requirements(chosen)[2].acceptance,
    /已发送数量不变.*没有在途邮件.*真实在途边界/,
  );
});

void test('允许改约增加版本；旧任务不能启动，新版本也不能提前启动', () => {
  const changed = applyCommand(createSimulation(), reschedule, chosen);
  assert.deepEqual(
    changed.outcome,
    literal('RESCHEDULED', { version: 8, scheduledAt: later }),
  );
  const stale = applyCommand(
    changed,
    { type: 'start', expectedVersion: 7, now: DUE },
    chosen,
  );
  assert.deepEqual(
    stale.outcome,
    literal('STALE_VERSION', { version: 8, scheduledAt: later }),
  );
  const early = applyCommand(
    stale,
    { type: 'start', expectedVersion: 8, now: DUE },
    chosen,
  );
  assert.deepEqual(
    early.outcome,
    literal('NOT_DUE', { version: 8, scheduledAt: later }),
  );
  const started = applyCommand(
    early,
    { type: 'start', expectedVersion: 8, now: LATER },
    chosen,
  );
  assert.deepEqual(
    started.outcome,
    literal('STARTED', { state: 'SENDING', version: 9, scheduledAt: later }),
  );
});

void test('拒绝改约保留原计划；允许改约也不能接受非未来或无时区时间', () => {
  const denied: Decisions = { sendingCancel: 'reject', reschedule: 'deny' };
  const original = createSimulation();
  const rejected = applyCommand(original, reschedule, denied);
  assert.deepEqual(rejected.outcome, literal('RESCHEDULE_REJECTED'));
  assert.deepEqual(rejected.plan, original.plan);
  assert.deepEqual(
    applyCommand(
      rejected,
      { type: 'start', expectedVersion: 7, now: DUE },
      denied,
    ).outcome,
    literal('STARTED', { state: 'SENDING', version: 8 }),
  );
  for (const [at, code] of [
    [NOW, 'NOT_FUTURE'],
    ['2026-09-10T03:00:00', 'INVALID_TIME'],
  ]) {
    const failed = applyCommand(
      original,
      { type: 'reschedule', requestId: 'BAD', at, now: NOW },
      chosen,
    );
    assert.equal(failed.outcome.code, code);
    assert.deepEqual(failed.plan, original.plan);
  }
});

void test('同请求同内容返回原结果且只记一次；后续状态不会被重放倒退', () => {
  const first = applyCommand(createSimulation(), reschedule, chosen);
  const replay = applyCommand(first, clone(reschedule), chosen);
  assert.deepEqual(replay, first);
  assert.equal(replay.receipts.length, 1);
  const progressed = applyCommand(
    first,
    { type: 'start', expectedVersion: 8, now: LATER },
    chosen,
  );
  const lateReplay = applyCommand(progressed, reschedule, chosen);
  assert.deepEqual(
    lateReplay.outcome,
    first.outcome,
    '返回原请求回执，不冒充最新状态',
  );
  assert.deepEqual(
    lateReplay.plan,
    progressed.plan,
    '当前计划仍为 SENDING / v9',
  );
  assert.equal(lateReplay.receipts.length, 1);
});

void test('同请求号不同内容或不同命令产生冲突，不覆盖原计划及回执', () => {
  const first = applyCommand(createSimulation(), reschedule, chosen);
  const variants: Command[] = [
    {
      type: 'reschedule',
      requestId: 'R-TEST',
      at: '2026-09-10T04:00:00Z',
      now: NOW,
    },
    { type: 'cancel', requestId: 'R-TEST' },
  ];
  for (const variant of variants) {
    const result = applyCommand(first, variant, chosen);
    assert.equal(result.outcome.code, 'REQUEST_CONFLICT');
    assert.deepEqual(result.plan, first.plan);
    assert.deepEqual(result.receipts, first.receipts);
  }
});

void test('相同请求仅对象字段顺序变化仍属于同内容重试', () => {
  const first = applyCommand(createSimulation(), reschedule, chosen);
  const reordered: Command = {
    now: NOW,
    at: LATER,
    requestId: 'R-TEST',
    type: 'reschedule',
  };
  const result = applyCommand(first, reordered, chosen);
  assert.deepEqual(result.outcome, first.outcome);
  assert.deepEqual(result.plan, first.plan);
  assert.equal(result.receipts.length, 1);
});

void test('取消/开始仅枚举两种顺序，不假装真实并发；两条路径都有确定预期', () => {
  const start: Command = { type: 'start', expectedVersion: 7, now: DUE };
  const cancelFirst = applyCommand(
    applyCommand(createSimulation(), cancel, chosen),
    start,
    chosen,
  );
  assert.deepEqual(
    cancelFirst.outcome,
    literal('NOT_SCHEDULED', { state: 'CANCELLED', version: 8 }),
  );
  for (const sendingCancel of ['reject', 'stop_remaining'] as SendingCancel[]) {
    const d = { sendingCancel, reschedule: 'allow' as const };
    const startFirst = applyCommand(
      applyCommand(createSimulation(), start, d),
      cancel,
      d,
    );
    assert.deepEqual(
      startFirst.outcome,
      sendingCancel === 'reject'
        ? literal('SENDING_CANCEL_REJECTED', { state: 'SENDING', version: 8 })
        : literal('STOPPED_REMAINING', {
            state: 'STOPPED',
            version: 9,
            stopped: 10,
          }),
    );
    assert.equal(
      startFirst.plan.sent,
      0,
      'STARTED 只改变参考状态，并不发送邮件',
    );
  }
  assert.ok(
    testCases(chosen)
      .filter((t) => ['TC-07', 'TC-08'].includes(t.id))
      .every((t) => t.category === '顺序竞争'),
  );
  assert.match(
    manualOnlyChecks.find((c) => c.id === 'IT-01')!.expected,
    /并发.*需要实际服务测试/,
  );
});

void test('输入不会被修改；失败保持计划，已发送终态不能取消或撤回事实', () => {
  const original = createSimulation();
  const snapshot = clone(original);
  applyCommand(original, cancel, chosen);
  assert.deepEqual(original, snapshot);
  for (const command of [
    { type: 'start', expectedVersion: 6, now: DUE },
    { type: 'start', expectedVersion: 7, now: NOW },
    { type: 'start', expectedVersion: 7, now: 'invalid' },
  ] as Command[]) {
    assert.deepEqual(
      applyCommand(original, command, chosen).plan,
      original.plan,
    );
  }
  const terminal = createSimulation(
    makePlan({ state: 'SENT', version: 9, sent: 10 }),
  );
  assert.deepEqual(
    applyCommand(terminal, cancel, chosen).outcome,
    literal('STATE_REJECTED', { state: 'SENT', version: 9, sent: 10 }),
  );
});

void test('需求改变准确标出受影响 REQ/AC/TC，旧报告过期且各决定版本独立', () => {
  const initial = clone(initialDecisions);
  const report = runTestPlan(initial);
  assert.equal(reportIsCurrent(null, initial), false);
  assert.equal(reportIsCurrent(report, initial), true);
  assert.deepEqual(changedArtifacts(initial, clone(initial)), []);
  const cancellation = { ...initial, sendingCancel: 'reject' as const };
  assert.deepEqual(changedArtifacts(initial, cancellation), [
    'REQ-03',
    'AC-03',
    'TC-08',
    'TC-09',
  ]);
  const scheduling = { ...initial, reschedule: 'allow' as const };
  assert.deepEqual(changedArtifacts(initial, scheduling), [
    'REQ-04',
    'AC-04',
    'TC-10',
    'TC-11',
  ]);
  assert.deepEqual(changedArtifacts(initial, chosen), [
    'REQ-03',
    'AC-03',
    'TC-08',
    'TC-09',
    'REQ-04',
    'AC-04',
    'TC-10',
    'TC-11',
  ]);
  assert.equal(reportIsCurrent(report, cancellation), false);
  assert.equal(reportIsCurrent(report, scheduling), false);
  assert.equal(reportIsCurrent(report, chosen), false);
  assert.equal(reportIsCurrent(runTestPlan(chosen), chosen), true);
  const versions = new Set<string>();
  for (const sendingCancel of [
    'unresolved',
    'reject',
    'stop_remaining',
  ] as SendingCancel[])
    for (const reschedule of ['unresolved', 'allow', 'deny'] as Reschedule[])
      versions.add(decisionVersion({ sendingCancel, reschedule }));
  assert.equal(versions.size, 9);
  assert.equal(report.version, 'demo-v1/unresolved/unresolved');
});

void test('报告只包含本地模型结果；真实队列、外部邮件和页面 IT 检查未执行', () => {
  const report = runTestPlan(chosen);
  assert.equal(report.target, 'local_reference_model');
  assert.deepEqual(
    manualOnlyChecks.map((c) => c.id),
    ['IT-01', 'IT-02', 'IT-03', 'IT-04'],
  );
  for (const check of manualOnlyChecks) {
    assert.deepEqual(Object.keys(check).sort(), ['expected', 'id', 'title']);
    assert.ok(!report.rows.some((r) => r.id === check.id));
  }
  assert.match(
    manualOnlyChecks.find((c) => c.id === 'IT-02')!.expected,
    /当前待确认、未执行/,
  );
  assert.match(
    manualOnlyChecks.find((c) => c.id === 'IT-04')!.expected,
    /当前未执行/,
  );
  const started = applyCommand(
    createSimulation(),
    { type: 'start', expectedVersion: 7, now: DUE },
    chosen,
  );
  assert.equal(started.plan.sent, 0);
  assert.deepEqual(Object.keys(started).sort(), [
    'outcome',
    'plan',
    'receipts',
  ]);
  assert.deepEqual(Object.keys(started.outcome).sort(), [
    'code',
    'scheduledAt',
    'sent',
    'state',
    'stopped',
    'version',
  ]);
});

void test('明确 ISO 日历时刻不能悄悄纠正不存在的日期或接受自然语言日期', () => {
  for (const at of [
    '2026-02-30T10:00:00Z',
    '2026-02-29T10:00:00Z',
    'September 10, 2026 10:00:00Z',
  ]) {
    assert.equal(scheduleCheck(at, '2026-02-28T00:00:00Z'), 'INVALID_TIME', at);
  }
  assert.equal(
    scheduleCheck('2028-02-29T10:00:00Z', '2028-02-28T00:00:00Z'),
    'SCHEDULED',
  );
});

void test('计划与报告是本轮独立产物，修改旧产物不会污染下一次计划或已运行报告', () => {
  const plan = testCases(chosen);
  const before = clone(plan);
  const report = runTestPlan(chosen);
  const originalReport = clone(report);
  plan[0].initial.version = 999;
  plan[0].expected!.code = 'FAKE_PASS';
  plan[4].commands.push({ type: 'cancel', requestId: 'EXTRA' });
  assert.deepEqual(testCases(chosen), before);
  assert.deepEqual(report, originalReport);
  assert.deepEqual(runTestPlan(chosen), originalReport);
});

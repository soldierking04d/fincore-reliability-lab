import { buildOrderCase, buildWorkflowCase } from './model.mjs';

// 独立命令只向 stdout 输出确定性证据，不读数据库、不写文件、不连接网络。
const orderCases = ['rest', 'cancel-before-settle', 'fill-rest', 'fill-then-cancel', 'roles-reversed'].map(scenario => buildOrderCase({ scenario }));
const workflowCases = ['retry', 'compensate', 'two-phase-commit'].map(scenario => buildWorkflowCase({ scenario }));
const cases = [...orderCases, ...workflowCases];
const summary = cases.map(result => ({
  kind: result.kind,
  scenario: result.scenario,
  snapshots: result.snapshots.length,
  checksPassed: result.snapshots.every(snapshot => snapshot.checks.every(check => check.ok)),
  finalBalances: result.current.balances,
  finalJournalCount: result.current.journals.length,
  ...(result.kind === 'order' ? { feePolicy: '每笔成交锁定：Maker 5bp、Taker 10bp，从收到的资产扣除', finalExecutions: result.current.executions.map(({ tradeId, owner, role, fee, feeBps, feeAsset }) => ({ tradeId, owner, role, fee, feeBps, feeAsset })) } : {}),
}));
console.log(JSON.stringify({
  evidenceType: 'deterministic-in-memory-model',
  limitation: orderCases[0].limitation,
  passed: summary.every(result => result.checksPassed),
  summary,
  ...(process.argv.includes('--summary') ? {} : { cases }),
}, null, 2));

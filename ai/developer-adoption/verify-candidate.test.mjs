import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// 独立测试只使用已知合成源码；不下载 JDK，不以环境失败替代预期拒绝。
const root = dirname(fileURLToPath(import.meta.url));
const verifier = join(root, 'verify-candidate.mjs');
const workshop = join(root, 'workshop');
const after = join(workshop, 'after/TicketQuery.java');
const before = join(workshop, 'before/TicketQuery.java');
const overflowOnly = join(workshop, 'overflow-only/TicketQuery.java');
const digest = path => createHash('sha256').update(readFileSync(path)).digest('hex');

function execute(mode, candidate, { approve = true, extra = [], cwd = root } = {}) {
  const args = [verifier, mode, candidate, ...(approve ? ['--approve-local-execution'] : []), ...extra];
  const run = spawnSync(process.execPath, args, { cwd, encoding: 'utf8', timeout: 120_000, maxBuffer: 1_048_576 });
  assert.equal(run.error, undefined, '验证工具本身必须正常启动');
  assert.equal(run.signal, null, '验证工具不能超时或被信号终止');
  const reportPath = run.stdout.match(/^REPORT_PATH=(.+)$/mu)?.[1];
  assert.ok(reportPath, `必须返回报告位置：${run.stderr}`);
  return { exit: run.status, report: JSON.parse(readFileSync(reportPath, 'utf8')) };
}
function assertSuccessfulEnvironment(report) {
  assert.equal(report.execution.java.split('.')[0], '21');
  assert.equal(report.execution.javac.split('.')[0], '21');
  assert.equal(report.execution.candidateCompiled, true);
  assert.equal(report.execution.osSandbox, false);
  assert.equal(report.execution.networkIsolationEnforced, false);
  assert.equal(report.verifierInvokedModel, false);
  assert.equal(report.execution.externalEffectsMonitored, false);
}

void test('完整实现：after 的真实候选源码通过 12 条独立合同',()=>{
  const { exit, report } = execute('implement', after);
  assert.equal(exit,0); assert.equal(report.status,'verified');
  assertSuccessfulEnvironment(report);
  assert.equal(report.candidate.sha256,digest(after));
  assert.equal(report.summary.taskPassed,12);
  assert.equal(report.summary.contractPassed,12);
  assert.equal(report.scope.satisfied,true);
  assert.deepEqual(report.results[2].actual,{kind:'ok',page:2147483647,size:200,offset:'429496729400',query:'urgent'});
  assert.deepEqual(report.results[9].actual.query,'工单\u2003\u202f 查询');
  assert.equal(report.trustedHashes['workshop/tests/cases.json'],digest(join(workshop,'tests/cases.json')));
  assert.ok(!('trustedBefore' in report),'完整实现不借用旧版结果作为新合同');
});

void test('完整实现：before 候选失败，不把固定 after 的成绩当成候选成绩',()=>{
  const { exit, report } = execute('implement', before);
  assert.equal(exit,1); assert.equal(report.status,'candidate_rejected');
  assertSuccessfulEnvironment(report);
  assert.equal(report.candidate.sha256,digest(before));
  assert.equal(report.summary.contractPassed,4);
  assert.equal(report.summary.taskFailed,8);
  assert.equal(report.results[2].actual.offset,'-200');
  assert.equal(report.results[2].expected.offset,'429496729400');
  assert.equal(report.scope.satisfied,false);
});

void test('单缺陷修复：overflow-only 任务通过，完整合同只能报告 5/12',()=>{
  const { exit, report } = execute('repair', overflowOnly);
  assert.equal(exit,0); assert.equal(report.status,'verified');
  assertSuccessfulEnvironment(report);
  assert.equal(report.candidate.sha256,digest(overflowOnly));
  assert.deepEqual(report.summary,{total:12,taskPassed:12,taskFailed:0,contractPassed:5,contractFailed:7});
  assert.equal(report.trustedBefore.contractPassed,4);
  assert.equal(report.scope.requirement,'only_P03_changes');
  assert.deepEqual(report.scope.changedNonTargetIds,[]);
  assert.equal(report.results[2].beforeActual.offset,'-200');
  assert.equal(report.results[2].actual.offset,'429496729400');
  for(const row of report.results.filter(r=>r.id!=='P03'))assert.deepEqual(row.actual,row.beforeActual,row.id);
});

void test('单缺陷修复：after 虽满足完整合同，额外改变七项行为仍被拒绝',()=>{
  const { exit, report } = execute('repair', after);
  assert.equal(exit,1); assert.equal(report.status,'candidate_rejected');
  assertSuccessfulEnvironment(report);
  assert.equal(report.summary.contractPassed,12);
  assert.equal(report.summary.taskPassed,5);
  assert.deepEqual(report.scope.changedNonTargetIds,['P05','P06','P07','P08','P09','P10','P12']);
  assert.equal(report.scope.satisfied,false);
  assert.equal(report.results[2].pass,true,'目标缺陷已修复，但范围仍超出任务');
});

void test('单缺陷修复：未修的 before 不因其他基线通过而假绿',()=>{
  const { exit, report } = execute('repair', before);
  assert.equal(exit,1); assert.equal(report.status,'candidate_rejected');
  assertSuccessfulEnvironment(report);
  assert.equal(report.summary.taskPassed,11);
  assert.equal(report.summary.contractPassed,4);
  assert.equal(report.results[2].pass,false);
  assert.deepEqual(report.scope.changedNonTargetIds,[]);
});

void test('编译失败必须退出并标记环境/执行错误，不能算候选预期红',()=>{
  const folder=mkdtempSync(join(tmpdir(),'candidate-compile-error-'));
  const candidate=join(folder,'TicketQuery.java');
  writeFileSync(candidate,'public final class TicketQuery { 故意造成语法错误 }\n');
  const { exit, report } = execute('implement',candidate);
  assert.equal(exit,1); assert.equal(report.status,'infrastructure_error');
  assert.equal(report.error.code,'PROCESS_FAILED');
  assert.match(report.error.message,/candidate 编译失败/);
  assert.equal(report.error.countsAsExpectedFailure,false);
  assert.equal(report.execution.candidateCompiled,false);
  assert.deepEqual(report.results,[]);
});

void test('没有显式确认时，即使提供坏 JDK 路径也先拒绝执行',()=>{
  const { exit, report } = execute('implement',after,{approve:false,extra:['--jdk','/nonexistent-candidate-jdk']});
  assert.equal(exit,1); assert.equal(report.status,'execution_not_approved');
  assert.equal(report.error.code,'APPROVAL_REQUIRED');
  assert.equal(report.execution.approved,false);
  assert.equal(report.execution.candidateCompiled,false);
  assert.equal(report.candidate.sha256,null,'未确认时不读取候选');
  assert.ok(!('java' in report.execution),'未确认时不启动 JDK 检查');
  assert.deepEqual(report.results,[]);
});

void test('缺少 JDK 报错，不计入任何候选成功或失败用例',()=>{
  const { exit, report } = execute('implement',after,{extra:['--jdk','/nonexistent-candidate-jdk']});
  assert.equal(exit,1); assert.equal(report.status,'infrastructure_error');
  assert.equal(report.error.countsAsExpectedFailure,false);
  assert.equal(report.execution.candidateCompiled,false);
  assert.deepEqual(report.results,[]);
});

void test('候选目录的自带测试和伪探针不参与验收，当前工作目录不改变受信来源',()=>{
  const folder=mkdtempSync(join(tmpdir(),'candidate-untrusted-tests-'));
  const candidate=join(folder,'TicketQuery.java');
  writeFileSync(candidate,readFileSync(before));
  mkdirSync(join(folder,'tests'));
  writeFileSync(join(folder,'tests/cases.json'),'[]\n');
  writeFileSync(join(folder,'QueryProbe.java'),'这不是合法探针，不能被读取或编译\n');
  const { exit, report } = execute('implement',candidate,{cwd:folder});
  assert.equal(exit,1); assert.equal(report.status,'candidate_rejected');
  assertSuccessfulEnvironment(report);
  assert.equal(report.results.length,12);
  assert.equal(report.summary.contractPassed,4);
  assert.equal(report.trustedHashes['workshop/tests/QueryProbe.java'],digest(join(workshop,'tests/QueryProbe.java')));
  assert.equal(report.results[2].actual.offset,'-200');
});

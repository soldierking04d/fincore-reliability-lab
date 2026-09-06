import assert from 'node:assert/strict';
import { cpSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const here = dirname(fileURLToPath(import.meta.url));

function execute(root, args=[]) {
  const child = spawnSync(process.execPath, [join(root, 'run-workshop.mjs'), ...args], {
    encoding: 'utf8', timeout: 120_000,
  });
  assert.ifError(child.error);
  const path = child.stdout.match(/JSON 报告：([^\r\n]+)/u)?.[1];
  assert.ok(path, '必须有实际报告，不能只检查退出状态');
  return { exit: child.status, report: JSON.parse(readFileSync(path, 'utf8')) };
}

test('缺 JDK 是基础环境错误，不能当作红灯复现', () => {
  const absent = join(mkdtempSync(join(tmpdir(), 'ai-runner-no-jdk-')), 'missing-jdk');
  const result = execute(join(here, 'workshop'), ['--jdk', absent]);
  assert.equal(result.exit, 1);
  assert.equal(result.report.status, 'infrastructure_error');
  assert.equal(result.report.error.countsAsExpectedFailure, false);
});

test('旧版不再含目标溢出时，即使还有其他失败也不能假绿', () => {
  // 故障注入仅作用于新建临时副本；原源码与固定用例不变。
  const copy = join(mkdtempSync(join(tmpdir(), 'ai-runner-mutation-')), 'workshop');
  cpSync(join(here, 'workshop'), copy, { recursive: true });
  const source = join(copy, 'before/TicketQuery.java');
  const old = readFileSync(source, 'utf8');
  assert.ok(old.includes('int offset = page * size;'));
  writeFileSync(source, old.replace('int offset = page * size;', 'long offset = (long) page * size;'));
  const result = execute(copy);
  assert.equal(result.exit, 1);
  assert.equal(result.report.status, 'verification_failed');
  assert.equal(result.report.contract.overflowReproducedExactly, false);
  assert.ok(result.report.variants[0].failed > 0);
});

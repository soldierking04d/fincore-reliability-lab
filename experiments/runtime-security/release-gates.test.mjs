import test from 'node:test';
import assert from 'node:assert/strict';
import { evaluateRelease } from './release-gates.mjs';
const digest = `sha256:${'a'.repeat(64)}`;
const base = () => ({ imageDigest: digest, generation: 'patched-1', expectedWorkers: ['web-1', 'web-2'],
  workers: ['web-1', 'web-2'].map(id => ({ id, sapi: 'fpm-fcgi', generation: 'patched-1', imageDigest: digest, uploadProgressEnabled: false })),
  patchEvidenceVerified: true, rollbackPatched: true });
test('完整记录通过；空记录、空范围、重复 Worker 均不能通过', () => {
  assert.equal(evaluateRelease(base()).passed, true);
  for (const record of [null, {}, { ...base(), expectedWorkers: {} }, { ...base(), expectedWorkers: [] }, { ...base(), workers: [null] }, { ...base(), workers: [base().workers[0], base().workers[0]] },
    { ...base(), workers: [...base().workers, { ...base().workers[0], id: 'untracked-old-worker' }] }]) {
    assert.equal(evaluateRelease(record).passed, false);
  }
});
test('CLI 版本正确也不能代替实际 Web Worker 配置', () => {
  const record = base(); record.workers[0].sapi = 'cli';
  assert.equal(evaluateRelease(record).passed, false);
});
test('缺一个 Worker、残留旧代际或摘要不符均阻断交接', () => {
  for (const key of ['generation', 'imageDigest']) {
    const record = base(); record.workers[1][key] = 'old';
    assert.equal(evaluateRelease(record).passed, false);
  }
  const missing = base(); missing.workers.pop();
  assert.equal(evaluateRelease(missing).passed, false);
});
test('功能关闭声明不能替代补丁证据，未修补回退制品也被拒绝', () => {
  for (const key of ['patchEvidenceVerified', 'rollbackPatched']) assert.equal(evaluateRelease({ ...base(), [key]: false }).passed, false);
  const record = base(); record.workers[1].uploadProgressEnabled = true;
  assert.equal(evaluateRelease(record).passed, false);
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { assessPhpAsset, demonstrateLifecycle, fixedBranches } from './php-policy.mjs';

const base = { phpVersion: '8.4.27', distribution: 'upstream', platform: 'linux', uploadProgressEnabled: false, workersReplaced: true, brotliLoaded: false };
const finding = (overrides, id) => assessPhpAsset({ ...base, ...overrides }).findings.find(f => f.id === id);

test('四个维护分支准确区分补丁边界，不能用浮点版本比较', () => {
  for (const [branch, patch] of Object.entries(fixedBranches)) {
    assert.equal(finding({ phpVersion: `${branch}.${patch - 1}` }, 'core').status, 'action');
    assert.equal(finding({ phpVersion: `${branch}.${patch}` }, 'core').status, 'covered');
    assert.equal(finding({ phpVersion: `${branch}.${patch + 1}` }, 'core').status, 'covered');
  }
});
test('未知、RC、发行版后缀与未来分支不会自动判为通过', () => {
  for (const phpVersion of ['', 'garbage', '8.5.12RC1', '8.4.27-ubuntu1', '8.6.0', '9007199254740992.4.27']) {
    assert.equal(finding({ phpVersion }, 'core').status, 'verify');
  }
  assert.equal(assessPhpAsset().status, 'verify');
});
test('发行版回补需要供应商证据，不因低版本误报也不因高版本放行', () => {
  for (const phpVersion of ['8.3.30', '8.5.12']) assert.equal(finding({ distribution: 'vendor', phpVersion }, 'core').status, 'verify');
  assert.equal(finding({ distribution: 'vendor', brotliLoaded: true, brotliVersion: '1.2.0' }, 'brotli').status, 'verify');
});
test('不维护的上游分支需要迁移', () => {
  for (const phpVersion of ['7.4.33', '8.0.30', '8.1.34']) assert.equal(finding({ phpVersion }, 'core').status, 'action');
});
test('核心版本已修复不抵消上传进度与旧 Worker 待办', () => {
  assert.equal(assessPhpAsset({ ...base, uploadProgressEnabled: true }).status, 'action');
  assert.equal(assessPhpAsset({ ...base, workersReplaced: false }).status, 'action');
  assert.equal(finding({ uploadProgressEnabled: 'false' }, 'upload-progress').status, 'verify');
  assert.equal(finding({ workersReplaced: 'true' }, 'worker-rollout').status, 'verify');
});
test('Windows 原始包与同版本 -1 重打包必须分开判断', () => {
  assert.equal(finding({ platform: 'windows', brotliLoaded: true }, 'brotli').status, 'action');
  assert.equal(finding({ platform: 'windows', windowsBuild: '8.4.27-1', brotliLoaded: true }, 'brotli').status, 'covered');
  assert.equal(finding({ platform: 'windows', windowsBuild: '8.5.12-1', brotliLoaded: true }, 'brotli').status, 'verify');
  assert.equal(finding({ platform: 'windows', windowsBuild: '8.4.27-2', brotliLoaded: true }, 'brotli').status, 'verify');
});
test('其他平台的 Brotli 也按加载依赖核验', () => {
  assert.equal(finding({ brotliLoaded: true, brotliVersion: '1.2.0' }, 'brotli').status, 'action');
  assert.equal(finding({ brotliLoaded: true, brotliVersion: '1.2.1' }, 'brotli').status, 'covered');
  assert.equal(finding({ brotliLoaded: true }, 'brotli').status, 'verify');
});
test('缺失资产不会被宣称为完整安全，非法数据拒绝', () => {
  assert.match(assessPhpAsset(base).scope, /不是主机扫描/);
  assert.equal(assessPhpAsset(base).status, 'covered');
  assert.throws(() => assessPhpAsset(null), TypeError);
  assert.throws(() => assessPhpAsset([]), TypeError);
});
test('负例暴露残留状态，安全路径在成功和失败后都解除 Worker 引用', () => {
  assert.equal(demonstrateLifecycle({ safe: false }).staleState, true);
  for (const failInitialization of [true, false]) {
    const result = demonstrateLifecycle({ safe: true, failInitialization });
    assert.equal(result.invariantHeld, true);
    assert.equal(result.events[0].published, false);
    assert.equal(result.events.at(-1).published, false);
  }
});

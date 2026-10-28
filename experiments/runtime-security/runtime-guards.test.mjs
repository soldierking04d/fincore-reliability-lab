import test from 'node:test';
import assert from 'node:assert/strict';
import { gzipSync } from 'node:zlib';
import { currentTenant, inTenant, observeConcurrentTenants, decodeGzipBounded, DecoderPool } from './runtime-guards.mjs';

test('交错请求的全局变量负例串用身份，异步作用域保留各自租户', async () => {
  assert.deepEqual(await observeConcurrentTenants({ isolated: false }), ['B', 'B']);
  assert.deepEqual(await observeConcurrentTenants({ isolated: true }), ['A', 'B']);
  assert.equal(currentTenant(), null);
});
test('异步抛错不遗留根上下文，嵌套作用域返回原身份', async () => {
  await assert.rejects(inTenant('A', async () => { await Promise.resolve(); throw new Error('受控失败'); }), /受控失败/);
  assert.equal(currentTenant(), null);
  await inTenant('A', async () => {
    await inTenant('B', async () => assert.equal(currentTenant(), 'B'));
    assert.equal(currentTenant(), 'A');
  });
});
test('正常内容可按多个输入块解压，恰好达到输出预算仍成功', async () => {
  const plain = Buffer.from('账户导入测试');
  const compressed = gzipSync(plain);
  const result = await decodeGzipBounded([compressed.subarray(0, 5), compressed.subarray(5)], { outputLimit: plain.length });
  assert.deepEqual(result.body, plain);
  assert.equal(result.compressedBytes, compressed.length);
  assert.equal(result.decodedBytes, plain.length);
});
test('小压缩包的放大输出被拒绝，调用方收不到部分成功结果', async () => {
  const compressed = gzipSync(Buffer.alloc(128 * 1024, 'A'));
  assert(compressed.length < 1024);
  await assert.rejects(decodeGzipBounded([compressed], { outputLimit: 32768 }), { code: 'OUTPUT_LIMIT' });
});
test('实际输入计量不依赖声明长度；截断数据不能返回半成品', async () => {
  const compressed = gzipSync(Buffer.from('完整性测试'));
  await assert.rejects(decodeGzipBounded([compressed], { compressedLimit: compressed.length - 1 }), { code: 'COMPRESSED_LIMIT' });
  await assert.rejects(decodeGzipBounded([compressed.subarray(0, -4)]));
});
test('取消信号和非法预算不会交付成功结果', async () => {
  await assert.rejects(decodeGzipBounded([gzipSync(Buffer.from('x'))], { signal: AbortSignal.abort() }), { name: 'AbortError' });
  await assert.rejects(decodeGzipBounded([], { outputLimit: Infinity }), RangeError);
});
test('并发名额满时明确拒绝，成功与失败后均归还名额', async () => {
  const pool = new DecoderPool(1);
  let release;
  const held = pool.run(() => new Promise(resolve => { release = resolve; }));
  assert.equal(pool.active, 1);
  await assert.rejects(pool.run(async () => 2), { code: 'BUSY' });
  release(1); await held;
  await assert.rejects(pool.run(async () => { throw new Error('受控失败'); }), /受控失败/);
  assert.equal(pool.active, 0);
  assert.equal(await pool.run(async () => 3), 3);
});

import { AsyncLocalStorage } from 'node:async_hooks';
import { Readable, Transform, Writable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import { createGunzip } from 'node:zlib';

const tenantContext = new AsyncLocalStorage();

/** 显式建立异步请求作用域；run 的回调抛错时仍向调用方传播，不伪造业务成功。 */
export function inTenant(tenant, action) {
  if (typeof tenant !== 'string' || !tenant) throw new TypeError('需要明确的租户身份');
  return tenantContext.run(Object.freeze({ tenant }), action);
}
export function currentTenant() {
  return tenantContext.getStore()?.tenant ?? null;
}

/** 使用两个闸门确定性安排交错执行，不靠随机 sleep 制造偶发通过。 */
export async function observeConcurrentTenants({ isolated = true } = {}) {
  let announceA;
  let announceB;
  const aEntered = new Promise(resolve => { announceA = resolve; });
  const bEntered = new Promise(resolve => { announceB = resolve; });
  let globalTenant = null;
  async function a() {
    globalTenant = 'A'; announceA(); await bEntered;
    return isolated ? currentTenant() : globalTenant;
  }
  async function b() {
    await aEntered; globalTenant = 'B'; announceB();
    return isolated ? currentTenant() : globalTenant;
  }
  return Promise.all([isolated ? inTenant('A', a) : a(), isolated ? inTenant('B', b) : b()]);
}

function failure(code, message) {
  return Object.assign(new Error(message), { code });
}

/**
 * 每个进程的并发解压预算。队列满时拒绝，而不是无限排队；成功/失败都归还名额。
 * 多进程入口仍需要各进程预算和上游总预算，这个计数器不能当成分布式限流。
 */
export class DecoderPool {
  #active = 0;
  constructor(limit) {
    if (!Number.isSafeInteger(limit) || limit < 1) throw new RangeError('并发预算必须为正整数');
    this.limit = limit;
  }
  get active() { return this.#active; }
  async run(action) {
    if (this.#active >= this.limit) throw failure('BUSY', '解压并发预算已满');
    this.#active += 1;
    try { return await action(); } finally { this.#active -= 1; }
  }
}

/**
 * 按实际流经的字节分别计量压缩输入与解压输出；只有完整成功才向调用方交付结果。
 * 背压、终止传播由 pipeline 管理。上限是逻辑字节预算，峰值内存还包括 zlib、流缓冲和合并副本。
 * 这是资源耗尽防御示例，不能修补原生解码器的越界写；原生库升级是独立前提。
 */
export async function decodeGzipBounded(chunks, { compressedLimit = 65536, outputLimit = 32768, signal } = {}) {
  for (const limit of [compressedLimit, outputLimit]) {
    if (!Number.isSafeInteger(limit) || limit < 1) throw new RangeError('字节预算必须为正整数');
  }
  let compressedBytes = 0;
  let decodedBytes = 0;
  const staged = [];
  async function* countedInput() {
    for await (const chunk of chunks) {
      if (!(chunk instanceof Uint8Array)) throw new TypeError('输入流必须交付字节块');
      compressedBytes += chunk.byteLength;
      if (compressedBytes > compressedLimit) throw failure('COMPRESSED_LIMIT', '实际压缩输入超过预算');
      yield chunk;
    }
  }
  const outputBudget = new Transform({
    transform(chunk, encoding, callback) {
      decodedBytes += chunk.length;
      if (decodedBytes > outputLimit) { callback(failure('OUTPUT_LIMIT', '实际解压输出超过预算')); return; }
      callback(null, chunk);
    },
  });
  const collector = new Writable({ write(chunk, encoding, callback) { staged.push(chunk); callback(); } });
  await pipeline(Readable.from(countedInput()), createGunzip({ chunkSize: 4096 }), outputBudget, collector, { signal });
  return { body: Buffer.concat(staged), compressedBytes, decodedBytes };
}

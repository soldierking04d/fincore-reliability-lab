import https from 'node:https';
import { performance } from 'node:perf_hooks';

const ENDPOINT = 'https://api.mainnet.solana.com';
const GENESIS = '5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d';
const RESPONSE_LIMIT = 1_048_576;
const REQUEST_LIMIT = 65_536;
const DEADLINE_MS = 10_000;
const METHODS = new Set(['getGenesisHash', 'getAccountInfo', 'getMultipleAccounts', 'getBalance',
  'getLatestBlockhash', 'getBlockHeight', 'isBlockhashValid', 'getFeeForMessage',
  'getSignatureStatuses', 'getTransaction', 'simulateTransaction']);
const MESSAGES = Object.freeze({
  RPC_METHOD_FORBIDDEN: '此入口不允许该链上操作。',
  RPC_PARAMS_INVALID: '只读查询参数无效。',
  RPC_REQUEST_TOO_LARGE: '只读查询参数超过大小限制。',
  RPC_JSON_INVALID: '数据不是严格有效且数值安全的 JSON。',
  RPC_RESPONSE_TOO_LARGE: '节点响应超过大小限制。',
  RPC_ENVELOPE_INVALID: '节点响应格式或请求编号不匹配。',
  RPC_RESULT_UNAVAILABLE: '节点尚未提供结果，不能据此判断执行成败。',
  RPC_REMOTE_ERROR: '节点拒绝本次只读查询或模拟。',
  RPC_HTTP_REJECTED: '节点没有返回正常的 HTTP 响应。',
  RPC_TRANSPORT_FAILED: '只读网络请求未能完成，请稍后手动检查。',
  RPC_CLUSTER_MISMATCH: '节点网络身份与指定主网不符。',
  RPC_OVERLOADED: '只读查询已达到并发上限，请稍后再试。',
  RPC_DEADLINE_EXCEEDED: '只读查询超过十秒，结果仍未知。',
  RPC_SIMULATION_FORBIDDEN: '仅允许已核对格式的单付款者零签名 legacy 模拟。',
  RPC_ID_EXHAUSTED: '只读请求编号已耗尽，请重新启动独立服务。',
});
class RpcError extends Error {
  constructor(code) { super(MESSAGES[code]); this.name = 'RpcError'; this.code = code; }
}
const fail = code => { throw new RpcError(code); };
const own = (object, key) => Object.prototype.hasOwnProperty.call(object, key);
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
let inFlight = 0;
let sequence = 0;

/**
 * 与本地 HTTP 入口共用的严格 JSON 解析器，限制文档大小、嵌套深度和节点数量。
 * 拒绝解码后重复的字段名、整数精度丢失和尾随文档，不向调用方暴露原始解析异常。
 * uiAmount 等展示字段允许小数；上层资金计算必须使用最小单位十进制字符串和 BigInt。
 * 只有 RPC 响应解析会显式启用大整数字面量转精确十进制字符串，例如 u64 类型的
 * rentEpoch；HTTP 请求默认拒绝超出安全范围的整数，不能通过舍入结果绕过金额检查。
 */
export function parseStrictJson(text, { unsafeIntegers = 'reject' } = {}) {
  const invalid = () => fail('RPC_JSON_INVALID');
  if (unsafeIntegers !== 'reject' && unsafeIntegers !== 'string') invalid();
  if (typeof text !== 'string' || Buffer.byteLength(text, 'utf8') > RESPONSE_LIMIT) invalid();
  let position = 0;
  let nodes = 0;
  const whitespace = () => { while (position < text.length && /[\x20\t\r\n]/.test(text[position])) position++; };
  function string() {
    const start = position++;
    while (position < text.length) {
      const char = text.charCodeAt(position++);
      if (char === 34) {
        let value;
        try { value = JSON.parse(text.slice(start, position)); } catch { invalid(); }
        if (value.length > 262_144) invalid();
        return value;
      }
      if (char < 32) invalid();
      if (char === 92) {
        if (position >= text.length) invalid();
        position++;
      }
    }
    invalid();
  }
  function value(depth) {
    if (depth > 32 || ++nodes > 100_000) invalid();
    whitespace();
    const char = text[position];
    if (char === '"') return string();
    if (char === '{') {
      position++; whitespace();
      const result = {};
      const keys = new Set();
      if (text[position] === '}') { position++; return result; }
      for (;;) {
        whitespace(); if (text[position] !== '"') invalid();
        const key = string();
        if (key.length > 128 || keys.has(key)) invalid();
        keys.add(key); whitespace();
        if (text[position++] !== ':') invalid();
        // 将字段定义为对象自身的数据属性；即使名称为 __proto__，也不会触发原型设置器。
        Object.defineProperty(result, key, { value: value(depth + 1), enumerable: true, writable: true, configurable: true });
        whitespace();
        const delimiter = text[position++];
        if (delimiter === '}') return result;
        if (delimiter !== ',') invalid();
      }
    }
    if (char === '[') {
      position++; whitespace();
      const result = [];
      if (text[position] === ']') { position++; return result; }
      for (;;) {
        result.push(value(depth + 1)); whitespace();
        const delimiter = text[position++];
        if (delimiter === ']') return result;
        if (delimiter !== ',') invalid();
      }
    }
    for (const [literal, decoded] of [['true', true], ['false', false], ['null', null]]) {
      if (text.startsWith(literal, position)) { position += literal.length; return decoded; }
    }
    const match = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/.exec(text.slice(position));
    if (!match || match[0].length > 80) invalid();
    const token = match[0];
    const number = Number(token);
    if (!Number.isFinite(number)) invalid();
    position += token.length;
    if (/^-?\d+$/.test(token) && !Number.isSafeInteger(number)) {
      if (unsafeIntegers !== 'string') invalid();
      return BigInt(token).toString();
    }
    if (Number.isInteger(number)) {
      if (!Number.isSafeInteger(number)) invalid();
      // 拒绝非整数小数或极小数被二进制浮点舍入成整数的情况，避免数值检查误认其为整数。
      const [mantissa, exponent = '0'] = token.toLowerCase().split('e');
      const fractionalDigits = (mantissa.split('.')[1] ?? '').length;
      const digits = mantissa.replace(/[-.]/g, '').replace(/^0+/, '');
      const scale = Number(exponent) - fractionalDigits;
      const zeroes = digits.match(/0*$/)[0].length;
      if (digits && scale < 0 && zeroes < -scale) invalid();
    }
    return number;
  }
  const result = value(1); whitespace();
  if (position !== text.length) invalid();
  return result;
}

/** 请求参数必须是有界 JSON 数据；先复制再等待网络响应，避免调用方中途改变已检查内容。 */
function snapshotParams(params) {
  if (!Array.isArray(params)) fail('RPC_PARAMS_INVALID');
  const ancestors = new Set();
  let nodes = 0;
  let characters = 0;
  function clone(value, depth) {
    if (++nodes > 100_000 || depth > 30) fail('RPC_PARAMS_INVALID');
    if (value === null || typeof value === 'boolean') return value;
    if (typeof value === 'string') {
      characters += value.length;
      if (characters + nodes > REQUEST_LIMIT) fail('RPC_REQUEST_TOO_LARGE');
      return value;
    }
    if (typeof value === 'number') {
      if (!Number.isFinite(value) || (Number.isInteger(value) && !Number.isSafeInteger(value))) fail('RPC_PARAMS_INVALID');
      return value;
    }
    if (typeof value !== 'object' || ancestors.has(value)) fail('RPC_PARAMS_INVALID');
    const array = Array.isArray(value);
    if (!array && ![Object.prototype, null].includes(Object.getPrototypeOf(value))) fail('RPC_PARAMS_INVALID');
    ancestors.add(value);
    const copy = array ? [] : {};
    if (array && value.length > 100_000) fail('RPC_REQUEST_TOO_LARGE');
    const keys = array ? Array.from({ length: value.length }, (_, index) => String(index)) : Object.keys(value);
    for (const key of keys) {
      if (key.length > 128) fail('RPC_PARAMS_INVALID');
      characters += key.length;
      if (characters + nodes > REQUEST_LIMIT) fail('RPC_REQUEST_TOO_LARGE');
      const descriptor = Object.getOwnPropertyDescriptor(value, key);
      if (!descriptor || !own(descriptor, 'value')) fail('RPC_PARAMS_INVALID');
      Object.defineProperty(copy, key, { value: clone(descriptor.value, depth + 1), enumerable: true, writable: true, configurable: true });
    }
    ancestors.delete(value);
    return copy;
  }
  const copied = clone(params, 1);
  if (Buffer.byteLength(JSON.stringify(copied)) > REQUEST_LIMIT) fail('RPC_REQUEST_TOO_LARGE');
  return copied;
}

/**
 * 这里只检查模拟参数及交易结构：仅允许一个付款者、全零签名和 legacy 消息，
 * 拒绝版本化交易、持久 nonce、重复账户、越界索引、非规范长度和尾随字节。
 * 结构合法不等于账户、指令或资金用途已获批准；DEX 层仍须独立核对完整业务合同，
 * 包括账户权限、指令数据和资金差额。本检查不提供签名授权、广播许可或资金白名单。
 */
function validateSimulation(params) {
  const invalid = () => fail('RPC_SIMULATION_FORBIDDEN');
  if (params.length !== 2 || typeof params[0] !== 'string' || !object(params[1])) invalid();
  const [encoded, config] = params;
  if (config.encoding !== 'base64' || config.commitment !== 'confirmed'
    || config.sigVerify !== false || config.replaceRecentBlockhash !== false) invalid();
  if (encoded.length === 0 || encoded.length > 1644) invalid();
  const bytes = Buffer.from(encoded, 'base64');
  if (bytes.length > 1232 || bytes.toString('base64') !== encoded) invalid();
  let position = 0;
  const byte = () => { if (position >= bytes.length) invalid(); return bytes[position++]; };
  const take = length => {
    if (length < 0 || length > bytes.length - position) invalid();
    const result = bytes.subarray(position, position + length); position += length; return result;
  };
  function compact() {
    let value = 0;
    for (let index = 0; index < 3; index++) {
      const next = byte();
      if ((index > 0 && next === 0) || (index === 2 && (next & 252) !== 0)) invalid();
      value |= (next & 127) << (7 * index);
      if ((next & 128) === 0) return value;
    }
    invalid();
  }
  if (compact() !== 1 || take(64).some(value => value !== 0)) invalid();
  if (byte() !== 1 || byte() !== 0) invalid(); // 仅接受一个可写签名者及 legacy 消息头。
  const readonlyUnsigned = byte();
  const count = compact();
  if (count < 1 || count > 256 || readonlyUnsigned > count - 1 || count > (bytes.length - position) / 32) invalid();
  const accounts = [];
  const seen = new Set();
  for (let index = 0; index < count; index++) {
    const key = take(32).toString('hex');
    if (seen.has(key)) invalid();
    seen.add(key); accounts.push(key);
  }
  take(32);
  const instructions = compact();
  if (instructions > (bytes.length - position) / 3) invalid();
  for (let index = 0; index < instructions; index++) {
    const program = byte();
    if (program === 0 || program >= count) invalid();
    const references = compact();
    if (references > bytes.length - position) invalid();
    for (let account = 0; account < references; account++) if (byte() >= count) invalid();
    const data = take(compact());
    if (accounts[program] === '00'.repeat(32) && data.length >= 4 && data.readUInt32LE(0) === 4) invalid();
  }
  if (position !== bytes.length) invalid();
}

/**
 * 原生 HTTPS 只向固定主网主机及路径发送一次请求，不接受调用方提供 URL 或代理配置。
 * agent=false 使用独立连接，不复用全局连接池及其代理设置；不跟随重定向或失败重试。
 * 响应按块累计并在超过一 MiB 时立即销毁连接；只有完整响应体结束才返回结果。
 * 上层共享的取消信号会终止仍在等待响应头或响应体的请求，原始网络异常统一脱敏。
 */
function nativeTransport({ body, signal }) {
  return new Promise((resolve, reject) => {
    let done = false;
    let response;
    let request;
    const finish = (error, reply) => {
      if (done) return;
      done = true;
      if (error) {
        response?.destroy(); request?.destroy(); reject(error);
      } else resolve(reply);
    };
    request = https.request({ hostname: 'api.mainnet.solana.com', port: 443, path: '/',
      method: 'POST', agent: false, signal, maxHeaderSize: 16_384,
      headers: { 'Content-Type': 'application/json', Accept: 'application/json',
        'Accept-Encoding': 'identity', 'Content-Length': body.length } }, incoming => {
      response = incoming;
      if (incoming.statusCode !== 200) return finish(new RpcError('RPC_HTTP_REJECTED'));
      if (incoming.headers['content-encoding'] && incoming.headers['content-encoding'] !== 'identity') {
        return finish(new RpcError('RPC_TRANSPORT_FAILED'));
      }
      const declared = incoming.headers['content-length'];
      if (declared !== undefined && (!/^\d+$/.test(declared) || Number(declared) > RESPONSE_LIMIT)) {
        return finish(new RpcError('RPC_RESPONSE_TOO_LARGE'));
      }
      let size = 0;
      const chunks = [];
      incoming.on('data', chunk => {
        if (done) return;
        if (chunk.length > RESPONSE_LIMIT - size) return finish(new RpcError('RPC_RESPONSE_TOO_LARGE'));
        size += chunk.length; chunks.push(chunk);
      });
      incoming.on('end', () => {
        if (!incoming.complete) return finish(new RpcError('RPC_TRANSPORT_FAILED'));
        finish(null, { statusCode: incoming.statusCode, body: Buffer.concat(chunks, size) });
      });
      incoming.on('aborted', () => finish(new RpcError('RPC_TRANSPORT_FAILED')));
      incoming.on('error', () => finish(new RpcError('RPC_TRANSPORT_FAILED')));
      incoming.on('close', () => { if (!done) finish(new RpcError('RPC_TRANSPORT_FAILED')); });
    });
    request.on('error', () => finish(new RpcError('RPC_TRANSPORT_FAILED')));
    request.end(body);
  });
}

/**
 * 固定主网的只读入口，方法白名单不包含签名、广播或空投操作。
 * 所有实例共享四个逻辑调用名额，超额立即拒绝；成功或异常结束都会归还名额。
 * 每次调用先核对完整 genesis，再读取目标方法；两次请求共用十秒截止时间，
 * 包含完整响应体读取和解析。超时会取消底层请求，结果仍未知，不自动重试。
 * transport 注入仅供离线测试，接收 {endpoint, body, signal, timeoutMs} 并返回原始响应。
 * 本入口及其结果都不修改 PAPER 账本，也不构成真实资金执行授权。
 */
export function createReadOnlyRpc({ transport = nativeTransport } = {}) {
  if (typeof transport !== 'function') fail('RPC_PARAMS_INVALID');
  return async function rpc(method, params) {
    if (!METHODS.has(method)) fail('RPC_METHOD_FORBIDDEN');
    const copied = snapshotParams(params);
    if (method === 'getGenesisHash' && copied.length !== 0) fail('RPC_PARAMS_INVALID');
    if (method === 'simulateTransaction') validateSimulation(copied);
    if (inFlight >= 4) fail('RPC_OVERLOADED');
    inFlight++;
    const controller = new AbortController();
    const deadline = performance.now() + DEADLINE_MS;
    let timer;
    const remaining = () => {
      const milliseconds = deadline - performance.now();
      if (controller.signal.aborted || milliseconds <= 0) fail('RPC_DEADLINE_EXCEEDED');
      return milliseconds;
    };
    async function exchange(rpcMethod, rpcParams) {
      remaining();
      if (sequence >= Number.MAX_SAFE_INTEGER) fail('RPC_ID_EXHAUSTED');
      const id = ++sequence;
      const body = Buffer.from(JSON.stringify({ jsonrpc: '2.0', id, method: rpcMethod, params: rpcParams }));
      if (body.length > REQUEST_LIMIT) fail('RPC_REQUEST_TOO_LARGE');
      const reply = await transport({ endpoint: ENDPOINT, body, signal: controller.signal, timeoutMs: remaining() });
      remaining();
      if (!reply || reply.statusCode !== 200) fail('RPC_HTTP_REJECTED');
      if (!(typeof reply.body === 'string' || Buffer.isBuffer(reply.body) || reply.body instanceof Uint8Array)) fail('RPC_ENVELOPE_INVALID');
      const bytes = Buffer.from(reply.body);
      if (bytes.length > RESPONSE_LIMIT) fail('RPC_RESPONSE_TOO_LARGE');
      let text;
      try { text = new TextDecoder('utf-8', { fatal: true }).decode(bytes); } catch { fail('RPC_JSON_INVALID'); }
      const envelope = parseStrictJson(text, { unsafeIntegers: 'string' });
      remaining();
      if (!object(envelope) || envelope.jsonrpc !== '2.0' || !Number.isSafeInteger(envelope.id) || envelope.id !== id) fail('RPC_ENVELOPE_INVALID');
      if (own(envelope, 'error')) {
        if (own(envelope, 'result') || !object(envelope.error) || !Number.isSafeInteger(envelope.error.code)
          || typeof envelope.error.message !== 'string') fail('RPC_ENVELOPE_INVALID');
        fail('RPC_REMOTE_ERROR');
      }
      if (!own(envelope, 'result')) fail('RPC_ENVELOPE_INVALID');
      if (envelope.result === null) fail('RPC_RESULT_UNAVAILABLE');
      return envelope.result;
    }
    try {
      const timeout = new Promise((_, reject) => {
        timer = setTimeout(() => { controller.abort(); reject(new RpcError('RPC_DEADLINE_EXCEEDED')); }, DEADLINE_MS);
      });
      const operation = (async () => {
        const genesis = await exchange('getGenesisHash', []);
        if (genesis !== GENESIS) fail('RPC_CLUSTER_MISMATCH');
        return method === 'getGenesisHash' ? genesis : exchange(method, copied);
      })();
      return await Promise.race([operation, timeout]);
    } catch (error) {
      if (error instanceof RpcError) throw error;
      throw new RpcError('RPC_TRANSPORT_FAILED');
    } finally {
      // 清理计时器和未完成请求；无论请求、解析或校验在哪一步失败，都必须归还并发名额。
      clearTimeout(timer); controller.abort(); inFlight--;
    }
  };
}

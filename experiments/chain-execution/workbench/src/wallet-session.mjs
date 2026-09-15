// 钱包标准只用于发现、用户主动连接与账户事件；本模块不提供资产执行能力。
// 协议依据：https://github.com/wallet-standard/wallet-standard/tree/master/packages/core
const MAINNET = 'solana:mainnet';
const BASE58 = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
const VERSION_ONE = /^1\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)$/;
const IDENTIFIER = /^[^:\s]+:[^\s]+$/;

class WalletDeclarationError extends Error {}
function requireDeclaration(condition, message) {
  if (!condition) throw new WalletDeclarationError(message);
}
function isRecord(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
function identifiers(value, message) {
  requireDeclaration(Array.isArray(value) && value.every(item => typeof item === 'string' && IDENTIFIER.test(item)), message);
  return [...value];
}
function feature(source, name, method, required = true) {
  const value = Object.hasOwn(source, name) ? source[name] : undefined;
  const valid = isRecord(value) && typeof value.version === 'string'
    && VERSION_ONE.test(value.version) && typeof value[method] === 'function';
  if (required) requireDeclaration(valid, '钱包缺少兼容的连接或账户事件能力');
  return valid ? { target: value, method: value[method] } : null;
}
function declaration(wallet, overrides = {}) {
  requireDeclaration(isRecord(wallet) && typeof wallet.version === 'string'
    && VERSION_ONE.test(wallet.version), '钱包标准版本不受支持');
  const name = wallet.name;
  requireDeclaration(typeof name === 'string' && name.trim().length > 0 && name.length <= 128, '钱包名称无效');
  const chains = identifiers(Object.hasOwn(overrides, 'chains') ? overrides.chains : wallet.chains, '钱包链声明无效');
  requireDeclaration(chains.includes(MAINNET), '钱包未声明支持 Solana 主网');
  const features = Object.hasOwn(overrides, 'features') ? overrides.features : wallet.features;
  requireDeclaration(isRecord(features), '钱包能力声明无效');
  const featureNames = new Set(Object.keys(features));
  requireDeclaration([...featureNames].every(name => IDENTIFIER.test(name)), '钱包能力名称无效');
  return {
    name, chains, sourceFeatures: features, featureNames,
    connect: feature(features, 'standard:connect', 'connect'),
    events: feature(features, 'standard:events', 'on'),
    disconnect: feature(features, 'standard:disconnect', 'disconnect', false),
  };
}

function publicAddress(publicKey) {
  // ArrayBuffer.isView 兼容扩展或 iframe 的 Uint8Array；拒绝普通数组和其他数值类型。
  requireDeclaration(ArrayBuffer.isView(publicKey)
    && Object.prototype.toString.call(publicKey) === '[object Uint8Array]'
    && publicKey.byteLength === 32, '主网账户公钥必须为 32 字节');
  const bytes = new Uint8Array(publicKey);
  let value = 0n;
  for (const byte of bytes) value = (value << 8n) | BigInt(byte);
  let encoded = '';
  while (value > 0n) {
    encoded = BASE58[Number(value % 58n)] + encoded;
    value /= 58n;
  }
  for (const byte of bytes) {
    if (byte !== 0) break;
    encoded = `1${encoded}`;
  }
  return encoded;
}
function permitted(account, declared) {
  requireDeclaration(account.chains.every(chain => declared.chains.includes(chain)), '账户链超出钱包声明');
  requireDeclaration(account.features.every(name => declared.featureNames.has(name)), '账户能力超出钱包声明');
}
function accountsFrom(rawAccounts, declared) {
  requireDeclaration(Array.isArray(rawAccounts), '钱包没有提供有效账户列表');
  const accounts = [];
  for (const raw of rawAccounts) {
    requireDeclaration(isRecord(raw), '钱包账户格式无效');
    const chains = identifiers(raw.chains, '账户链声明无效');
    const features = identifiers(raw.features, '账户能力声明无效');
    const account = { chains, features };
    permitted(account, declared);
    // 多链钱包可以同时返回其他链账户；只选择声明了目标主网的账户。
    if (!chains.includes(MAINNET)) continue;
    const address = raw.address;
    requireDeclaration(typeof address === 'string' && address.length >= 32 && address.length <= 44
      && publicAddress(raw.publicKey) === address, '账户地址与规范的 32 字节公钥不符');
    accounts.push({ address, chains, features });
  }
  requireDeclaration(rawAccounts.length === 0 || accounts.length > 0, '钱包未授权 Solana 主网账户');
  return accounts;
}

function frozenState(state) {
  return Object.freeze({
    ...state,
    wallets: Object.freeze(state.wallets.map(({ id, name }) => Object.freeze({ id, name }))),
  });
}
const disconnected = (error = null) => ({ status: 'disconnected', address: null, walletName: null, error });
function safeOff(off) {
  if (!off) return;
  try { off(); } catch {
    // 撤销函数来自扩展；即使扩展清理失败，代次校验仍禁止旧监听改变本地会话。
  }
}

/** registry 由页面传入真实 getWallets() 实例；构造时不请求账户授权。 */
export function createWalletSession({ registry, onChange } = {}) {
  if (!registry || typeof registry.get !== 'function' || typeof registry.on !== 'function'
    || (onChange !== undefined && typeof onChange !== 'function')) {
    throw new TypeError('需要有效的钱包注册表与状态回调');
  }
  const ids = new WeakMap();
  let nextId = 0;
  let available = new Map();
  let current = null;
  let generation = 0;
  let destroyed = false;
  let state = frozenState({ wallets: [], ...disconnected(), revision: 0 });
  const registryOff = [];
  const getState = () => state;
  const isCurrent = token => !destroyed && current === token;

  function publish(patch, bump = false, notify = true) {
    state = frozenState({ ...state, ...patch, revision: state.revision + (bump ? 1 : 0) });
    if (notify && onChange) {
      try { onChange(state); } catch {
        // 展示回调不能阻止账户撤销，也不能改写会话状态；状态仍可经 getState 读取。
      }
    }
  }
  function retire() {
    const old = current;
    current = null;
    generation += 1;
    if (old) {
      old.subscription += 1;
      const off = old.off;
      old.off = null;
      safeOff(off);
    }
    return old;
  }
  function fail(error) {
    retire();
    publish(disconnected(error), true);
  }
  function refresh(notify = true) {
    if (destroyed) return;
    let wallets;
    try {
      wallets = registry.get();
      requireDeclaration(Array.isArray(wallets), '钱包注册表返回格式无效');
    } catch {
      const hadCurrent = current !== null;
      retire();
      available = new Map();
      publish({ wallets: [], ...disconnected('暂时无法读取钱包注册表') }, hadCurrent, notify);
      return;
    }
    const next = new Map();
    for (const wallet of wallets) {
      try {
        const declared = declaration(wallet);
        if (!ids.has(wallet)) ids.set(wallet, `wallet-${++nextId}`);
        const id = ids.get(wallet);
        next.set(id, { wallet, id, name: declared.name });
      } catch {
        // 不兼容或声明损坏的钱包不进入可连接列表，也不影响其他钱包发现。
      }
    }
    available = next;
    const summaries = [...next.values()].map(({ id, name }) => ({ id, name }));
    if (current && !next.has(current.id)) {
      retire();
      publish({ wallets: summaries, ...disconnected('当前钱包已移除或不再支持主网') }, true, notify);
    } else if (JSON.stringify(summaries) !== JSON.stringify(state.wallets)) {
      publish({ wallets: summaries }, false, notify);
    }
  }

  function listen(token) {
    const subscription = ++token.subscription;
    const previous = token.off;
    token.off = null;
    safeOff(previous);
    const events = token.declared.events;
    const off = events.method.call(events.target, 'change', change => {
      if (isCurrent(token) && token.subscription === subscription) changed(token, change);
    });
    requireDeclaration(typeof off === 'function', '钱包未提供账户事件的撤销方法');
    if (isCurrent(token) && token.subscription === subscription) token.off = off;
    else safeOff(off);
  }

  function changed(token, change) {
    if (!isCurrent(token)) return;
    const event = ++token.event;
    try {
      requireDeclaration(isRecord(change), '钱包账户变更格式无效');
      const hasAccounts = Object.hasOwn(change, 'accounts');
      const hasChains = Object.hasOwn(change, 'chains');
      const hasFeatures = Object.hasOwn(change, 'features');
      if (!hasAccounts && !hasChains && !hasFeatures) return;
      if (hasAccounts) token.accountEvents += 1;
      const previous = token.declared;
      const declared = declaration(token.wallet, {
        chains: hasChains ? change.chains : previous.chains,
        features: hasFeatures ? change.features : previous.sourceFeatures,
      });
      const accounts = hasAccounts ? accountsFrom(change.accounts, declared) : token.accounts;
      for (const account of accounts) permitted(account, declared);
      // 扩展属性也可能是 getter；读取期间若发生嵌套事件，外层事件不得回写旧账户。
      if (!isCurrent(token) || token.event !== event) return;
      if (hasAccounts && accounts.length === 0) {
        fail(null);
        return;
      }
      token.declared = declared;
      token.accounts = accounts;
      if (declared.events.target !== previous.events.target || declared.events.method !== previous.events.method) {
        listen(token);
        if (!isCurrent(token) || token.event !== event) return;
      }
      publish(accounts.length > 0
        ? { status: 'connected', address: accounts[0].address, walletName: declared.name, error: null }
        : { walletName: declared.name, error: null }, true);
    } catch (error) {
      if (isCurrent(token) && token.event === event) {
        fail(error instanceof WalletDeclarationError ? error.message : '钱包账户声明无法验证');
      }
    }
    refresh();
  }

  async function connect(id) {
    if (destroyed) return getState();
    retire();
    const intent = generation;
    refresh();
    if (destroyed || generation !== intent) return getState();
    const selected = available.get(id);
    if (!selected) {
      publish(disconnected('所选钱包不可用，请重新选择'), true);
      return getState();
    }
    let token;
    let listening = false;
    let beforeAccounts = 0;
    try {
      token = {
        ...selected, declared: declaration(selected.wallet), accounts: [],
        accountEvents: 0, event: 0, subscription: 0, off: null,
      };
      current = token;
      publish({ status: 'connecting', address: null, walletName: token.declared.name, error: null }, true);
      if (!isCurrent(token)) return getState();
      // 先订阅；钱包可能在 connect 调用内部同步发布真正的最新账户。
      beforeAccounts = token.accountEvents;
      listen(token);
      listening = true;
      if (!isCurrent(token)) return getState();
      const connection = token.declared.connect;
      const result = await connection.method.call(connection.target);
      if (!isCurrent(token) || token.accountEvents !== beforeAccounts) return getState();
      requireDeclaration(isRecord(result), '钱包连接结果无效');
      const beforeValidation = token.event;
      const accounts = accountsFrom(result.accounts, token.declared);
      if (!isCurrent(token) || token.accountEvents !== beforeAccounts) return getState();
      requireDeclaration(token.event === beforeValidation, '账户校验期间钱包能力发生变化，请重新连接');
      requireDeclaration(accounts.length > 0, '钱包未授权 Solana 主网账户');
      token.accounts = accounts;
      publish({ status: 'connected', address: accounts[0].address, walletName: token.declared.name, error: null }, true);
    } catch (error) {
      // 连接期间的账户事件已成为最新事实时，旧 Promise 的拒绝同样必须丢弃。
      if (token && isCurrent(token) && (!listening || token.accountEvents === beforeAccounts)) {
        fail(error instanceof WalletDeclarationError ? error.message : '钱包连接被取消或失败，请重试');
      } else if (!token && !destroyed) {
        fail(error instanceof WalletDeclarationError ? error.message : '钱包连接能力无法读取');
      }
    }
    return getState();
  }

  async function disconnect() {
    if (destroyed) return getState();
    const token = retire();
    const intent = generation;
    // 用户点击即撤销本地会话；扩展清理无论多久，都不能延续旧账户授权。
    publish(disconnected(), true);
    const cleanup = token?.declared.disconnect;
    if (!cleanup) return getState();
    try {
      await cleanup.method.call(cleanup.target);
    } catch {
      if (!destroyed && generation === intent && current === null) {
        publish(disconnected('本地已断开；钱包扩展清理失败'));
      }
    }
    return getState();
  }

  function destroy() {
    if (destroyed) return getState();
    destroyed = true;
    retire();
    registryOff.splice(0).forEach(safeOff);
    available.clear();
    publish({ wallets: [], ...disconnected() }, true);
    return getState();
  }

  try {
    for (const event of ['register', 'unregister']) {
      const off = registry.on(event, () => refresh());
      if (typeof off !== 'function') throw new TypeError('钱包注册表未提供撤销监听方法');
      registryOff.push(off);
    }
    refresh(false);
  } catch (error) {
    destroyed = true;
    registryOff.splice(0).forEach(safeOff);
    throw error;
  }
  return Object.freeze({ getState, connect, disconnect, destroy });
}

/** 仅接受十进制文本与精确整数上限；原子金额始终以 BigInt 运算并返回字符串。 */
export function parseAtomicAmount(text, decimals, maxAtomic) {
  if (!Number.isInteger(decimals) || decimals < 0 || decimals > 255) throw new TypeError('资产精度必须为 0 到 255 的整数');
  if (!((typeof maxAtomic === 'bigint' && maxAtomic > 0n)
    || (typeof maxAtomic === 'string' && /^[1-9][0-9]*$/.test(maxAtomic)))) {
    throw new TypeError('金额上限必须为正整数字符串或 BigInt');
  }
  const maximum = BigInt(maxAtomic);
  if (typeof text !== 'string' || text.length > maximum.toString().length + decimals + 2
    || !/^(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$/.test(text)) {
    throw new TypeError('请输入不带符号、空白或科学计数的十进制金额');
  }
  const [whole, fraction = ''] = text.split('.');
  if (fraction.length > decimals) throw new TypeError('金额超过资产允许的小数精度');
  const atomic = BigInt(whole) * (10n ** BigInt(decimals)) + BigInt(fraction.padEnd(decimals, '0') || '0');
  if (atomic <= 0n || atomic > maximum) throw new TypeError('金额必须大于零且不超过本次上限');
  return atomic.toString();
}

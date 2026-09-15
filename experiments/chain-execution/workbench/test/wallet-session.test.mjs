import test from 'node:test';
import assert from 'node:assert/strict';
import { createWalletSession, parseAtomicAmount } from '../src/wallet-session.mjs';

// 测试替身只存在于 Node 测试；不访问浏览器、节点服务或真实钱包。
const MAINNET = 'solana:mainnet';
const A = '11111111111111111111111111111112';
const B = '11111111111111111111111111111113';
const C = '11111111111111111111111111111114';
function account(last = 1, extra = {}) {
  const publicKey = new Uint8Array(32);
  publicKey[31] = last;
  return { address: [null, A, B, C][last], publicKey, chains: [MAINNET], features: [], ...extra };
}
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function fakeWallet(name = '测试钱包', connect) {
  const listeners = new Set();
  const removed = [];
  const calls = [];
  const wallet = {
    version: '1.0.0', name, chains: [MAINNET], accounts: [],
    features: {
      'standard:connect': { version: '1.0.0', async connect(...args) {
        calls.push(['connect', ...args]);
        if (connect) return connect(wallet);
        wallet.accounts = [account()];
        return { accounts: wallet.accounts };
      } },
      'standard:events': { version: '1.0.0', on(type, listener) {
        assert.equal(type, 'change');
        calls.push(['subscribe']);
        listeners.add(listener);
        return () => { listeners.delete(listener); removed.push(listener); calls.push(['unsubscribe']); };
      } },
      'standard:disconnect': { version: '1.0.0', async disconnect() { calls.push(['disconnect']); } },
    },
  };
  return {
    wallet, calls, listeners,
    emit(change) {
      Object.assign(wallet, change);
      for (const listener of [...listeners]) listener(change);
    },
    late(change) { for (const listener of removed) listener(change); },
  };
}
function fakeRegistry(...initial) {
  const wallets = new Set(initial);
  const listeners = { register: new Set(), unregister: new Set() };
  return {
    get() { return [...wallets]; },
    on(type, listener) { listeners[type].add(listener); return () => listeners[type].delete(listener); },
    add(...added) { added.forEach(wallet => wallets.add(wallet)); for (const fn of [...listeners.register]) fn(...added); },
    remove(...removed) { removed.forEach(wallet => wallets.delete(wallet)); for (const fn of [...listeners.unregister]) fn(...removed); },
    listenerCount() { return listeners.register.size + listeners.unregister.size; },
  };
}
const idFor = (session, name) => session.getState().wallets.find(wallet => wallet.name === name).id;

test('发现只暴露最小钱包状态且不自动连接已有账户', () => {
  const good = fakeWallet(); good.wallet.accounts = [account()];
  const wrongChain = fakeWallet('其他链'); wrongChain.wallet.chains = ['solana:devnet'];
  const wrongVersion = fakeWallet('新版本'); wrongVersion.wallet.features['standard:connect'].version = '2.0.0';
  const noEvents = fakeWallet('缺少事件'); delete noEvents.wallet.features['standard:events'];
  const session = createWalletSession({ registry: fakeRegistry(good.wallet, wrongChain.wallet, wrongVersion.wallet, noEvents.wallet) });
  const state = session.getState();
  assert.deepEqual(Object.keys(state).sort(), ['address', 'error', 'revision', 'status', 'walletName', 'wallets']);
  assert.deepEqual(state, { wallets: [{ id: state.wallets[0].id, name: '测试钱包' }], status: 'disconnected', address: null, walletName: null, error: null, revision: 0 });
  assert.deepEqual(good.calls, []);
  assert.throws(() => state.wallets.push({ id: '伪造', name: '伪造' }), TypeError);
  assert.throws(() => { state.wallets[0].name = '修改'; }, TypeError);
  session.destroy();
});

test('同名钱包按实例分配稳定不同 ID，注册注销不会自动连接', () => {
  const one = fakeWallet('同名'), two = fakeWallet('同名');
  const registry = fakeRegistry(one.wallet);
  const session = createWalletSession({ registry });
  const firstId = session.getState().wallets[0].id;
  registry.add(two.wallet);
  assert.equal(new Set(session.getState().wallets.map(wallet => wallet.id)).size, 2);
  registry.remove(one.wallet); registry.add(one.wallet);
  assert.equal(session.getState().wallets.find(wallet => wallet.id === firstId).id, firstId);
  assert.deepEqual([...one.calls, ...two.calls], []);
  session.destroy(); assert.equal(registry.listenerCount(), 0);
});

test('点击连接前先监听，返回规范地址且状态没有账户原始对象', async () => {
  const fake = fakeWallet(); const changes = [];
  const session = createWalletSession({ registry: fakeRegistry(fake.wallet), onChange: state => changes.push(state) });
  await session.connect(idFor(session, fake.wallet.name));
  assert.deepEqual(fake.calls.slice(0, 2).map(call => call[0]), ['subscribe', 'connect']);
  assert.equal(session.getState().status, 'connected');
  assert.equal(session.getState().address, A);
  assert.equal(session.getState().walletName, fake.wallet.name);
  assert.equal(session.getState().error, null);
  assert.ok(changes.some(state => state.status === 'connecting' && state.address === null));
  assert.ok(changes.every(state => !('features' in state) && !('publicKey' in state)));
  session.destroy();
});

test('连接中的账户事件优先于随后成功或失败的旧回包', async () => {
  for (const fail of [false, true]) {
    const pending = deferred();
    const fake = fakeWallet('事件优先', () => pending.promise);
    const session = createWalletSession({ registry: fakeRegistry(fake.wallet) });
    const connecting = session.connect(idFor(session, '事件优先'));
    const before = session.getState().revision;
    fake.emit({ accounts: [account(2)] });
    assert.equal(session.getState().address, B);
    assert.ok(session.getState().revision > before);
    const latest = session.getState();
    if (fail) pending.reject(new Error('旧拒绝')); else pending.resolve({ accounts: [account()] });
    await connecting;
    assert.deepEqual(session.getState(), latest);
    session.destroy();
  }
});

test('connect 内同步账户事件也不能被返回值覆盖', async () => {
  let fake;
  fake = fakeWallet('同步事件', () => {
    fake.emit({ accounts: [account(3)] });
    return { accounts: [account()] };
  });
  const session = createWalletSession({ registry: fakeRegistry(fake.wallet) });
  await session.connect(idFor(session, '同步事件'));
  assert.equal(session.getState().address, C);
  session.destroy();
});

test('切换钱包使旧回包和已撤销监听失效且不自动触发远端断开', async () => {
  const pending = deferred();
  const old = fakeWallet('旧钱包', () => pending.promise), current = fakeWallet('新钱包');
  const session = createWalletSession({ registry: fakeRegistry(old.wallet, current.wallet) });
  const connecting = session.connect(idFor(session, '旧钱包'));
  await session.connect(idFor(session, '新钱包'));
  const latest = session.getState();
  old.late({ accounts: [account(3)] });
  pending.resolve({ accounts: [account(2)] }); await connecting;
  assert.deepEqual(session.getState(), latest);
  assert.equal(old.listeners.size, 0);
  assert.equal(old.calls.filter(call => call[0] === 'disconnect').length, 0);
  session.destroy();
});

test('连接中断开事件立即失效账户，旧连接不能复活', async () => {
  const pending = deferred(), fake = fakeWallet('断开事件', () => pending.promise);
  const session = createWalletSession({ registry: fakeRegistry(fake.wallet) });
  const connecting = session.connect(idFor(session, '断开事件'));
  const before = session.getState().revision;
  fake.emit({ accounts: [] });
  assert.equal(session.getState().status, 'disconnected');
  assert.equal(session.getState().address, null);
  assert.ok(session.getState().revision > before);
  pending.resolve({ accounts: [account()] }); await connecting;
  assert.equal(session.getState().status, 'disconnected');
  assert.equal(fake.calls.filter(call => call[0] === 'disconnect').length, 0);
  session.destroy();
});

test('账户换号和权限变更同步递增 revision', async () => {
  const fake = fakeWallet(); const session = createWalletSession({ registry: fakeRegistry(fake.wallet) });
  await session.connect(idFor(session, fake.wallet.name));
  const before = session.getState().revision;
  fake.emit({ accounts: [account(2)] });
  assert.equal(session.getState().address, B);
  assert.ok(session.getState().revision > before);
  const accountRevision = session.getState().revision;
  fake.emit({ chains: ['solana:devnet'] });
  assert.equal(session.getState().status, 'disconnected');
  assert.equal(session.getState().address, null);
  assert.ok(session.getState().revision > accountRevision);
  assert.ok(session.getState().error);
  session.destroy();
});

test('钱包能力撤回使会话失效，不能继续展示旧账户', async () => {
  const fake = fakeWallet(); const session = createWalletSession({ registry: fakeRegistry(fake.wallet) });
  await session.connect(idFor(session, fake.wallet.name));
  fake.emit({ features: { 'standard:connect': fake.wallet.features['standard:connect'] } });
  assert.equal(session.getState().status, 'disconnected');
  assert.equal(session.getState().address, null);
  assert.ok(session.getState().error);
  session.destroy();
});

test('注销当前钱包立即撤销会话和监听，旧回包无效', async () => {
  const pending = deferred(), fake = fakeWallet('注销', () => pending.promise);
  const registry = fakeRegistry(fake.wallet), session = createWalletSession({ registry });
  const connecting = session.connect(idFor(session, '注销'));
  registry.remove(fake.wallet);
  assert.deepEqual(session.getState().wallets, []);
  assert.equal(session.getState().status, 'disconnected');
  assert.equal(fake.listeners.size, 0);
  const latest = session.getState();
  pending.resolve({ accounts: [account()] }); await connecting;
  fake.late({ accounts: [account(3)] });
  assert.deepEqual(session.getState(), latest);
  assert.equal(fake.calls.filter(call => call[0] === 'disconnect').length, 0);
  session.destroy();
});

test('显式断开先本地失效，远端拒绝也不能恢复旧账户', async () => {
  const fake = fakeWallet(), cleanup = deferred();
  fake.wallet.features['standard:disconnect'].disconnect = () => cleanup.promise;
  const session = createWalletSession({ registry: fakeRegistry(fake.wallet) });
  await session.connect(idFor(session, fake.wallet.name));
  const before = session.getState().revision;
  const disconnecting = session.disconnect();
  assert.equal(session.getState().status, 'disconnected');
  assert.equal(session.getState().address, null);
  assert.ok(session.getState().revision > before);
  cleanup.reject(new Error('外部断开失败'));
  await disconnecting;
  assert.equal(session.getState().status, 'disconnected');
  assert.ok(session.getState().error);
  session.destroy();
});

test('旧断开失败不能覆盖后来建立的新连接', async () => {
  const first = fakeWallet('一'), second = fakeWallet('二'), cleanup = deferred();
  first.wallet.features['standard:disconnect'].disconnect = () => cleanup.promise;
  const session = createWalletSession({ registry: fakeRegistry(first.wallet, second.wallet) });
  await session.connect(idFor(session, '一'));
  const disconnecting = session.disconnect();
  await session.connect(idFor(session, '二'));
  const latest = session.getState();
  cleanup.reject(new Error('过期清理')); await disconnecting;
  assert.deepEqual(session.getState(), latest);
  session.destroy();
});

test('可选断开能力缺失时仍立即断开本地会话', async () => {
  const fake = fakeWallet(); delete fake.wallet.features['standard:disconnect'];
  const session = createWalletSession({ registry: fakeRegistry(fake.wallet) });
  await session.connect(idFor(session, fake.wallet.name)); await session.disconnect();
  assert.equal(session.getState().status, 'disconnected');
  assert.equal(session.getState().error, null);
  session.destroy();
});

test('destroy 清理所有监听且不远端断开、不接受晚到结果', async () => {
  const pending = deferred(), fake = fakeWallet('销毁', () => pending.promise);
  const registry = fakeRegistry(fake.wallet), changes = [];
  const session = createWalletSession({ registry, onChange: state => changes.push(state) });
  const id = idFor(session, '销毁'), connecting = session.connect(id);
  session.destroy();
  const latest = session.getState(), count = changes.length;
  assert.equal(latest.status, 'disconnected');
  assert.equal(latest.address, null);
  assert.equal(registry.listenerCount(), 0); assert.equal(fake.listeners.size, 0);
  pending.resolve({ accounts: [account()] }); await connecting;
  fake.late({ accounts: [account(2)] }); registry.add(fakeWallet('后来').wallet);
  await session.connect(id); await session.disconnect(); session.destroy();
  assert.deepEqual(session.getState(), latest);
  assert.equal(changes.length, count);
  assert.equal(fake.calls.filter(call => call[0] === 'disconnect').length, 0);
});

test('连接拒绝和未知 ID 只留下失效状态与有限错误信息', async () => {
  const fake = fakeWallet('拒绝', () => { throw new Error('来自钱包的原始内部数据'); });
  const session = createWalletSession({ registry: fakeRegistry(fake.wallet) });
  await session.connect(idFor(session, '拒绝'));
  assert.equal(session.getState().status, 'disconnected');
  assert.equal(session.getState().address, null);
  assert.ok(session.getState().error);
  assert.ok(!session.getState().error.includes('原始内部数据'));
  await session.connect('不存在');
  assert.equal(session.getState().status, 'disconnected'); assert.ok(session.getState().error);
  session.destroy();
});

test('主网账户必须具有规范地址、对应 32 字节公钥及一致声明', async t => {
  const invalid = [
    ['地址与公钥不符', account(1, { address: B })],
    ['非规范地址', account(1, { address: ` ${A}` })],
    ['短公钥', account(1, { publicKey: new Uint8Array(31) })],
    ['普通数组不是公钥字节类型', account(1, { publicKey: [...new Uint8Array(32)] })],
    ['无主网账户', account(1, { chains: ['solana:devnet'] })],
    ['未授权链', account(1, { chains: [MAINNET, 'solana:devnet'] })],
    ['未声明账户能力', account(1, { features: ['solana:unknown'] })],
    ['缺少能力列表', account(1, { features: undefined })],
  ];
  for (const [name, value] of invalid) await t.test(name, async () => {
    const fake = fakeWallet(name, () => ({ accounts: [value] }));
    const session = createWalletSession({ registry: fakeRegistry(fake.wallet) });
    await session.connect(idFor(session, name));
    assert.equal(session.getState().status, 'disconnected'); assert.equal(session.getState().address, null);
    assert.ok(session.getState().error); session.destroy();
  });
});

test('不要求账户声明与只读展示无关的额外能力，已声明能力必须属于钱包', async () => {
  const fake = fakeWallet('只读能力', () => ({ accounts: [account(1, { features: ['solana:readOnlyExample'] })] }));
  fake.wallet.features['solana:readOnlyExample'] = { version: '1.0.0' };
  const session = createWalletSession({ registry: fakeRegistry(fake.wallet) });
  await session.connect(idFor(session, '只读能力'));
  assert.equal(session.getState().address, A); session.destroy();
});

test('完整账户校验也作用于 change 事件', async () => {
  const fake = fakeWallet(); const session = createWalletSession({ registry: fakeRegistry(fake.wallet) });
  await session.connect(idFor(session, fake.wallet.name));
  fake.emit({ accounts: [account(2, { publicKey: new Uint8Array(32) })] });
  assert.equal(session.getState().status, 'disconnected'); assert.equal(session.getState().address, null);
  assert.ok(session.getState().error); session.destroy();
});

test('监听注册失败不调用连接能力', async () => {
  const fake = fakeWallet(); fake.wallet.features['standard:events'].on = () => { throw new Error('无法监听'); };
  const session = createWalletSession({ registry: fakeRegistry(fake.wallet) });
  await session.connect(idFor(session, fake.wallet.name));
  assert.equal(session.getState().status, 'disconnected'); assert.ok(session.getState().error);
  assert.equal(fake.calls.filter(call => call[0] === 'connect').length, 0); session.destroy();
});

test('监听同步给出账户后注册仍失败，不能留下无监听的已连接状态', async () => {
  const fake = fakeWallet();
  fake.wallet.features['standard:events'].on = (_type, listener) => {
    listener({ accounts: [account()] });
    throw new Error('监听安装最终失败');
  };
  const session = createWalletSession({ registry: fakeRegistry(fake.wallet) });
  await session.connect(idFor(session, fake.wallet.name));
  assert.equal(session.getState().status, 'disconnected');
  assert.equal(session.getState().address, null);
  assert.ok(session.getState().error);
  assert.equal(fake.calls.filter(call => call[0] === 'connect').length, 0);
  session.destroy();
});

test('读取旧回包账户字段时同步到达的新事件也拥有优先权', async () => {
  let fake;
  fake = fakeWallet('读取期间换号', () => ({
    get accounts() { fake.emit({ accounts: [account(2)] }); return [account()]; },
  }));
  const session = createWalletSession({ registry: fakeRegistry(fake.wallet) });
  await session.connect(idFor(session, fake.wallet.name));
  assert.equal(session.getState().status, 'connected');
  assert.equal(session.getState().address, B);
  session.destroy();
});

test('连接尚未返回时显式取消立即递增 revision，晚到成功被丢弃', async () => {
  const pending = deferred(), fake = fakeWallet('主动取消', () => pending.promise);
  const session = createWalletSession({ registry: fakeRegistry(fake.wallet) });
  const connecting = session.connect(idFor(session, fake.wallet.name));
  const before = session.getState().revision;
  const disconnecting = session.disconnect();
  assert.equal(session.getState().status, 'disconnected');
  assert.equal(session.getState().address, null);
  assert.ok(session.getState().revision > before);
  pending.resolve({ accounts: [account()] });
  await Promise.all([connecting, disconnecting]);
  assert.equal(session.getState().status, 'disconnected');
  assert.equal(fake.calls.filter(call => call[0] === 'disconnect').length, 1);
  session.destroy();
});

test('更换事件能力会重订阅，旧监听的晚到事件不影响当前地址', async () => {
  const fake = fakeWallet(), nextListeners = new Set();
  const session = createWalletSession({ registry: fakeRegistry(fake.wallet) });
  await session.connect(idFor(session, fake.wallet.name));
  const features = { ...fake.wallet.features, 'standard:events': {
    version: '1.0.0', on(_type, fn) { nextListeners.add(fn); return () => nextListeners.delete(fn); },
  } };
  fake.emit({ features });
  assert.equal(fake.listeners.size, 0); assert.equal(nextListeners.size, 1);
  fake.late({ accounts: [account(3)] });
  assert.equal(session.getState().address, A);
  fake.wallet.accounts = [account(2)];
  for (const fn of nextListeners) fn({ accounts: fake.wallet.accounts });
  assert.equal(session.getState().address, B);
  session.destroy(); assert.equal(nextListeners.size, 0);
});

test('状态通知中取消连接也不继续弹出钱包授权', async () => {
  const fake = fakeWallet();
  let session;
  session = createWalletSession({ registry: fakeRegistry(fake.wallet), onChange(state) {
    if (state.status === 'connecting') void session.disconnect();
  } });
  await session.connect(idFor(session, fake.wallet.name));
  assert.equal(session.getState().status, 'disconnected');
  assert.equal(fake.calls.filter(call => call[0] === 'connect').length, 0);
  session.destroy();
});

test('多链账户列表仅选择通过验证的主网账户', async () => {
  const fake = fakeWallet('多链', () => ({ accounts: [
    { address: '0x1234', publicKey: new Uint8Array(20), chains: ['eip155:1'], features: [] }, account(2),
  ] }));
  fake.wallet.chains = [MAINNET, 'eip155:1'];
  const session = createWalletSession({ registry: fakeRegistry(fake.wallet) });
  await session.connect(idFor(session, fake.wallet.name));
  assert.equal(session.getState().address, B);
  session.destroy();
});

test('金额转换使用 BigInt，覆盖小数、最小单位与超过 Number 精度的整数', () => {
  assert.equal(parseAtomicAmount('0.000005', 9, '5000'), '5000');
  assert.equal(parseAtomicAmount('1.2300', 4, 12300n), '12300');
  assert.equal(parseAtomicAmount('0.000000001', 9, 1n), '1');
  assert.equal(parseAtomicAmount('9007199254740993', 0, '18446744073709551615'), '9007199254740993');
  assert.equal(parseAtomicAmount('18446744073.709551615', 9, '18446744073709551615'), '18446744073709551615');
});

test('金额拒绝科学计数、符号、空白、超精度、零、超限和不精确参数', () => {
  for (const text of ['', ' ', '1e3', '1E3', '-1', '+1', 'NaN', 'Infinity', '.1', '1.', '01', '1,000', ' 1', '1\n', '０.１', '0', '0.000', '1.0001']) {
    assert.throws(() => parseAtomicAmount(text, 3, 100000n), { name: 'TypeError' }, text);
  }
  assert.throws(() => parseAtomicAmount('1.001', 3, 1000n), { name: 'TypeError' });
  assert.throws(() => parseAtomicAmount('1.0', 0, 1000n), { name: 'TypeError' });
  for (const decimals of [-1, 1.1, 256, '9', null]) assert.throws(() => parseAtomicAmount('1', decimals, 1000n), { name: 'TypeError' });
  for (const maximum of [0n, -1n, '01', '1e10', 1000, null]) assert.throws(() => parseAtomicAmount('1', 0, maximum), { name: 'TypeError' });
  assert.throws(() => parseAtomicAmount(1, 0, 1000n), { name: 'TypeError' });
});

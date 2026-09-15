import test from 'node:test';
import assert from 'node:assert/strict';
import { PublicKey, Transaction, SystemProgram, ComputeBudgetProgram } from '@solana/web3.js';
import { address } from '@solana/kit';
import { getWhirlpoolEncoder, getDynamicTickArrayEncoder, getTickArrayAddress, getOracleAddress,
  getWhirlpoolAddress, WhirlpoolDeployment } from '@orca-so/whirlpools-client';
import { tickIndexToSqrtPrice, sqrtPriceToTickIndex, swapQuoteByInputToken,
  tryGetNextSqrtPriceFromA, tryGetNextSqrtPriceFromB } from '@orca-so/whirlpools-core';
import { createDexPreview } from '../src/dex.mjs';

// 独立合成账本夹具，不读真实账户、旧 session、钱包或私钥。
const C = Object.freeze({
  pool: 'HUeniRZwa8nSXimfMreuv7MidAoVGMesxLPDssLSyj6c',
  program: 'whirLbMiicVdio4qvUfM5KAg6Ct8VwpYzGff3uctyCc',
  config: '2LecshUwdy9xi7meFgHtFJQNSKk4KdTrcpvaB56dP2NQ',
  mintA: 'So11111111111111111111111111111111111111112',
  mintB: 'AakW2qYEFRK5DunP7yNaXAr9eknBtLiEd9iMXAAC6Mck',
  vaultA: 'EExtb55W5g96tMYB6MUpFMxXFPY3fYoZg5UYiQhGfgws',
  vaultB: 'H4yTkwJK1FPzRmkGSJBUh6fmpv1zNyTAHwKYecjuNxuH',
  token: 'TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA', spacing: 32896,
});
const SYS = SystemProgram.programId.toBase58();
const RENT = 2_039_280n;
const pk = (s) => new PublicKey(s);
const ata = (m, w) => PublicKey.findProgramAddressSync([pk(w).toBuffer(), pk(C.token).toBuffer(), pk(m).toBuffer()],
  pk('ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL'))[0].toBase58();
const key = (tag) => {
  for (let index = 0; index < 256; index++) {
    const b = Buffer.alloc(32); b[0] = tag; b[31] = index;
    if (PublicKey.isOnCurve(b)) return new PublicKey(b).toBase58();
  }
  throw new Error('无法生成公开曲线点夹具');
};
const WALLET = key(21), HASH = key(22);
const clone = (v) => structuredClone(v);
const raw = (owner, data, lamports = 1_000_000n, executable = false) => ({
  owner, executable, lamports: Number(lamports), data: [Buffer.from(data).toString('base64'), 'base64'], space: data.length,
});
const mint = (decimals, supply) => {
  const b = Buffer.alloc(82); b.writeBigUInt64LE(supply, 36); b[44] = decimals; b[45] = 1;
  return raw(C.token, b);
};
const token = (mintAddress, owner, amount) => {
  const b = Buffer.alloc(165); pk(mintAddress).toBuffer().copy(b); pk(owner).toBuffer().copy(b, 32);
  b.writeBigUInt64LE(amount, 64); b[108] = 1;
  if (mintAddress === C.mintA) { b.writeUInt32LE(1, 109); b.writeBigUInt64LE(RENT, 113); }
  return raw(C.token, b, RENT + (mintAddress === C.mintA ? amount : 0n));
};
const amount = (account) => Buffer.from(account.data[0], 'base64').readBigUInt64LE(64);
const setAmount = (account, value) => {
  const b = Buffer.from(account.data[0], 'base64'); b.writeBigUInt64LE(value, 64); account.data[0] = b.toString('base64');
};

async function fixture(side = 'BUY', input = 100_000n) {
  const wsol = ata(C.mintA, WALLET);
  const fclab = ata(C.mintB, WALLET);
  const deployment = WhirlpoolDeployment.custom(address(C.program), address(C.config));
  const [poolAddress, bump] = await getWhirlpoolAddress(address(C.mintA), address(C.mintB), C.spacing, deployment);
  assert.equal(poolAddress, C.pool);
  const oracle = (await getOracleAddress(address(C.pool), address(C.program)))[0];
  const starts = [-88 * C.spacing, 0];
  const ticks = await Promise.all(starts.map(async (startTickIndex) => {
    const entries = Array.from({ length: 88 }, () => ({ __kind: 'Uninitialized' }));
    const edge = startTickIndex < 0 ? -13 * C.spacing : 13 * C.spacing;
    const index = (edge - startTickIndex) / C.spacing;
    entries[index] = { __kind: 'Initialized', fields: [{ liquidityNet: startTickIndex < 0 ? 10_000_000_000n : -10_000_000_000n,
      liquidityGross: 10_000_000_000n, feeGrowthOutsideA: 0n, feeGrowthOutsideB: 0n, rewardGrowthsOutside: [0n, 0n, 0n] }] };
    return { address: (await getTickArrayAddress(address(C.pool), startTickIndex, address(C.program)))[0],
      args: { startTickIndex, whirlpool: address(C.pool), tickBitmap: 1n << BigInt(index), ticks: entries },
      facade: { startTickIndex, ticks: entries.map(t => t.__kind === 'Initialized'
        ? { initialized: true, ...t.fields[0] } : { initialized: false, liquidityNet: 0n, liquidityGross: 0n,
          feeGrowthOutsideA: 0n, feeGrowthOutsideB: 0n, rewardGrowthsOutside: [0n, 0n, 0n] }) } };
  }));
  const pool = { whirlpoolsConfig: address(C.config), whirlpoolBump: Uint8Array.of(bump), tickSpacing: C.spacing,
    feeTierIndexSeed: Uint8Array.of(C.spacing & 255, C.spacing >> 8), feeRate: 10_000, protocolFeeRate: 300,
    liquidity: 10_000_000_000n, sqrtPrice: tickIndexToSqrtPrice(108198), tickCurrentIndex: 108198,
    protocolFeeOwedA: 0n, protocolFeeOwedB: 0n, tokenMintA: address(C.mintA), tokenVaultA: address(C.vaultA),
    feeGrowthGlobalA: 0n, tokenMintB: address(C.mintB), tokenVaultB: address(C.vaultB), feeGrowthGlobalB: 0n,
    rewardLastUpdatedTimestamp: 1n, rewardInfos: Array.from({ length: 3 }, () => ({ mint: address(SYS), vault: address(SYS),
      extension: new Uint8Array(32), emissionsPerSecondX64: 0n, growthGlobalX64: 0n })) };
  const accounts = new Map([
    [WALLET, raw(SYS, Buffer.alloc(0), 10_000_000n)],
    [C.pool, raw(C.program, getWhirlpoolEncoder().encode(pool))],
    [wsol, token(C.mintA, WALLET, 1_000_000n)], [fclab, token(C.mintB, WALLET, 10_000_000_000n)],
    [C.vaultA, token(C.mintA, C.pool, 100_000_000n)], [C.vaultB, token(C.mintB, C.pool, 10_000_000_000_000n)],
    [C.mintA, mint(9, 0n)], [C.mintB, mint(6, 100_000_000_000_000n)], [oracle, null],
    ...ticks.map(t => [t.address, raw(C.program, getDynamicTickArrayEncoder().encode(t.args))]),
    ...[C.program, C.token, SYS, ComputeBudgetProgram.programId.toBase58()].map(p =>
      [p, raw('BPFLoaderUpgradeab1e11111111111111111111111', Buffer.alloc(36), 1n, true)]),
  ]);
  const q = swapQuoteByInputToken(input, side === 'BUY', 50, pool, undefined, ticks.map(t => t.facade), 2n);
  const end = (side === 'BUY' ? tryGetNextSqrtPriceFromA : tryGetNextSqrtPriceFromB)(pool.sqrtPrice, pool.liquidity, q.tokenIn - q.tradeFee, true);
  const calls = [];
  let feeMessage;
  let mutation = () => {};
  const rpc = async (method, params) => {
    calls.push({ method, params: clone(params) });
    if (method === 'getMultipleAccounts') return { context: { slot: 100 }, value: params[0].map(a => clone(accounts.get(a) ?? null)) };
    if (method === 'getLatestBlockhash') return { context: { slot: 101 }, value: { blockhash: HASH, lastValidBlockHeight: 2000 } };
    if (method === 'getBlockHeight') return 1000;
    if (method === 'isBlockhashValid') return { context: { slot: 102 }, value: true };
    if (method === 'getFeeForMessage') { feeMessage = params[0]; return { context: { slot: 103 }, value: 5000 }; }
    assert.equal(method, 'simulateTransaction', '只允许已列出的读取与模拟方法');
    const tx = Transaction.from(Buffer.from(params[0], 'base64'));
    assert.equal(tx.compileMessage().header.numRequiredSignatures, 1);
    assert.equal(tx.signatures.length, 1); assert.equal(tx.signatures[0].signature, null);
    assert.equal(tx.serializeMessage().toString('base64'), feeMessage, '费用与模拟必须绑定同一消息');
    assert.equal(params[1].sigVerify, false); assert.equal(params[1].replaceRecentBlockhash, false);
    const keys = tx.compileMessage().accountKeys.map(k => k.toBase58());
    const after = new Map([...accounts].map(([a, v]) => [a, clone(v)]));
    after.get(WALLET).lamports -= Number(5000n + (side === 'BUY' ? input : 0n));
    setAmount(after.get(fclab), amount(accounts.get(fclab)) + (side === 'BUY' ? q.tokenEstOut : -input));
    setAmount(after.get(C.vaultB), amount(accounts.get(C.vaultB)) + (side === 'BUY' ? -q.tokenEstOut : input));
    setAmount(after.get(wsol), amount(accounts.get(wsol)) + (side === 'BUY' ? 0n : q.tokenEstOut));
    setAmount(after.get(C.vaultA), amount(accounts.get(C.vaultA)) + (side === 'BUY' ? input : -q.tokenEstOut));
    after.get(wsol).lamports += Number(side === 'BUY' ? 0n : q.tokenEstOut);
    after.get(C.vaultA).lamports += Number(side === 'BUY' ? input : -q.tokenEstOut);
    // 独立夹具按单流动性段的协议/LP 分账生成真实后状态，不从候选指令推导批准条件。
    const protocol = q.tradeFee * 300n / 10000n, lpGrowth = ((q.tradeFee - protocol) << 64n) / pool.liquidity;
    after.set(C.pool, raw(C.program, getWhirlpoolEncoder().encode({ ...pool, sqrtPrice: end,
      tickCurrentIndex: sqrtPriceToTickIndex(end), rewardLastUpdatedTimestamp: 2n,
      [side === 'BUY' ? 'protocolFeeOwedA' : 'protocolFeeOwedB']: protocol,
      [side === 'BUY' ? 'feeGrowthGlobalA' : 'feeGrowthGlobalB']: lpGrowth })));
    const tokenMetadata = (source) => [wsol, fclab, C.vaultA, C.vaultB].map(a => ({
      accountIndex: keys.indexOf(a), mint: [wsol, C.vaultA].includes(a) ? C.mintA : C.mintB,
      owner: [wsol, fclab].includes(a) ? WALLET : C.pool, programId: C.token,
      uiTokenAmount: { amount: amount(source.get(a)).toString(), decimals: [wsol, C.vaultA].includes(a) ? 9 : 6 },
    }));
    const response = { context: { slot: 104 }, value: { err: null, unitsConsumed: 80_000, fee: 5000,
      replacementBlockhash: null, loadedAddresses: { writable: [], readonly: [] },
      preBalances: keys.map(a => accounts.get(a)?.lamports ?? 0), postBalances: keys.map(a => after.get(a)?.lamports ?? 0),
      preTokenBalances: tokenMetadata(accounts), postTokenBalances: tokenMetadata(after),
      accounts: params[1].accounts.addresses.map(a => clone(after.get(a) ?? null)),
    } };
    mutation(response, { keys, after, q, accounts, tx });
    return response;
  };
  return { rpc, accounts, calls, wallet: WALLET, wsol, fclab, q, pool, ticks, oracle,
    mutateSimulation(fn) { mutation = fn; } };
}

for (const [side, input] of [['BUY', 100_000n], ['SELL', 10_000_000_000n]]) {
  test(`${side}：单笔完整模拟证据通过，只有零签名，不返回可执行交易`, async () => {
    const f = await fixture(side, input);
    const result = await createDexPreview({ rpc: f.rpc })({ wallet: f.wallet, side, amountAtomic: input.toString() });
    assert.equal(result.status, 'SIMULATION_PASSED', JSON.stringify(result));
    assert.equal(result.executionAllowed, false);
    assert.equal(result.quote.inputAtomic, input.toString());
    assert.equal(result.quote.expectedOutputAtomic, f.q.tokenEstOut.toString());
    assert.equal(result.quote.outputAsset, side === 'BUY' ? 'FCLAB' : 'WSOL');
    assert.equal(result.simulation.outputAtomic, f.q.tokenEstOut.toString());
    assert.equal(result.simulation.feeAtomic, '5000');
    const encoded = JSON.stringify(result);
    assert(!encoded.includes('transactionBase64') && !encoded.includes('messageBase64'));
    assert.equal(f.calls.filter(c => c.method === 'getMultipleAccounts').length, 1);
    assert.equal(f.calls.filter(c => c.method === 'simulateTransaction').length, 1);
    const tx = Transaction.from(Buffer.from(f.calls.find(c => c.method === 'simulateTransaction').params[0], 'base64'));
    assert.equal(tx.instructions.filter(i => i.programId.toBase58() === SYS).length, side === 'BUY' ? 1 : 0);
    assert.equal(tx.instructions.filter(i => i.programId.toBase58() === C.token).length, side === 'BUY' ? 1 : 0);
    assert.equal(tx.instructions.filter(i => i.programId.toBase58() === C.program).length, 1);
  });
}

for (const field of ['preBalances', 'postBalances', 'preTokenBalances', 'postTokenBalances', 'fee', 'accounts', 'err', 'unitsConsumed']) {
  test(`缺少同笔模拟字段 ${field} 必须阻断，不补查伪装证据`, async () => {
    const f = await fixture(); f.mutateSimulation(r => { delete r.value[field]; });
    const result = await createDexPreview({ rpc: f.rpc })({ wallet: f.wallet, side: 'BUY', amountAtomic: '100000' });
    assert.equal(result.status, 'BLOCKED'); assert.equal(result.executionAllowed, false);
    assert.equal(f.calls.filter(c => c.method === 'getMultipleAccounts').length, 1);
  });
}

const mutations = {
  'native-extra-debit': (r) => { r.value.postBalances[0] -= 1; },
  'fee-over-budget': (r) => { r.value.fee = 10001; },
  'compute-over-budget': (r) => { r.value.unitsConsumed = 200001; },
  'partial-input': (r, c) => { const i = c.keys.indexOf(C.vaultA); r.value.postBalances[i] -= 1; },
  'stale-snapshot-pre': (r) => { r.value.preTokenBalances[0].uiTokenAmount.amount = '999'; },
  'duplicate-token-entry': (r) => { r.value.postTokenBalances.push(clone(r.value.postTokenBalances[0])); },
  'wrong-token-owner': (r) => { r.value.postTokenBalances[0].owner = C.pool; },
  'wrong-token-decimals': (r) => { r.value.postTokenBalances[0].uiTokenAmount.decimals = 6; },
  'replacement-blockhash': (r) => { r.value.replacementBlockhash = { blockhash: HASH }; },
  'simulation-error': (r) => { r.value.err = { InstructionError: [3, 'Custom'] }; },
  'unexpected-loaded-address': (r) => { r.value.loadedAddresses.writable = [key(44)]; },
  'unsafe-integer-lamports': (r) => { r.value.preBalances[0] = Number.MAX_SAFE_INTEGER + 1; },
  'null-post-account': (r) => { r.value.accounts[0] = null; },
  'missing-loaded-address-evidence': (r) => { delete r.value.loadedAddresses; },
  'stale-simulation-slot': (r) => { r.context.slot = 99; },
  'noncanonical-token-amount': (r) => { r.value.postTokenBalances[0].uiTokenAmount.amount = '01000000'; },
  'unexpected-token-delegate': (r, c) => {
    const rawAccount = c.after.get(c.keys[r.value.postTokenBalances[0].accountIndex]);
    const bytes = Buffer.from(rawAccount.data[0], 'base64'); bytes.writeUInt32LE(1, 72);
    r.value.accounts.find(a => a?.data?.[0] === rawAccount.data[0]).data[0] = bytes.toString('base64');
  },
  'unexpected-readonly-program-change': (r) => {
    const a = r.value.accounts.find(a => a?.executable === true);
    const bytes = Buffer.from(a.data[0], 'base64'); bytes[2] ^= 1; a.data[0] = bytes.toString('base64');
  },
  'unexpected-pool-protocol-fee': (r) => {
    const a = r.value.accounts.find(a => a?.owner === C.program && Buffer.from(a.data[0], 'base64').length === 653);
    const bytes = Buffer.from(a.data[0], 'base64'); bytes.writeBigUInt64LE(bytes.readBigUInt64LE(77) + 1n, 77); a.data[0] = bytes.toString('base64');
  },
  'unexpected-tick-data': (r) => {
    const a = r.value.accounts.find(a => a?.owner === C.program && Buffer.from(a.data[0], 'base64').length !== 653 && !a.executable);
    const bytes = Buffer.from(a.data[0], 'base64'); bytes[bytes.length - 1] ^= 1; a.data[0] = bytes.toString('base64');
  },
};
for (const [name, mutation] of Object.entries(mutations)) {
  test(`模拟篡改 ${name} 不得通过`, async () => {
    const f = await fixture(); let applied = false;
    f.mutateSimulation((...args) => { mutation(...args); applied = true; });
    const result = await createDexPreview({ rpc: f.rpc })({ wallet: f.wallet, side: 'BUY', amountAtomic: '100000' });
    assert(applied, '必须真正应用篡改夹具，不能把夹具异常误算成策略拒绝');
    assert.equal(result.status, 'BLOCKED', JSON.stringify(result));
  });
}

test('缺 ATA 阻断，不生成创建或关闭账户指令', async () => {
  const f = await fixture(); f.accounts.set(f.wsol, null);
  const result = await createDexPreview({ rpc: f.rpc })({ wallet: f.wallet, side: 'BUY', amountAtomic: '100000' });
  assert.equal(result.status, 'BLOCKED'); assert.equal(result.reasonCode, 'ATA_MISSING');
  assert.equal(f.calls.filter(c => c.method === 'simulateTransaction').length, 0);
});

test('经典 mint 扩展长度、额外冻结权限及超卖均拒绝', async () => {
  for (const change of [
    f => { const rawMint = f.accounts.get(C.mintB); rawMint.data[0] = Buffer.concat([Buffer.from(rawMint.data[0], 'base64'), Buffer.of(1)]).toString('base64'); },
    f => { const rawMint = f.accounts.get(C.mintB); const b = Buffer.from(rawMint.data[0], 'base64'); b.writeUInt32LE(1, 46); rawMint.data[0] = b.toString('base64'); },
    f => { setAmount(f.accounts.get(f.fclab), 1n); },
  ]) {
    const f = await fixture('SELL', 10_000_000_000n); change(f);
    const result = await createDexPreview({ rpc: f.rpc })({ wallet: f.wallet, side: 'SELL', amountAtomic: '10000000000' });
    assert.equal(result.status, 'BLOCKED'); assert.equal(f.calls.filter(c => c.method === 'simulateTransaction').length, 0);
  }
});

test('金额输入必须是规范正整数字符串，并遵守两方向固定上限', async () => {
  let calls = 0; const preview = createDexPreview({ rpc: async () => { calls++; throw new Error('不应访问'); } });
  for (const [side, value] of [['BUY', '100001'], ['BUY', '0'], ['BUY', '1e5'], ['BUY', '01'], ['BUY', 100000], ['SELL', '10000000001']]) {
    const result = await preview({ wallet: WALLET, side, amountAtomic: value });
    assert.equal(result.status, 'BLOCKED');
  }
  assert.equal(calls, 0);
});

test('区块高度过期或 blockhash 无效时不能模拟', async () => {
  for (const method of ['getBlockHeight', 'isBlockhashValid']) {
    const f = await fixture();
    const rpc = async (m, p) => m === method ? (m === 'getBlockHeight' ? 2001 : { context: { slot: 102 }, value: false }) : f.rpc(m, p);
    const result = await createDexPreview({ rpc })({ wallet: WALLET, side: 'BUY', amountAtomic: '100000' });
    assert.equal(result.status, 'BLOCKED'); assert.equal(f.calls.filter(c => c.method === 'simulateTransaction').length, 0);
  }
});

test('默认奖励管理者不是奖励扩展，必须保持只读而允许正常池', async () => {
  const f = await fixture();
  f.pool.rewardInfos[0].extension = pk(key(71)).toBytes();
  f.accounts.set(C.pool, raw(C.program, getWhirlpoolEncoder().encode(f.pool)));
  const result = await createDexPreview({ rpc: f.rpc })({ wallet: WALLET, side: 'BUY', amountAtomic: '100000' });
  assert.equal(result.status, 'SIMULATION_PASSED', JSON.stringify(result));
});

test('正常默认管理者之外的已启用奖励或控制扩展仍然阻断', async () => {
  for (const change of [p => { p.rewardInfos[1].extension[0] = 1; }, p => { p.rewardInfos[2].extension[0] = 1; },
    p => { p.rewardInfos[0].mint = address(C.mintB); }, p => { p.rewardInfos[0].emissionsPerSecondX64 = 1n; }]) {
    const f = await fixture(); change(f.pool); f.accounts.set(C.pool, raw(C.program, getWhirlpoolEncoder().encode(f.pool)));
    const result = await createDexPreview({ rpc: f.rpc })({ wallet: WALLET, side: 'BUY', amountAtomic: '100000' });
    assert.equal(result.status, 'BLOCKED'); assert.equal(result.reasonCode, 'POOL_EXTENSION');
  }
});

test('规范字符串 RPC 整数兼容，不丢失 u64 精度', async () => {
  const f = await fixture();
  for (const a of f.accounts.values()) if (a) { a.lamports = String(a.lamports); a.rentEpoch = '18446744073709551615'; }
  // 夹具模拟使用 number 加减；只在返回结果时切换表示，避免夹具本身混用金额类型。
  const rpc = async (m, p) => {
    if (m !== 'getMultipleAccounts') for (const a of f.accounts.values()) if (a) a.lamports = Number(a.lamports);
    return f.rpc(m, p);
  };
  const result = await createDexPreview({ rpc })({ wallet: WALLET, side: 'BUY', amountAtomic: '100000' });
  assert.equal(result.status, 'SIMULATION_PASSED', JSON.stringify(result));
});

test('完整预检预算在 RPC 返回后检查，不能以最终模拟迟到冒充通过', async () => {
  const f = await fixture(), realNow = Date.now;
  try {
    const beginning = realNow(); let elapsed = 0;
    Date.now = () => beginning + elapsed;
    const rpc = async (m, p) => { const response = await f.rpc(m, p); if (m === 'simulateTransaction') elapsed = 30001; return response; };
    const result = await createDexPreview({ rpc })({ wallet: WALLET, side: 'BUY', amountAtomic: '100000' });
    assert.equal(result.status, 'BLOCKED'); assert.equal(result.reasonCode, 'PREVIEW_EXPIRED');
  } finally { Date.now = realNow; }
});

test('RPC 异常脱敏，不返回交易编码或原始上游秘密文本', async () => {
  const preview = createDexPreview({ rpc: async () => { throw new Error('secret-fixture-only'); } });
  const result = await preview({ wallet: WALLET, side: 'BUY', amountAtomic: '100000' });
  assert.equal(result.status, 'BLOCKED'); assert.equal(result.error.code, 'RPC_UNAVAILABLE');
  assert(!JSON.stringify(result).includes('secret-fixture-only'));
});

test('卖出 WSOL 收入不高于网络费时明确警告，不改变只模拟状态', async () => {
  const f = await fixture('SELL', 100000000n);
  const result = await createDexPreview({ rpc: f.rpc })({ wallet: WALLET, side: 'SELL', amountAtomic: '100000000' });
  assert.equal(result.status, 'SIMULATION_PASSED', JSON.stringify(result));
  assert.equal(result.executionAllowed, false);
  assert(BigInt(result.simulation.outputAtomic) <= BigInt(result.simulation.feeAtomic));
  assert(result.warnings.some(w => w.includes('收入') && w.includes('网络费') && w.includes('不代表盈利')));
});

test('独立意图决定 legacy 指令字节、账户顺序与权限，禁止额外程序', async () => {
  for (const side of ['BUY', 'SELL']) {
    const input = side === 'BUY' ? 100000n : 10000000000n, f = await fixture(side, input);
    const result = await createDexPreview({ rpc: f.rpc })({ wallet: WALLET, side, amountAtomic: input.toString() });
    assert.equal(result.status, 'SIMULATION_PASSED');
    const request = f.calls.find(c => c.method === 'simulateTransaction');
    const wire = Buffer.from(request.params[0], 'base64'), tx = Transaction.from(wire);
    assert.deepEqual(request.params[1].accounts.addresses, tx.compileMessage().accountKeys.map(k => k.toBase58()),
      'RPC 后账户请求只能覆盖该消息实际账户，不能超过消息账户数量');
    assert.equal(wire[0], 1); assert(wire.subarray(1, 65).every(b => b === 0));
    assert.equal(tx.instructions.length, side === 'BUY' ? 4 : 2);
    assert.deepEqual([...tx.instructions[0].data], [2, 64, 13, 3, 0]);
    const swap = tx.instructions.at(-1);
    assert.equal(swap.data.length, 42); assert.equal(swap.data.readBigUInt64LE(8), input);
    assert.equal(swap.data.readBigUInt64LE(16), f.q.tokenMinOut);
    assert(swap.data.subarray(24, 40).every(b => b === 0)); assert.equal(swap.data[40], 1); assert.equal(swap.data[41], side === 'BUY' ? 1 : 0);
    const selected = side === 'BUY' ? [f.ticks[1].address, f.ticks[0].address, f.ticks[0].address] : Array(3).fill(f.ticks[1].address);
    assert.deepEqual(swap.keys.map(k => k.pubkey.toBase58()), [C.token, WALLET, C.pool, f.wsol, C.vaultA, f.fclab, C.vaultB, ...selected, f.oracle]);
    const allowed = new Set([C.program, C.token, SYS, ComputeBudgetProgram.programId.toBase58()]);
    assert(tx.instructions.every(ix => allowed.has(ix.programId.toBase58())));
    assert(tx.instructions.flatMap(ix => ix.keys).filter(k => k.isSigner).every(k => k.pubkey.toBase58() === WALLET));
    if (side === 'BUY') {
      assert.equal(tx.instructions[1].data.readUInt32LE(0), 2); assert.equal(tx.instructions[1].data.readBigUInt64LE(4), input);
      assert.deepEqual([...tx.instructions[2].data], [17]);
    }
  }
});

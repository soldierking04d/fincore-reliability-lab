import { createHash } from 'node:crypto';
import { PublicKey, Transaction, TransactionInstruction, SystemProgram, ComputeBudgetProgram } from '@solana/web3.js';
import { address } from '@solana/kit';
import { getWhirlpoolDecoder, getWhirlpoolEncoder, getWhirlpoolDiscriminatorBytes,
  getTickArrayAddress, getOracleAddress, getWhirlpoolAddress, WhirlpoolDeployment, decodeTickArray } from '@orca-so/whirlpools-client';
import { swapQuoteByInputToken, sqrtPriceToTickIndex, getTickArrayStartTickIndex,
  tryGetNextSqrtPriceFromA, tryGetNextSqrtPriceFromB } from '@orca-so/whirlpools-core';

// 此内核只生成零签名模拟消息；没有签名器、钱包文件入口或广播能力。
export const DEX_POLICY = Object.freeze({
  pool: 'HUeniRZwa8nSXimfMreuv7MidAoVGMesxLPDssLSyj6c',
  program: 'whirLbMiicVdio4qvUfM5KAg6Ct8VwpYzGff3uctyCc',
  config: '2LecshUwdy9xi7meFgHtFJQNSKk4KdTrcpvaB56dP2NQ',
  mintA: 'So11111111111111111111111111111111111111112',
  mintB: 'AakW2qYEFRK5DunP7yNaXAr9eknBtLiEd9iMXAAC6Mck',
  vaultA: 'EExtb55W5g96tMYB6MUpFMxXFPY3fYoZg5UYiQhGfgws',
  vaultB: 'H4yTkwJK1FPzRmkGSJBUh6fmpv1zNyTAHwKYecjuNxuH',
  tickSpacing: '32896', feeRate: '10000', slippageBps: '50',
  maxBuyAtomic: '100000', maxSellAtomic: '10000000000', maxFeeAtomic: '10000', maxUnits: '200000',
});
const P = DEX_POLICY, TOKEN = 'TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA', SYS = SystemProgram.programId.toBase58();
const ATA = 'ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL';
const COMPUTE = ComputeBudgetProgram.programId.toBase58(), SPACING = 32896, U64 = (1n << 64n) - 1n;
const STARTS = [-88 * SPACING, 0], PROGRAMS = [P.program, TOKEN, SYS, COMPUTE];
const pk = (v) => new PublicKey(v);
const associatedToken = (mint, wallet) => PublicKey.findProgramAddressSync(
  [pk(wallet).toBuffer(), pk(TOKEN).toBuffer(), pk(mint).toBuffer()], pk(ATA))[0].toBase58();
const equal = (a, b) => Buffer.from(a).equals(Buffer.from(b));
const hash = (v) => createHash('sha256').update(v).digest('hex');
class Blocked extends Error { constructor(code, message) { super(message); this.code = code; } }
const requireThat = (ok, code, message) => { if (!ok) throw new Blocked(code, message); };
function integer(value, name, max = U64) {
  let n;
  if (typeof value === 'bigint') n = value;
  else if (typeof value === 'string' && /^(0|[1-9][0-9]*)$/.test(value) && value.length <= 40) n = BigInt(value);
  else if (typeof value === 'number' && Number.isSafeInteger(value)) n = BigInt(value);
  else throw new Blocked('MISSING_EVIDENCE', `${name} 缺失或不是精确整数`);
  requireThat(n >= 0n && n <= max, 'INVALID_INTEGER', `${name} 超出允许范围`);
  return n;
}
function publicKey(value) {
  requireThat(typeof value === 'string' && value.length >= 32 && value.length <= 44, 'INVALID_ADDRESS', '地址格式无效');
  let parsed;
  try { parsed = pk(value); } catch { throw new Blocked('INVALID_ADDRESS', '地址格式无效'); }
  requireThat(parsed.toBase58() === value, 'INVALID_ADDRESS', '地址不是规范编码');
  return parsed;
}
function account(raw, name, { owner, size, executable = false } = {}) {
  requireThat(raw && typeof raw === 'object', 'ACCOUNT_MISSING', `${name} 不存在`);
  requireThat(raw.executable === executable && (!owner || raw.owner === owner), 'ACCOUNT_OWNER', `${name} 程序归属或可执行状态不符`);
  publicKey(raw.owner);
  requireThat(Array.isArray(raw.data) && raw.data.length === 2 && raw.data[1] === 'base64'
    && typeof raw.data[0] === 'string' && raw.data[0].length <= 131072, 'ACCOUNT_ENCODING', `${name} 不是受支持的 base64 账户`);
  const data = Buffer.from(raw.data[0], 'base64');
  requireThat(data.toString('base64') === raw.data[0] && (size === undefined || data.length === size), 'ACCOUNT_EXTENSION', `${name} 长度或扩展不受支持`);
  if (raw.space !== undefined) requireThat(integer(raw.space, 'space') === BigInt(data.length), 'ACCOUNT_ENCODING', `${name} 长度声明不符`);
  return { data, owner: raw.owner, executable: raw.executable, lamports: integer(raw.lamports, `${name} lamports`) };
}
function mint(raw, mintAddress) {
  const a = account(raw, 'Mint', { owner: TOKEN, size: 82 });
  requireThat(a.data.readUInt32LE(0) === 0 && a.data.readUInt32LE(46) === 0 && a.data[45] === 1,
    'MINT_AUTHORITY', 'Mint 必须已初始化且无增发、冻结权限');
  const isA = mintAddress === P.mintA;
  requireThat(a.data[44] === (isA ? 9 : 6) && a.data.readBigUInt64LE(36) === (isA ? 0n : 100000000000000n),
    'MINT_MISMATCH', 'Mint 精度或固定供应量不符');
  return a;
}
function token(raw, mintAddress, authority) {
  const a = account(raw, 'Token 账户', { owner: TOKEN, size: 165 });
  const d = a.data;
  requireThat(equal(d.subarray(0, 32), pk(mintAddress).toBuffer()) && equal(d.subarray(32, 64), pk(authority).toBuffer()),
    'TOKEN_AUTHORITY', 'Token 的 mint 或所有者不符');
  requireThat(d[108] === 1 && d.readUInt32LE(72) === 0 && d.readBigUInt64LE(121) === 0n && d.readUInt32LE(129) === 0,
    'TOKEN_EXTENSION', 'Token 必须正常初始化、未冻结且无代理或关闭权限');
  const amount = d.readBigUInt64LE(64), isNative = mintAddress === P.mintA;
  requireThat(d.readUInt32LE(109) === (isNative ? 1 : 0), 'TOKEN_NATIVE', 'WSOL 原生包装标志不符');
  if (isNative) requireThat(d.readBigUInt64LE(113) > 0n && a.lamports === amount + d.readBigUInt64LE(113),
    'TOKEN_NATIVE', 'WSOL 金额与租金储备、原生余额不一致');
  return { ...a, amount, mint: mintAddress, authority, decimals: isNative ? 9 : 6 };
}
function pool(raw, bump) {
  const a = account(raw, '固定 Whirlpool', { owner: P.program, size: 653 });
  requireThat(equal(a.data.subarray(0, 8), getWhirlpoolDiscriminatorBytes()), 'POOL_MISMATCH', '池账户类型不符');
  const d = getWhirlpoolDecoder().decode(a.data);
  requireThat(d.whirlpoolsConfig === P.config && d.whirlpoolBump[0] === bump && d.tickSpacing === SPACING
    && d.feeTierIndexSeed[0] === (SPACING & 255) && d.feeTierIndexSeed[1] === (SPACING >> 8)
    && d.feeRate === 10000 && d.protocolFeeRate <= 10000 && d.tokenMintA === P.mintA && d.tokenMintB === P.mintB
    && d.tokenVaultA === P.vaultA && d.tokenVaultB === P.vaultB && d.liquidity > 0n && d.sqrtPrice > 0n
    && d.tickCurrentIndex === sqrtPriceToTickIndex(d.sqrtPrice), 'POOL_MISMATCH', '池身份、费率、流动性或价格状态不符');
  // 当前范围不支持奖励发放或自适应费率，避免未建模的可变账户效果。
  // 官方布局中 [0].extension 是默认奖励管理者公钥，不是启用扩展；模拟后仍逐字节锁定。
  // [1] 是控制标志、[2] 是未来保留段，此版本只接受二者全零。
  requireThat(d.rewardInfos.every((r, i) => r.mint === SYS && r.vault === SYS && r.emissionsPerSecondX64 === 0n
    && r.growthGlobalX64 === 0n && (i === 0 || r.extension.every(b => b === 0))), 'POOL_EXTENSION', '奖励或自适应扩展不受支持');
  return { ...a, decoded: d };
}
function ticks(raw, tickAddress, start) {
  const a = account(raw, 'Tick 数组', { owner: P.program });
  const d = decodeTickArray({ address: address(tickAddress), data: a.data, executable: false,
    lamports: a.lamports, programAddress: address(P.program), space: BigInt(a.data.length) }).data;
  requireThat(d.whirlpool === P.pool && d.startTickIndex === start && d.ticks.length === 88,
    'TICK_MISMATCH', 'Tick 数组身份或长度不符');
  let bitmap = 0n;
  d.ticks.forEach((t, i) => {
    if (t.initialized) {
      const index = start + i * SPACING;
      requireThat(index >= -443636 && index <= 443636 && t.liquidityGross > 0n
        && t.liquidityNet <= t.liquidityGross && t.liquidityNet >= -t.liquidityGross, 'TICK_MISMATCH', '已初始化 Tick 不合法');
      bitmap |= 1n << BigInt(i);
    }
  });
  if (d.__kind === 'Dynamic') requireThat(d.tickBitmap === bitmap, 'TICK_MISMATCH', 'Tick 位图不符');
  return { ...a, address: tickAddress, decoded: d };
}
function context(response, minimum) {
  const slot = integer(response?.context?.slot, 'context.slot', BigInt(Number.MAX_SAFE_INTEGER));
  requireThat(slot >= minimum, 'STALE_CONTEXT', '节点上下文早于已核验快照');
  return slot;
}

// 预期指令来自固定策略、输入意图和已验证快照，绝不从候选交易反推批准条件。
function buildTransaction({ wallet, wsol, fclab, oracle, tickAddresses, side, input, minimum, blockhash }) {
  const aToB = side === 'BUY';
  const swapData = Buffer.alloc(42);
  createHash('sha256').update('global:swap').digest().copy(swapData, 0, 0, 8);
  swapData.writeBigUInt64LE(input, 8); swapData.writeBigUInt64LE(minimum, 16);
  swapData[40] = 1; swapData[41] = aToB ? 1 : 0;
  const spec = [
    [TOKEN, false, false], [wallet, true, false], [P.pool, false, true], [wsol, false, true],
    [P.vaultA, false, true], [fclab, false, true], [P.vaultB, false, true],
    ...tickAddresses.map(a => [a, false, true]), [oracle, false, false],
  ];
  const expected = [{ program: COMPUTE, keys: [], data: Buffer.from([2, 64, 13, 3, 0]) }];
  if (aToB) {
    const transfer = Buffer.alloc(12); transfer.writeUInt32LE(2); transfer.writeBigUInt64LE(input, 4);
    expected.push({ program: SYS, keys: [[wallet, true, true], [wsol, false, true]], data: transfer },
      { program: TOKEN, keys: [[wsol, false, true]], data: Buffer.of(17) });
  }
  expected.push({ program: P.program, keys: spec, data: swapData });
  const tx = new Transaction({ feePayer: pk(wallet), recentBlockhash: blockhash });
  tx.add(ComputeBudgetProgram.setComputeUnitLimit({ units: 200000 }));
  if (aToB) tx.add(SystemProgram.transfer({ fromPubkey: pk(wallet), toPubkey: pk(wsol), lamports: input }),
    new TransactionInstruction({ programId: pk(TOKEN), keys: [{ pubkey: pk(wsol), isSigner: false, isWritable: true }], data: Buffer.of(17) }));
  tx.add(new TransactionInstruction({ programId: pk(P.program), data: swapData,
    keys: spec.map(([pubkey, isSigner, isWritable]) => ({ pubkey: pk(pubkey), isSigner, isWritable })) }));
  requireThat(tx.instructions.length === expected.length, 'MESSAGE_MISMATCH', '指令数量不符');
  for (let i = 0; i < expected.length; i++) {
    const actual = tx.instructions[i], want = expected[i];
    requireThat(actual.programId.toBase58() === want.program && equal(actual.data, want.data)
      && actual.keys.length === want.keys.length && actual.keys.every((k, j) => k.pubkey.toBase58() === want.keys[j][0]
        && k.isSigner === want.keys[j][1] && k.isWritable === want.keys[j][2]), 'MESSAGE_MISMATCH', '指令或账户权限不符');
  }
  const message = tx.compileMessage(), keys = message.accountKeys.map(k => k.toBase58());
  const privileges = new Map([[wallet, { signer: true, writable: true }]]);
  for (const e of expected) {
    if (!privileges.has(e.program)) privileges.set(e.program, { signer: false, writable: false });
    for (const [a, signer, writable] of e.keys) {
      const old = privileges.get(a) ?? { signer: false, writable: false };
      privileges.set(a, { signer: old.signer || signer, writable: old.writable || writable });
    }
  }
  requireThat(message.header.numRequiredSignatures === 1 && keys[0] === wallet && keys.length === privileges.size
    && keys.every((a, i) => privileges.has(a) && message.isAccountSigner(i) === privileges.get(a).signer
      && message.isAccountWritable(i) === privileges.get(a).writable), 'MESSAGE_MISMATCH', '消息含额外账户、签名者或权限');
  const messageBytes = tx.serializeMessage(), wire = tx.serialize({ requireAllSignatures: false, verifySignatures: false });
  requireThat(wire.length <= 1232 && wire[0] === 1 && wire.subarray(1, 65).every(b => b === 0)
    && equal(Transaction.from(wire).serializeMessage(), messageBytes), 'MESSAGE_MISMATCH', '必须是单一零签名 legacy 消息');
  return { keys, messageBytes, wire };
}

function verifySimulation({ response, slot, keys, addresses, before, tokenBefore, bump, quote, endPrice,
  wallet, wsol, fclab, oracle, side, input, fee }) {
  const simulationSlot = context(response, slot), s = response?.value;
  requireThat(s && Object.hasOwn(s, 'err') && s.err === null, 'SIMULATION_FAILED', '模拟失败或没有明确的成功状态');
  const units = integer(s.unitsConsumed, 'unitsConsumed');
  requireThat(units > 0n && units <= 200000n && integer(s.fee, '模拟 fee') === fee, 'SIMULATION_BUDGET', '模拟费用或计算量不符');
  requireThat(s.replacementBlockhash == null && s.loadedAddresses && Array.isArray(s.loadedAddresses.writable)
    && Array.isArray(s.loadedAddresses.readonly) && s.loadedAddresses.writable.length === 0 && s.loadedAddresses.readonly.length === 0,
    'MESSAGE_MISMATCH', '模拟不能替换区块哈希或加载额外地址');
  requireThat(Array.isArray(s.preBalances) && Array.isArray(s.postBalances) && s.preBalances.length === keys.length
    && s.postBalances.length === keys.length && Array.isArray(s.accounts) && s.accounts.length === addresses.length,
    'MISSING_EVIDENCE', '节点缺少同笔模拟的完整前后余额或账户数据');
  const preLamports = new Map(), postLamports = new Map();
  keys.forEach((a, i) => {
    const pre = integer(s.preBalances[i], 'preBalances'), post = integer(s.postBalances[i], 'postBalances');
    requireThat(pre === (before.get(a)?.lamports ?? 0n), 'SNAPSHOT_CHANGED', '模拟起始余额与报价快照不一致');
    preLamports.set(a, pre); postLamports.set(a, post);
  });
  const parseTokens = (entries) => {
    requireThat(Array.isArray(entries) && entries.length === 4, 'MISSING_EVIDENCE', '节点缺少四个 Token 账户的同笔模拟余额');
    const result = new Map();
    for (const t of entries) {
      const index = integer(t?.accountIndex, 'Token accountIndex', BigInt(keys.length - 1)), a = keys[Number(index)], expected = tokenBefore.get(a);
      requireThat(expected && !result.has(a) && t.mint === expected.mint && t.owner === expected.authority
        && t.programId === TOKEN && t.uiTokenAmount?.decimals === expected.decimals
        && typeof t.uiTokenAmount.amount === 'string', 'TOKEN_EVIDENCE', 'Token 余额身份、精度或重复索引不符');
      result.set(a, integer(t.uiTokenAmount.amount, 'Token amount'));
    }
    return result;
  };
  const preToken = parseTokens(s.preTokenBalances), postToken = parseTokens(s.postTokenBalances);
  for (const [a, t] of tokenBefore) requireThat(preToken.get(a) === t.amount, 'SNAPSHOT_CHANGED', 'Token 模拟起点与报价快照不一致');
  const output = side === 'BUY' ? postToken.get(fclab) - preToken.get(fclab) : postToken.get(wsol) - preToken.get(wsol);
  requireThat(output > 0n && output >= quote.tokenMinOut && output === quote.tokenEstOut, 'OUTPUT_MISMATCH', '模拟到账低于最低到账或偏离同快照精确报价');
  const tokenDelta = new Map(side === 'BUY' ? [[wsol, 0n], [fclab, output], [P.vaultA, input], [P.vaultB, -output]]
    : [[wsol, output], [fclab, -input], [P.vaultA, -output], [P.vaultB, input]]);
  for (const [a, d] of tokenDelta) requireThat(postToken.get(a) - preToken.get(a) === d, 'INPUT_MISMATCH', '完整输入或 Token 守恒不符');
  const nativeDelta = new Map([[wallet, -fee - (side === 'BUY' ? input : 0n)],
    [wsol, side === 'BUY' ? 0n : output], [P.vaultA, side === 'BUY' ? input : -output]]);
  let totalNativeDelta = 0n;
  for (const a of keys) {
    const d = postLamports.get(a) - preLamports.get(a); totalNativeDelta += d;
    requireThat(d === (nativeDelta.get(a) ?? 0n), 'EXTRA_BALANCE_CHANGE', '出现未批准的原生余额变化');
  }
  requireThat(totalNativeDelta === -fee, 'FEE_MISMATCH', '原生余额净差与同笔费用不守恒');
  const post = new Map();
  addresses.forEach((a, i) => {
    const raw = s.accounts[i], old = before.get(a);
    if (a === oracle && !old) {
      if (raw !== null) {
        const empty = account(raw, '空 Oracle', { owner: SYS, size: 0 });
        requireThat(empty.lamports === 0n, 'EXTRA_ACCOUNT_CHANGE', '未初始化 Oracle 不得发生资金变化');
      }
      post.set(a, null); return;
    }
    const next = tokenBefore.has(a) ? token(raw, tokenBefore.get(a).mint, tokenBefore.get(a).authority)
      : a === P.pool ? pool(raw, bump) : account(raw, '模拟后账户', { owner: old.owner, executable: old.executable });
    requireThat(next.lamports === (postLamports.has(a) ? postLamports.get(a) : old.lamports), 'ACCOUNT_EVIDENCE', '模拟账户与余额元数据不一致');
    if (tokenBefore.has(a)) {
      requireThat(next.amount === postToken.get(a), 'ACCOUNT_EVIDENCE', '模拟账户原始 Token 金额与元数据不一致');
      const normalized = Buffer.from(next.data); old.data.subarray(64, 72).copy(normalized, 64);
      requireThat(equal(normalized, old.data), 'EXTRA_ACCOUNT_CHANGE', 'Token 金额以外的账户字段发生变化');
    } else if (a === P.pool) {
      const d = next.decoded, prior = old.decoded;
      requireThat(d.sqrtPrice === endPrice && d.tickCurrentIndex === sqrtPriceToTickIndex(endPrice)
        && d.rewardLastUpdatedTimestamp >= prior.rewardLastUpdatedTimestamp
        && d.rewardLastUpdatedTimestamp <= BigInt(Math.floor(Date.now() / 1000) + 60), 'POOL_STATE_CHANGE', '模拟后池价格或时间不符');
      // 单一流动性段的手续费记账是确定的；不接受额外协议费或 LP 费用增长。
      const protocolFee = quote.tradeFee * BigInt(prior.protocolFeeRate) / 10000n;
      const growth = ((quote.tradeFee - protocolFee) << 64n) / prior.liquidity;
      const owedField = side === 'BUY' ? 'protocolFeeOwedA' : 'protocolFeeOwedB';
      const growthField = side === 'BUY' ? 'feeGrowthGlobalA' : 'feeGrowthGlobalB';
      requireThat(d[owedField] === prior[owedField] + protocolFee && d[growthField] === (prior[growthField] + growth) % (1n << 128n),
        'POOL_STATE_CHANGE', '模拟后协议费或 LP 费用增长不符');
      const normalized = { ...d, sqrtPrice: prior.sqrtPrice, tickCurrentIndex: prior.tickCurrentIndex,
        rewardLastUpdatedTimestamp: prior.rewardLastUpdatedTimestamp, [owedField]: prior[owedField], [growthField]: prior[growthField] };
      requireThat(equal(getWhirlpoolEncoder().encode(normalized), old.data), 'EXTRA_ACCOUNT_CHANGE', '池出现未批准的其他状态变化');
    } else requireThat(equal(next.data, old.data), 'EXTRA_ACCOUNT_CHANGE', '只读账户或未跨越 Tick 数据发生变化');
    post.set(a, next);
  });
  return { output, units, simulationSlot, post, preLamports, postLamports, preToken, postToken };
}

/** 创建只读预检函数：仅完整证据可以通过，任何缺失、变化或未知扩展均阻断，永远不授权执行。 */
export function createDexPreview({ rpc } = {}) {
  if (typeof rpc !== 'function') throw new TypeError('必须提供只读 RPC 函数');
  return async function preview(intent = {}) {
    if (intent === null || typeof intent !== 'object') intent = {};
    const result = { status: 'BLOCKED', executionAllowed: false, wallet: typeof intent.wallet === 'string' ? intent.wallet : null,
      side: typeof intent.side === 'string' ? intent.side : null, quote: null, simulation: null, balances: null,
      pool: { address: P.pool, program: P.program, config: P.config, mintA: P.mintA, mintB: P.mintB,
        vaultA: P.vaultA, vaultB: P.vaultB, tickSpacing: P.tickSpacing, feeRate: P.feeRate },
      warnings: ['仅模拟：不签名、不广播，模拟通过不代表允许执行。', '卖出所得保留为已有 WSOL 账户余额，不自动解包为 SOL。'] };
    let stage = '输入验证';
    try {
      const started = Date.now(), walletKey = publicKey(intent.wallet), wallet = walletKey.toBase58(), side = intent.side;
      requireThat(PublicKey.isOnCurve(walletKey.toBytes()) && ![...Object.values(P), ...PROGRAMS].includes(wallet), 'INVALID_WALLET', '必须提供独立的公开钱包地址');
      requireThat(side === 'BUY' || side === 'SELL', 'INVALID_SIDE', '方向只允许 BUY 或 SELL');
      requireThat(typeof intent.amountAtomic === 'string' && /^[1-9][0-9]{0,19}$/.test(intent.amountAtomic), 'INVALID_AMOUNT', '金额必须是规范正整数字符串');
      const input = integer(intent.amountAtomic, '输入金额');
      requireThat(input <= BigInt(side === 'BUY' ? P.maxBuyAtomic : P.maxSellAtomic), 'AMOUNT_LIMIT', '输入金额超过固定上限');
      const invoke = async (method, params) => {
        stage = method;
        requireThat(Date.now() - started <= 30000, 'PREVIEW_EXPIRED', '预检已超时，必须重新获取完整快照');
        let response;
        try { response = await rpc(method, params); } catch { throw new Blocked('RPC_UNAVAILABLE', `${method} 只读请求失败；没有执行交易`); }
        requireThat(Date.now() - started <= 30000, 'PREVIEW_EXPIRED', '预检已超时，必须重新获取完整快照');
        return response;
      };
      const deployment = WhirlpoolDeployment.custom(address(P.program), address(P.config));
      const [derivedPool, bump] = await getWhirlpoolAddress(address(P.mintA), address(P.mintB), SPACING, deployment);
      requireThat(derivedPool === P.pool, 'POOL_MISMATCH', '固定池 PDA 校验失败');
      const oracle = (await getOracleAddress(address(P.pool), address(P.program)))[0];
      const tickAddresses = await Promise.all(STARTS.map(async start => (await getTickArrayAddress(address(P.pool), start, address(P.program)))[0]));
      const wsol = associatedToken(P.mintA, wallet);
      const fclab = associatedToken(P.mintB, wallet);
      const addresses = [...new Set([wallet, P.pool, wsol, fclab, P.vaultA, P.vaultB, P.mintA, P.mintB, ...tickAddresses, oracle, ...PROGRAMS])];
      // 固定大 tickSpacing 覆盖全域，只需要这两个数组；所有事实来自同一次快照。
      const snapshot = await invoke('getMultipleAccounts', [addresses, { encoding: 'base64', commitment: 'confirmed' }]);
      let slot = context(snapshot, 0n);
      requireThat(Array.isArray(snapshot.value) && snapshot.value.length === addresses.length, 'MISSING_EVIDENCE', '快照账户数量不完整');
      const raw = new Map(addresses.map((a, i) => [a, snapshot.value[i]]));
      requireThat(raw.get(wsol) != null && raw.get(fclab) != null, 'ATA_MISSING', '缺少已有 WSOL 或 FCLAB ATA；本工作台不创建、关闭账户');
      const before = new Map(), tokenBefore = new Map();
      before.set(wallet, account(raw.get(wallet), '钱包', { owner: SYS, size: 0 }));
      before.set(P.pool, pool(raw.get(P.pool), bump));
      for (const [a, m, authority] of [[wsol, P.mintA, wallet], [fclab, P.mintB, wallet], [P.vaultA, P.mintA, P.pool], [P.vaultB, P.mintB, P.pool]]) {
        const value = token(raw.get(a), m, authority); before.set(a, value); tokenBefore.set(a, value);
      }
      before.set(P.mintA, mint(raw.get(P.mintA), P.mintA)); before.set(P.mintB, mint(raw.get(P.mintB), P.mintB));
      const tickValues = tickAddresses.map((a, i) => { const value = ticks(raw.get(a), a, STARTS[i]); before.set(a, value); return value; });
      requireThat(raw.get(oracle) === null, 'POOL_EXTENSION', '当前策略不支持已初始化 Oracle 或自适应费率'); before.set(oracle, null);
      for (const p of PROGRAMS) before.set(p, account(raw.get(p), '固定程序', { executable: true }));
      requireThat(before.get(wallet).lamports >= 10000n + (side === 'BUY' ? input : 0n), 'INSUFFICIENT_SOL', 'SOL 不足以支付完整本金及费用预算');
      requireThat(side !== 'SELL' || tokenBefore.get(fclab).amount >= input, 'INSUFFICIENT_FCLAB', '已有 FCLAB 不足，不能超卖');
      result.balances = { nativeSol: { beforeAtomic: before.get(wallet).lamports.toString() },
        wrappedSol: { beforeAtomic: tokenBefore.get(wsol).amount.toString() }, fclab: { beforeAtomic: tokenBefore.get(fclab).amount.toString() } };
      const p = before.get(P.pool).decoded;
      const quote = swapQuoteByInputToken(input, side === 'BUY', 50, p, undefined, tickValues.map(t => t.decoded), BigInt(Math.floor(Date.now() / 1000)));
      requireThat(quote.tokenIn === input && quote.tokenEstOut > 0n && quote.tokenMinOut > 0n && quote.tokenMinOut <= quote.tokenEstOut
        && quote.tradeFee >= 0n && quote.tradeFee < input, 'QUOTE_INVALID', 'SDK 报价不是完整输入或无有效最低到账');
      const endPrice = (side === 'BUY' ? tryGetNextSqrtPriceFromA : tryGetNextSqrtPriceFromB)(p.sqrtPrice, p.liquidity, input - quote.tradeFee, true);
      const endTick = sqrtPriceToTickIndex(endPrice);
      requireThat(side === 'BUY' ? endPrice < p.sqrtPrice : endPrice > p.sqrtPrice, 'QUOTE_INVALID', '报价价格方向不符');
      const crossed = tickValues.some(t => t.decoded.ticks.some((tick, i) => tick.initialized
        && t.decoded.startTickIndex + i * SPACING >= Math.min(p.tickCurrentIndex, endTick)
        && t.decoded.startTickIndex + i * SPACING <= Math.max(p.tickCurrentIndex, endTick)));
      requireThat(!crossed, 'TICK_CROSSING_UNSUPPORTED', '当前预检仅支持单流动性段，不接受跨越已初始化 Tick');
      result.quote = { inputAtomic: input.toString(), expectedOutputAtomic: quote.tokenEstOut.toString(), minimumOutputAtomic: quote.tokenMinOut.toString(),
        tradeFeeAtomic: quote.tradeFee.toString(), inputAsset: side === 'BUY' ? 'SOL' : 'FCLAB', outputAsset: side === 'BUY' ? 'FCLAB' : 'WSOL', slippageBps: '50' };
      const latest = await invoke('getLatestBlockhash', [{ commitment: 'confirmed', minContextSlot: Number(slot) }]);
      slot = context(latest, slot); const blockhash = publicKey(latest?.value?.blockhash).toBase58(), lastValidHeight = integer(latest?.value?.lastValidBlockHeight, 'lastValidBlockHeight');
      const height = integer(await invoke('getBlockHeight', [{ commitment: 'confirmed', minContextSlot: Number(slot) }]), 'blockHeight');
      requireThat(height <= lastValidHeight, 'BLOCKHASH_EXPIRED', '区块哈希已过期');
      const valid = await invoke('isBlockhashValid', [blockhash, { commitment: 'confirmed', minContextSlot: Number(slot) }]);
      slot = context(valid, slot); requireThat(valid.value === true, 'BLOCKHASH_EXPIRED', '节点未明确确认区块哈希有效');
      const start = getTickArrayStartTickIndex(p.tickCurrentIndex + (side === 'SELL' ? SPACING : 0), SPACING);
      const selected = tickValues.filter(t => side === 'BUY' ? t.decoded.startTickIndex <= start : t.decoded.startTickIndex >= start)
        .sort((a, b) => side === 'BUY' ? b.decoded.startTickIndex - a.decoded.startTickIndex : a.decoded.startTickIndex - b.decoded.startTickIndex).map(t => t.address);
      requireThat(selected.length > 0 && STARTS.includes(start), 'TICK_MISMATCH', '缺少首个交换 Tick 数组');
      while (selected.length < 3) selected.push(selected.at(-1));
      const { keys, wire, messageBytes } = buildTransaction({ wallet, wsol, fclab, oracle, tickAddresses: selected.slice(0, 3), side, input, minimum: quote.tokenMinOut, blockhash });
      const feeResponse = await invoke('getFeeForMessage', [messageBytes.toString('base64'), { commitment: 'confirmed', minContextSlot: Number(slot) }]);
      slot = context(feeResponse, slot); const fee = integer(feeResponse.value, 'getFeeForMessage');
      requireThat(fee > 0n && fee <= 10000n, 'FEE_LIMIT', '无法确定费用或费用超过预算');
      const response = await invoke('simulateTransaction', [wire.toString('base64'), { encoding: 'base64', sigVerify: false,
        replaceRecentBlockhash: false, commitment: 'confirmed', minContextSlot: Number(slot), innerInstructions: true,
        // RPC 限制返回账户数量不超过消息账户数；消息外的 mint/unused tick 根本不可被该交易修改。
        accounts: { encoding: 'base64', addresses: keys } }]);
      const checked = verifySimulation({ response, slot, keys, addresses: keys, before, tokenBefore, bump, quote, endPrice,
        wallet, wsol, fclab, oracle, side, input, fee });
      result.simulation = { outputAtomic: checked.output.toString(), feeAtomic: fee.toString(), unitsConsumed: checked.units.toString(),
        slot: checked.simulationSlot.toString(), messageSha256: hash(messageBytes), wireSha256: hash(wire), evidence: 'SAME_SIMULATION_PRE_POST' };
      result.balances.nativeSol.afterAtomic = checked.postLamports.get(wallet).toString();
      result.balances.wrappedSol.afterAtomic = checked.postToken.get(wsol).toString(); result.balances.fclab.afterAtomic = checked.postToken.get(fclab).toString();
      // SOL 和 WSOL 均为 9 位小数，可直接比较原子数量；这里只提示费用，不推断收益率。
      if (side === 'SELL' && checked.output <= fee) result.warnings.push('本次模拟 WSOL 收入低于或等于 SOL 网络费；费用不低于收入，模拟通过不代表盈利。');
      requireThat(Date.now() - started <= 30000, 'PREVIEW_EXPIRED', '预检已超时，必须重新获取完整快照');
      result.status = 'SIMULATION_PASSED';
    } catch (error) {
      const safe = error instanceof Blocked ? error : new Blocked('UNSUPPORTED_EVIDENCE', `${stage} 数据不完整或不受支持；未授权执行`);
      result.reasonCode = safe.code; result.reason = safe.message; result.error = { code: safe.code, message: safe.message };
    }
    return result;
  };
}

import { getWallets } from '@wallet-standard/app';
import { createWalletSession, parseAtomicAmount } from '../src/wallet-session.mjs';

// 页面仅发现标准钱包；连接和断开均由下方按钮事件触发，不恢复任何持久会话。
const ids = [
  'wallet-select', 'connect-wallet', 'disconnect-wallet', 'connected-wallet', 'wallet-address',
  'wallet-badge', 'wallet-help', 'wallet-message', 'side-buy', 'side-sell', 'amount-label',
  'amount-limit', 'amount-input', 'amount-unit', 'atomic-amount', 'amount-error', 'sell-note',
  'preview-form', 'preview-button', 'config-error', 'config-error-text', 'reload-config',
  'asset-name', 'asset-network', 'asset-decimals', 'mint-address', 'pool-address', 'asset-badge',
  'results-panel', 'result-badge', 'result-empty', 'empty-title', 'empty-description',
  'result-content', 'result-summary', 'result-status-label', 'result-title', 'result-description',
  'result-side', 'result-input', 'result-wallet', 'result-warning-box', 'result-warnings', 'result-data',
];
const ui = Object.fromEntries(ids.map((id) => [id, document.getElementById(id)]));
const amountSummary = document.createElement('p');
const amountSummaryText = document.createElement('strong');
amountSummary.append(amountSummaryText);
amountSummary.hidden = true;
ui['result-summary'].insertBefore(amountSummary, ui['result-description']);
const apiConfig = new URL('./api/config', document.baseURI);
const apiPreview = new URL('./api/preview', document.baseURI);
let configuration = null;
let configurationLoading = false;
let configurationRequest = 0;
let side = 'BUY';
let humanAmounts = { BUY: '0.0001', SELL: '100' };
let walletState;
let walletRevision = -1;
let selectionDirty = false;
let previewGeneration = 0;
let previewAbort = null;
let previewPending = false;
let hasPreviewed = false;
const session = createWalletSession({ registry: getWallets(), onChange: onWalletChange });

const labels = {
  address: '地址', symbol: '资产', decimals: '精度', amount: '原子数量', amountAtomic: '输入原子数量',
  inputAtomic: '输入原子数量', inputAmount: '输入原子数量', inputAmountAtomic: '输入原子数量', amountIn: '输入原子数量',
  outputAmount: '预计到账（原子）', outputAmountAtomic: '预计到账（原子）', amountOut: '预计到账（原子）',
  expectedOutput: '预计到账（原子）', expectedOutputAtomic: '预计到账（原子）', estimatedAmountOut: '预计到账（原子）', minOutput: '最小到账（原子）',
  minimumOutput: '最小到账（原子）', minimumOutputAtomic: '最小到账（原子）', minAmountOut: '最小到账（原子）',
  otherAmountThreshold: '最小到账阈值', inputMint: '输入资产地址', outputMint: '输出资产地址',
  inputAsset: '输入资产', outputAsset: '输出资产', outputAtomic: '模拟到账（原子）',
  slippageBps: '滑点上限（基点）', priceImpactBps: '价格影响（基点）', priceImpactPct: '价格影响（百分比）',
  fee: '费用（原子）', feeAtomic: '费用（原子）', estimatedFee: '估算网络费（原子）', networkFee: '网络费（原子）',
  feeRate: '池费用率（百万分率）', feeAmount: '池费用（原子）', feeAmountAtomic: '池费用（原子）', tradeFeeAtomic: '池交易费（原子）',
  slot: '观察槽位', contextSlot: '上下文槽位', fetchedAt: '读取时间', expiresAt: '报价到期时间',
  status: '状态', outcome: '结果', error: '错误详情', err: '模拟错误', code: '原因代码', message: '原因说明',
  unitsConsumed: '模拟计算量', maxUnits: '计算量上限', executionAllowed: '允许执行',
  signature: '交易标识', wireSha256: '未签名字节摘要', messageSha256: '消息摘要', evidence: '证据类型',
  program: '程序地址', config: '池配置地址',
  pool: '资金池', poolAddress: '资金池地址', dex: '交易协议', liquidity: '流动性（原始值）',
  reserveA: '储备 A（原子）', reserveB: '储备 B（原子）', tokenA: '资产 A', tokenB: '资产 B',
  tokenMintA: '资产 A 地址', tokenMintB: '资产 B 地址', tokenVaultA: '资产 A 金库', tokenVaultB: '资产 B 金库',
  mintA: '资产 A 地址', mintB: '资产 B 地址', vaultA: '资产 A 金库', vaultB: '资产 B 金库', tickSpacing: '刻度间距',
  sol: 'SOL 余额（原子）', solAtomic: 'SOL 余额（原子）', solLamports: 'SOL 余额（原子）',
  fclab: 'FCLAB 余额（原子）', fclabAtomic: 'FCLAB 余额（原子）', wsol: 'WSOL 余额（原子）',
  wsolAtomic: 'WSOL 余额（原子）', nativeSol: 'SOL', wrappedSol: 'WSOL',
  beforeAtomic: '模拟前余额（原子）', afterAtomic: '模拟后余额（原子）', wallet: '公开地址', ata: '关联代币账户（ATA）',
  exists: '账户是否存在', initialized: '是否已初始化', owner: '账户所属程序', side: '方向',
};

function setText(id, value) { ui[id].textContent = value ?? ''; }

function setBadge(id, label, tone = 'neutral') {
  setText(id, label);
  ui[id].className = `badge ${tone}`;
}

function plain(value) {
  if (value === null) return '无';
  if (value === undefined) return '未返回';
  if (value === true) return '是';
  if (value === false) return '否';
  return typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value);
}

function onWalletChange(state) {
  walletState = state;
  const selected = ui['wallet-select'].value;
  const options = state.wallets.map((wallet) => {
    const option = document.createElement('option');
    option.value = wallet.id;
    option.textContent = wallet.name;
    return option;
  });
  if (options.length === 0) {
    const placeholder = document.createElement('option');
    placeholder.value = '';
    placeholder.textContent = '未发现兼容的主网钱包';
    options.push(placeholder);
  }
  ui['wallet-select'].replaceChildren(...options);
  if (state.wallets.some((wallet) => wallet.id === selected)) ui['wallet-select'].value = selected;
  ui['connected-wallet'].hidden = state.status === 'disconnected';
  setText('wallet-address', state.address ?? '等待钱包提供公开地址…');
  setText('disconnect-wallet', state.status === 'connecting' ? '取消连接' : '断开连接');
  const connected = state.status === 'connected';
  setBadge('wallet-badge', connected ? '已连接 · 只读' : state.status === 'connecting' ? '等待确认' : '未连接',
    connected ? 'success' : state.status === 'connecting' ? 'loading' : 'neutral');
  setText('wallet-message', state.error ?? (connected ? `已连接 ${state.walletName}。仅使用此公开地址进行预检。` : ''));
  ui['wallet-message'].classList.toggle('error-text', Boolean(state.error));
  setText('wallet-help', state.wallets.length === 0
    ? '未发现兼容钱包。请启用已安装的钱包扩展并允许本页访问；页面不会自动连接。'
    : '只支持 Solana 主网的标准钱包。连接不证明控制权，也不会建立服务器登录。');
  if (walletRevision !== state.revision) {
    walletRevision = state.revision;
    invalidatePreview(connected ? '钱包账户或连接状态已改变，请重新预检。' : '钱包未连接，旧预检结果已清空。');
  }
  refreshControls();
}

// 输入、账户或连接发生变化时立即废弃旧请求；旧响应只能被丢弃，不能覆盖新会话。
function invalidatePreview(reason) {
  previewGeneration += 1;
  previewAbort?.abort();
  previewAbort = null;
  previewPending = false;
  ui['results-panel'].setAttribute('aria-busy', 'false');
  ui['result-content'].hidden = true;
  ui['result-empty'].hidden = false;
  ui['result-data'].replaceChildren();
  amountSummaryText.textContent = '';
  amountSummary.hidden = true;
  ui['result-warnings'].replaceChildren();
  ui['result-warning-box'].hidden = true;
  for (const id of ['result-title', 'result-description', 'result-side', 'result-input', 'result-wallet']) setText(id, '');
  setBadge('result-badge', hasPreviewed ? '结果已清空' : '尚未预检');
  setText('empty-title', hasPreviewed ? '请重新预检' : '等待一次真实检查');
  setText('empty-description', hasPreviewed ? reason : '连接钱包并选择数量后，这里会展示报价、余额、资金池与模拟结果。');
}

function currentAtomic(showError = true) {
  if (!configuration) return null;
  try {
    const limit = configuration.limits[side];
    const amount = parseAtomicAmount(ui['amount-input'].value, limit.decimals, limit.maxAtomic);
    setText('atomic-amount', `原子数量：${amount}`);
    setText('amount-error', '');
    return amount;
  } catch (error) {
    setText('atomic-amount', '原子数量：—');
    if (showError) setText('amount-error', error instanceof Error ? error.message : '数量格式无效。');
    return null;
  }
}

function humanLimit(atoms, decimals) {
  const digits = String(atoms).padStart(decimals + 1, '0');
  if (decimals === 0) return digits;
  const fraction = digits.slice(-decimals).replace(/0+$/, '');
  return `${digits.slice(0, -decimals)}${fraction ? `.${fraction}` : ''}`;
}

// 友好金额只移动十进制字符，不舍入，也不替代下方保留的原子证据。
function humanSummary(payload, request, config) {
  const input = config?.limits?.[request.side];
  const output = request.side === 'BUY' ? config?.mint : { symbol: 'WSOL', decimals: 9 };
  const amount = (atoms, asset) => typeof atoms === 'string' && /^(0|[1-9][0-9]{0,39})$/.test(atoms)
    && asset && Number.isInteger(asset.decimals) && asset.decimals >= 0 && asset.decimals <= 18
    ? `${humanLimit(atoms, asset.decimals)} ${asset.symbol}` : null;
  const inputText = amount(request.amountAtomic, input);
  const outputText = amount(payload.quote?.expectedOutputAtomic, output);
  const minimumText = amount(payload.quote?.minimumOutputAtomic, output);
  if (!inputText || !outputText || !minimumText) return '';
  const feeText = amount(payload.simulation?.feeAtomic, { symbol: 'SOL', decimals: 9 });
  return `输入 ${inputText} → 预计 ${outputText}；最低到账 ${minimumText}${feeText ? `；网络费 ${feeText}` : ''}。`;
}

function refreshControls() {
  const enabled = configuration?.walletEnabled === true && !configurationLoading;
  const connecting = walletState?.status === 'connecting';
  const connected = walletState?.status === 'connected';
  ui['wallet-select'].disabled = !enabled || connecting || walletState?.wallets.length === 0;
  ui['connect-wallet'].disabled = !enabled || connecting || !ui['wallet-select'].value;
  setText('connect-wallet', connecting ? '连接中…' : connected && !selectionDirty ? '重新连接' : '连接钱包');
  ui['amount-input'].disabled = !enabled;
  ui['side-buy'].disabled = !enabled;
  ui['side-sell'].disabled = !enabled;
  const atomic = currentAtomic();
  const canPreview = enabled && connected && !selectionDirty && atomic !== null && !previewPending;
  ui['preview-button'].disabled = !canPreview;
  setText('preview-button', configurationLoading ? '正在读取配置…'
    : !enabled ? '预检暂不可用'
      : !connected || selectionDirty ? '连接钱包后预检'
        : previewPending ? '正在读取与模拟…' : '运行只读预检');
}

function setSide(next) {
  humanAmounts[side] = ui['amount-input'].value;
  side = next;
  ui['amount-input'].value = humanAmounts[side];
  ui['side-buy'].setAttribute('aria-pressed', String(side === 'BUY'));
  ui['side-sell'].setAttribute('aria-pressed', String(side === 'SELL'));
  ui['sell-note'].hidden = side !== 'SELL';
  const limit = configuration?.limits[side];
  const symbol = limit?.symbol ?? (side === 'BUY' ? 'SOL' : 'FCLAB');
  setText('amount-label', `输入 ${symbol} 数量`);
  setText('amount-unit', symbol);
  setText('amount-limit', limit ? `上限 ${humanLimit(limit.maxAtomic, limit.decimals)} ${symbol}` : '等待读取限额');
  invalidatePreview('检查方向或数量已改变，请重新预检。');
  refreshControls();
}

function validateConfiguration(value) {
  if (!value || value.mode !== 'LOCAL_SIMULATION_ONLY' || value.cluster !== 'solana:mainnet'
      || typeof value.walletEnabled !== 'boolean' || typeof value.mint?.address !== 'string'
      || typeof value.pool?.address !== 'string' || value.mint?.symbol !== 'FCLAB'
      || !Number.isInteger(value.mint.decimals)) throw new Error('服务器配置不符合只读主网预检合同。');
  for (const direction of ['BUY', 'SELL']) {
    const limit = value.limits?.[direction];
    if (!limit || !Number.isInteger(limit.decimals) || limit.decimals < 0 || limit.decimals > 18
        || typeof limit.maxAtomic !== 'string' || !/^[1-9][0-9]{0,39}$/.test(limit.maxAtomic)
        || typeof limit.defaultHuman !== 'string' || typeof limit.symbol !== 'string') {
      throw new Error('服务器未提供完整、精确的原子数量限额。');
    }
    parseAtomicAmount(limit.defaultHuman, limit.decimals, limit.maxAtomic);
  }
  if (value.limits.BUY.decimals !== 9 || value.limits.BUY.symbol !== 'SOL'
      || value.limits.SELL.decimals !== value.mint.decimals || value.limits.SELL.symbol !== 'FCLAB') {
    throw new Error('资产精度或方向配置不一致，已停止预检。');
  }
  if (value.csrfToken !== undefined && (typeof value.csrfToken !== 'string' || !value.csrfToken)) {
    throw new Error('预检保护令牌缺失或格式错误。');
  }
  return value;
}

async function loadConfiguration() {
  const request = ++configurationRequest;
  configurationLoading = true;
  configuration = null;
  invalidatePreview('检查配置正在重新读取，请等待完成后重新预检。');
  ui['config-error'].hidden = true;
  refreshControls();
  const controller = new AbortController();
  const deadline = setTimeout(() => controller.abort(), 15_000);
  try {
    const response = await fetch(apiConfig, { cache: 'no-store', credentials: 'same-origin', redirect: 'error', mode: 'same-origin',
      headers: { Accept: 'application/json' }, signal: controller.signal });
    if (!response.ok) throw new Error(`无法读取检查配置（HTTP ${response.status}）。`);
    const received = validateConfiguration(await response.json());
    if (request !== configurationRequest) return;
    configuration = received;
    humanAmounts = { BUY: received.limits.BUY.defaultHuman, SELL: received.limits.SELL.defaultHuman };
    // 配置中的默认数量仍走同一整数转换路径，不以浮点数重建金额。
    ui['amount-input'].value = humanAmounts[side];
    setText('asset-name', received.mint.symbol);
    setText('asset-network', 'Solana 主网');
    setText('asset-decimals', `${received.mint.decimals} 位`);
    setText('mint-address', received.mint.address);
    setText('pool-address', received.pool.address);
    setBadge('asset-badge', received.pool.dex ?? '已读取配置', 'success');
    if (!received.walletEnabled) {
      ui['config-error'].hidden = false;
      setText('config-error-text', '当前部署已关闭钱包连接与预检入口。');
    }
    setSide(side);
  } catch (error) {
    if (request !== configurationRequest) return;
    setBadge('asset-badge', '配置不可用', 'blocked');
    ui['config-error'].hidden = false;
    setText('config-error-text', error?.name === 'AbortError' ? '读取配置超时，预检已暂停。'
      : error instanceof Error ? error.message : '读取配置失败，预检已暂停。');
  } finally {
    clearTimeout(deadline);
    if (request === configurationRequest) { configurationLoading = false; refreshControls(); }
  }
}

function addDataSection(title, data) {
  if (data === undefined) return;
  const section = document.createElement('section');
  section.className = 'data-section';
  const heading = document.createElement('h4');
  heading.textContent = title;
  section.append(heading);
  if (data && typeof data === 'object' && !Array.isArray(data)) {
    const list = document.createElement('dl');
    list.className = 'data-list';
    const rows = Object.entries(data).flatMap(([key, value]) => {
      const label = labels[key] ?? key;
      return value && typeof value === 'object' && !Array.isArray(value)
        ? Object.entries(value).map(([nested, item]) => [`${label} · ${labels[nested] ?? nested}`, item])
        : [[label, value]];
    });
    for (const [label, value] of rows) {
      const row = document.createElement('div');
      const term = document.createElement('dt');
      const description = document.createElement('dd');
      term.textContent = label;
      description.textContent = plain(value);
      row.append(term, description);
      list.append(row);
    }
    section.append(list);
  } else {
    const content = document.createElement('pre');
    content.className = 'json-data';
    content.textContent = plain(data);
    section.append(content);
  }
  ui['result-data'].append(section);
}

function renderResult(payload, request) {
  hasPreviewed = true;
  const passed = payload.status === 'SIMULATION_PASSED';
  ui['result-empty'].hidden = true;
  ui['result-content'].hidden = false;
  ui['result-summary'].classList.toggle('blocked', !passed);
  setBadge('result-badge', passed ? '模拟通过' : '已阻止', passed ? 'success' : 'blocked');
  setText('result-status-label', passed ? 'SIMULATION_PASSED / 仅模拟' : 'BLOCKED / 已停止');
  setText('result-title', passed ? '本次模拟通过' : '本次预检已阻止');
  amountSummaryText.textContent = humanSummary(payload, request, configuration);
  amountSummary.hidden = !amountSummaryText.textContent;
  setText('result-description', passed ? '仅对应本次公开地址、数量与当时的链上状态。没有签名、没有下单。'
    : payload.error?.message ?? payload.reason ?? payload.message ?? '当前条件未通过检查，未继续执行。请查看下方原因。');
  setText('result-side', request.side === 'BUY' ? '买入 FCLAB · 输入 SOL' : '卖出 FCLAB · 收入 WSOL');
  setText('result-input', request.amountAtomic);
  setText('result-wallet', request.wallet);
  const warnings = Array.isArray(payload.warnings) ? [...payload.warnings]
    : payload.warnings === undefined ? [] : [payload.warnings];
  const explanation = `${payload.error?.code ?? payload.reasonCode ?? ''} ${payload.error?.message ?? payload.reason ?? ''} ${plain(payload.warnings ?? '')}`;
  if (!passed && /ATA|associated.?token|关联代币|代币账户不存在|缺少.*账户/i.test(explanation)) {
    warnings.unshift('钱包缺少所需的关联代币账户（ATA），当前预检被阻止。本页面不会创建账户或请求任何签名。');
  }
  if (request.side === 'SELL') warnings.push('本次卖出以 WSOL 为输出资产，不会自动转回 SOL。');
  const items = warnings.map((warning) => {
    const item = document.createElement('li');
    item.textContent = plain(warning);
    return item;
  });
  ui['result-warnings'].replaceChildren(...items);
  ui['result-warning-box'].hidden = items.length === 0;
  ui['result-data'].replaceChildren();
  // 节点和服务器返回的所有内容均按普通文本展示，禁止作为页面结构解析。
  addDataSection('报价与最小到账', payload.quote);
  addDataSection('交易模拟', payload.simulation);
  addDataSection('资金池实际数据', payload.pool);
  addDataSection('钱包余额与模拟变化', payload.balances);
  addDataSection('阻止原因', payload.error);
}

async function runPreview(event) {
  event.preventDefault();
  if (previewPending || configurationLoading || !configuration?.walletEnabled || selectionDirty) return;
  const current = session.getState();
  const amountAtomic = currentAtomic();
  if (current.status !== 'connected' || !current.address || amountAtomic === null) return;
  invalidatePreview('正在重新检查。');
  const ticket = previewGeneration;
  const request = { wallet: current.address, side, amountAtomic };
  const isCurrent = () => ticket === previewGeneration && session.getState().revision === current.revision
    && session.getState().address === request.wallet;
  const controller = new AbortController();
  previewAbort = controller;
  previewPending = true;
  let timedOut = false;
  const deadline = setTimeout(() => { timedOut = true; controller.abort(); }, 60_000);
  setBadge('result-badge', '检查中', 'loading');
  setText('empty-title', '正在读取并模拟');
  setText('empty-description', '读取钱包余额与资金池，核对报价后进行模拟。期间不会签名或下单。');
  ui['results-panel'].setAttribute('aria-busy', 'true');
  refreshControls();
  try {
    const headers = { Accept: 'application/json', 'Content-Type': 'application/json' };
    if (configuration.csrfToken) headers['X-CSRF-Token'] = configuration.csrfToken;
    const response = await fetch(apiPreview, { method: 'POST', cache: 'no-store', credentials: 'same-origin',
      redirect: 'error', mode: 'same-origin', headers, body: JSON.stringify(request), signal: controller.signal });
    const payload = await response.json();
    if (!isCurrent()) return;
    if (!response.ok) {
      renderResult({ status: 'BLOCKED', error: payload?.error ?? { code: `HTTP_${response.status}`, message: '预检请求被服务器拒绝。' } }, request);
      return;
    }
    if (!payload || !['SIMULATION_PASSED', 'BLOCKED'].includes(payload.status)) {
      throw new Error('服务器未返回可识别的预检结果，不能判定为通过。');
    }
    if (payload.wallet !== request.wallet || payload.side !== request.side
        || (payload.quote?.inputAtomic !== undefined && payload.quote.inputAtomic !== request.amountAtomic)) {
      throw new Error('返回结果与本次钱包、方向或输入数量不一致，已丢弃。');
    }
    if (payload.executionAllowed !== false || (payload.status === 'SIMULATION_PASSED' && payload.error != null)) {
      throw new Error('返回结果不符合只读预检合同，不能判定为通过。');
    }
    renderResult(payload, request);
  } catch (error) {
    if (!isCurrent()) return;
    renderResult({ status: 'BLOCKED', error: { code: timedOut ? 'PREVIEW_TIMEOUT' : 'PREVIEW_UNAVAILABLE',
      message: timedOut ? '预检超过等待期限，结果未知。请重新预检；此页没有提交交易。'
        : error instanceof Error ? error.message : '预检未完成，无法确定结果。' } }, request);
  } finally {
    clearTimeout(deadline);
    if (isCurrent()) {
      previewPending = false;
      previewAbort = null;
      ui['results-panel'].setAttribute('aria-busy', 'false');
      refreshControls();
    }
  }
}

ui['wallet-select'].addEventListener('change', () => {
  selectionDirty = true;
  invalidatePreview('钱包选择已改变，请先连接选中的钱包。');
  refreshControls();
});
ui['connect-wallet'].addEventListener('click', async () => {
  if (!configuration?.walletEnabled || !ui['wallet-select'].value) return;
  selectionDirty = false;
  try { await session.connect(ui['wallet-select'].value); }
  catch { onWalletChange(session.getState()); }
});
ui['disconnect-wallet'].addEventListener('click', async () => {
  selectionDirty = false;
  try { await session.disconnect(); }
  catch { onWalletChange(session.getState()); }
});
ui['side-buy'].addEventListener('click', () => { if (side !== 'BUY') setSide('BUY'); });
ui['side-sell'].addEventListener('click', () => { if (side !== 'SELL') setSide('SELL'); });
ui['amount-input'].addEventListener('input', () => {
  humanAmounts[side] = ui['amount-input'].value;
  invalidatePreview('输入数量已改变，请重新预检。');
  refreshControls();
});
ui['preview-form'].addEventListener('submit', runPreview);
ui['reload-config'].addEventListener('click', loadConfiguration);
window.addEventListener('pagehide', () => { invalidatePreview('页面已离开。'); configuration = null; session.destroy(); });
window.addEventListener('pageshow', (event) => { if (event.persisted) window.location.reload(); });
onWalletChange(session.getState());
loadConfiguration();

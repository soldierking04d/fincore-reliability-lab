// 确定性内存教学模型；不是数据库账本、生产撮合引擎或崩溃恢复实现。
// 所有资金计算均使用 BigInt 最小单位；Number 只用于页面步骤下标。
const SCALE = 10000n;
const PRICE = 10n;
const FEE_BPS = { maker: 5n, taker: 10n };
const ASSETS = ['ABC', 'USDT'];
const LIMITATION = '仅为顺序执行的内存状态模型，不连接数据库、网络或钱包；不证明数据库原子性、真实并发安全、持久化、崩溃恢复、XA 或生产高可用。';
const amount = value => BigInt(value) * SCALE;
const decimal = value => {
  const sign = value < 0n ? '-' : '';
  const absolute = value < 0n ? -value : value;
  const fraction = (absolute % SCALE).toString().padStart(4, '0').replace(/0+$/, '');
  return `${sign}${absolute / SCALE}${fraction ? `.${fraction}` : ''}`;
};
const check = (name, ok, detail) => ({ name, ok, detail });

function accountBook(owners, assets = ASSETS) {
  const rows = owners.flatMap(owner => assets.map(asset => ({ owner, asset, available: 0n, frozen: 0n, pending: 0n })));
  const get = (owner, asset) => {
    const row = rows.find(item => item.owner === owner && item.asset === asset);
    if (!row) throw new Error(`账户不存在：${owner}/${asset}`);
    return row;
  };
  const totals = () => Object.fromEntries(assets.map(asset => [asset, rows.filter(row => row.asset === asset).reduce((sum, row) => sum + row.available + row.frozen + row.pending, 0n)]));
  const view = () => rows.map(row => ({ owner: row.owner, asset: row.asset, available: decimal(row.available), frozen: decimal(row.frozen), pending: decimal(row.pending), total: decimal(row.available + row.frozen + row.pending) }));
  const checks = initial => [
    ...assets.map(asset => check(`${asset} 总量守恒`, totals()[asset] === initial[asset], `初始 ${decimal(initial[asset])}；当前 ${decimal(totals()[asset])}（含手续费和过渡账户）`)),
    check('账户桶非负', rows.every(row => row.available >= 0n && row.frozen >= 0n && row.pending >= 0n), 'available、frozen、pending 均不小于 0'),
  ];
  return { get, totals, view, checks };
}

function packageCase(kind, scenario, title, snapshots, step) {
  const selected = step === undefined ? snapshots.length - 1 : step;
  if (!Number.isInteger(selected) || selected < 0 || selected >= snapshots.length) throw new Error('步骤必须是范围内的整数');
  return { kind, scenario, title, units: { ABC: 4, USDT: 4 }, snapshots, current: snapshots[selected], maxStep: snapshots.length - 1, limitation: LIMITATION };
}

export function buildOrderCase({ scenario = 'rest', step } = {}) {
  const titles = {
    rest: '40 / 40 / 20 成交，C 保留 30',
    'cancel-before-settle': 'T3 结算前撤 C 的未成交 30',
    'fill-rest': 'D 再买 30，C 全部成交',
    'fill-then-cancel': '权威状态再成交 10 后，只撤剩余 20',
    'roles-reversed': '同一 C 卖单先 Taker、后 Maker',
  };
  if (!Object.hasOwn(titles, scenario)) throw new Error(`未知订单场景：${scenario}`);
  const extraQuantity = scenario === 'fill-rest' || scenario === 'roles-reversed' ? 30n : scenario === 'fill-then-cancel' ? 10n : 0n;
  const book = accountBook(['U', 'A', 'B', 'C', ...(extraQuantity ? ['D'] : []), 'FEE']);
  book.get('U', 'USDT').available = amount('1500');
  for (const [owner, quantity] of [['A', '40'], ['B', '40'], ['C', '50']]) book.get(owner, 'ABC').available = amount(quantity);
  if (extraQuantity) book.get('D', 'USDT').available = extraQuantity * PRICE * SCALE;
  const initial = book.totals();
  const orders = [], trades = [], executions = [], journals = [], snapshots = [];
  const applied = new Set();
  const findOrder = id => {
    const found = orders.find(order => order.id === id);
    if (!found) throw new Error(`订单不存在：${id}`);
    return found;
  };
  const remaining = order => order.quantity - order.filled - order.canceled;
  const frozenAsset = order => order.side === 'BUY' ? 'USDT' : 'ABC';
  const frozenAmount = order => (remaining(order) + order.filled - order.settled) * (order.side === 'BUY' ? PRICE : 1n);
  const orderView = order => {
    const roles = [...new Set(executions.filter(row => row.orderId === order.id).map(row => row.role))];
    return {
      id: order.id, owner: order.owner, side: order.side,
      role: roles.length > 1 ? 'mixed' : roles[0] || 'unfilled',
      status: remaining(order) > 0n ? (order.filled ? 'PARTIALLY_FILLED' : 'OPEN') : order.canceled ? 'CANCELED' : 'FILLED',
      settlementStatus: !order.filled ? 'NOT_REQUIRED' : order.settled === order.filled ? 'SETTLED' : order.settled ? 'PARTIAL' : 'PENDING',
      quantity: decimal(order.quantity), filledQuantity: decimal(order.filled), settledQuantity: decimal(order.settled),
      canceledQuantity: decimal(order.canceled), remainingQuantity: decimal(remaining(order)),
      frozenQuantity: decimal(frozenAmount(order)), frozenAsset: frozenAsset(order),
      cumulativeQuote: decimal(order.filled * PRICE), cumulativeFee: decimal(order.fee), feeAsset: order.side === 'BUY' ? 'ABC' : 'USDT',
    };
  };
  const snapshot = (title, event, notes = []) => {
    const checks = [
      ...book.checks(initial),
      check('委托数量闭合', orders.every(order => order.quantity === order.filled + order.canceled + remaining(order) && remaining(order) >= 0n), '原始数量 = 已成交 + 已撤 + 未成交'),
      check('账务进度不超过成交进度', orders.every(order => order.settled >= 0n && order.settled <= order.filled), '已结算数量 ≤ 已成交数量'),
      check('冻结覆盖未成交及待结算成交', orders.every(order => book.get(order.owner, frozenAsset(order)).frozen === frozenAmount(order)), '冻结量 = 未成交占用 + 已成交待结算占用；买单乘成交价 10'),
      check('每个成交对应双侧执行', executions.length === trades.length * 2 && trades.every(trade => executions.filter(execution => execution.tradeId === trade.id).length === 2), '每个 trade 关联买、卖各一个 execution；双方各自累计'),
      check('结算流水无重复', journals.length === applied.size * 6 && trades.filter(trade => trade.settled).length === applied.size, '每个已结算 trade 只产生六条资产分录'),
    ];
    if (checks.some(row => !row.ok)) throw new Error(`资金不变量失败：${checks.filter(row => !row.ok).map(row => row.name).join('、')}`);
    snapshots.push({
      step: snapshots.length, title, event, orders: orders.map(orderView),
      trades: trades.map(trade => ({ id: trade.id, buyer: trade.buyer, seller: trade.seller, quantity: decimal(trade.quantity), quote: decimal(trade.quantity * PRICE), buyerRole: trade.buyerRole, sellerRole: trade.sellerRole, buyerFee: decimal(trade.buyerFee), sellerFee: decimal(trade.sellerFee), buyerFeeBps: String(trade.buyerFeeBps), sellerFeeBps: String(trade.sellerFeeBps), status: trade.settled ? 'SETTLED' : 'PENDING' })),
      executions: executions.map(execution => ({ ...execution, status: applied.has(execution.tradeId) ? 'SETTLED' : 'PENDING' })),
      balances: book.view(), journals: journals.map(row => ({ ...row })), idempotencyKeys: [...applied], checks, notes,
    });
  };
  const addOrder = (owner, side, quantity) => {
    const order = { id: owner, owner, side, quantity, filled: 0n, settled: 0n, canceled: 0n, fee: 0n };
    const account = book.get(owner, frozenAsset(order));
    const required = frozenAmount(order);
    if (account.available < required) throw new Error('可用余额不足，不能冻结委托');
    account.available -= required;
    account.frozen += required;
    orders.push(order);
  };
  const match = (id, buyerId, sellerId, quantity, buyerRole = 'taker') => {
    const buyer = findOrder(buyerId), seller = findOrder(sellerId);
    if (buyer.side !== 'BUY' || seller.side !== 'SELL' || quantity <= 0n || remaining(buyer) < quantity || remaining(seller) < quantity) throw new Error('撮合数量或订单方向不合法');
    const sellerRole = buyerRole === 'maker' ? 'taker' : 'maker';
    // 在撮合时按参与方角色锁定费率与最小单位费用；这些固定案例无隐含舍入。
    const buyerFeeBps = FEE_BPS[buyerRole], sellerFeeBps = FEE_BPS[sellerRole];
    if (quantity * buyerFeeBps % 10000n || quantity * PRICE * sellerFeeBps % 10000n) throw new Error('本案例费用必须在四位最小单位内精确表示');
    const buyerFee = quantity * buyerFeeBps / 10000n, sellerFee = quantity * PRICE * sellerFeeBps / 10000n;
    const trade = { id, buyer: buyerId, seller: sellerId, quantity, buyerRole, sellerRole, buyerFee, sellerFee, buyerFeeBps, sellerFeeBps, settled: false };
    trades.push(trade);
    for (const [order, role, fee, feeBps] of [[buyer, buyerRole, buyerFee, buyerFeeBps], [seller, sellerRole, sellerFee, sellerFeeBps]]) {
      order.filled += quantity;
      order.fee += fee;
      executions.push({ id: `${id}:${order.id}`, tradeId: id, orderId: order.id, owner: order.owner, side: order.side, role, quantity: decimal(quantity), quote: decimal(quantity * PRICE), fee: decimal(fee), feeBps: String(feeBps), feeAsset: order.side === 'BUY' ? 'ABC' : 'USDT', cumulativeQuantity: decimal(order.filled), cumulativeQuote: decimal(order.filled * PRICE), cumulativeFee: decimal(order.fee) });
    }
  };
  const settle = id => {
    if (applied.has(id)) return false;
    const trade = trades.find(row => row.id === id);
    if (!trade) throw new Error('不能结算不存在的成交');
    const quantity = trade.quantity, quote = quantity * PRICE;
    // 结算使用该笔成交已锁定的费用，不读取当前费率、方向或订单汇总角色重算。
    const { buyerFee, sellerFee } = trade;
    const postings = [
      [trade.seller, 'ABC', 'frozen', -quantity], [trade.buyer, 'ABC', 'available', quantity - buyerFee], ['FEE', 'ABC', 'available', buyerFee],
      [trade.buyer, 'USDT', 'frozen', -quote], [trade.seller, 'USDT', 'available', quote - sellerFee], ['FEE', 'USDT', 'available', sellerFee],
    ];
    // 同步模型先验证整组分录再应用；真实系统还须数据库事务与唯一约束。
    if (postings.some(([owner, asset, bucket, delta]) => book.get(owner, asset)[bucket] + delta < 0n)) throw new Error('结算冻结余额不足');
    for (const [owner, asset, bucket, delta] of postings) {
      book.get(owner, asset)[bucket] += delta;
      journals.push({ id: `${id}:${journals.length + 1}`, tradeId: id, owner, asset, bucket, delta: decimal(delta) });
    }
    findOrder(trade.buyer).settled += quantity;
    findOrder(trade.seller).settled += quantity;
    trade.settled = true;
    applied.add(id);
    return true;
  };
  const cancelRest = id => {
    const order = findOrder(id), quantity = remaining(order);
    // 撤单只读取当前权威状态的未成交量；已成交但未结算的占用不可释放。
    const released = quantity * (order.side === 'BUY' ? PRICE : 1n);
    const account = book.get(order.owner, frozenAsset(order));
    account.frozen -= released;
    account.available += released;
    order.canceled += quantity;
  };

  snapshot('初始余额：U 有 1500 USDT', 'initial', ['ABC/USDT 固定成交价 10；每笔成交 Maker 5bp（0.05%）、Taker 10bp（0.1%），从收到的资产扣除并在撮合时锁定。', ...(extraQuantity ? [`D 另有 ${extraQuantity * PRICE} USDT，稍后下单。`] : [])]);
  if (scenario === 'roles-reversed') addOrder('U', 'BUY', amount('100'));
  for (const [owner, quantity] of [['A', '40'], ['B', '40'], ['C', '50']]) addOrder(owner, 'SELL', amount(quantity));
  if (scenario !== 'roles-reversed') addOrder('U', 'BUY', amount('100'));
  snapshot('四个订单冻结资金', 'frozen', [scenario === 'roles-reversed' ? 'U 买单先挂，后来的 A/B/C 卖单主动吃单。' : 'A/B/C 卖单先挂，U 的买单后到。', 'U 只冻结 1000 USDT，1500 余额中的另外 500 仍可用。']);
  for (const [id, seller, quantity] of [['T1', 'A', '40'], ['T2', 'B', '40'], ['T3', 'C', '20']]) match(id, 'U', seller, amount(quantity), scenario === 'roles-reversed' ? 'maker' : 'taker');
  snapshot('撮合产生 T1/T2/T3 与六条参与方执行', 'matched', ['U 已成交 100，但尚未结算；FILLED 不代表资金已到账。', 'Maker/Taker 属于该次成交的参与方角色，与 BUY/SELL 方向分开。']);
  settle('T1');
  snapshot('T1 结算：A 卖出 40', 'settled-T1', ['U 累计成交 100，累计已结算 40；两种进度独立记录。']);
  settle('T2');
  snapshot('T2 结算：B 卖出 40', 'settled-T2');
  if (scenario === 'fill-then-cancel') {
    addOrder('D', 'BUY', amount('10'));
    match('T4', 'D', 'C', amount('10'));
    snapshot('撤单请求前，权威撮合状态又成交 10', 'matched-T4', ['页面旧快照仍可能显示剩余 30；权威状态已变为剩余 20。', 'T3 的 20 和 T4 的 10 均尚未结算。']);
  }
  if (scenario === 'cancel-before-settle' || scenario === 'fill-then-cancel') {
    cancelRest('C');
    snapshot('撤 C 当前未成交余量，保留待结算冻结', 'canceled', [scenario === 'fill-then-cancel' ? '只撤 20；待结算 30 仍冻结，不能按旧快照撤 30。' : '只撤 30；T3 的 20 尚待结算，仍冻结。']);
  }
  settle('T3');
  snapshot('T3 结算：C 向 U 卖出 20', 'settled-T3');
  if (scenario === 'fill-rest' || scenario === 'roles-reversed') {
    addOrder('D', 'BUY', amount('30'));
    snapshot('D 冻结 300 USDT 买入 30', 'frozen-D');
    match('T4', 'D', 'C', amount('30'));
    snapshot('T4 撮合 C 的余下 30', 'matched-T4', ['C 在自己的订单中累计成交 50；D 的独立累计成交为 30。', ...(scenario === 'roles-reversed' ? ['同一个 C 订单在 T3 是 Taker（卖费 0.2 USDT），挂住的余量在 T4 成为 Maker（卖费 0.15 USDT）；累计卖费 0.35 USDT。'] : [])]);
  }
  if (extraQuantity) {
    settle('T4');
    snapshot('T4 结算完成', 'settled-T4');
  }
  for (const trade of trades) settle(trade.id);
  snapshot('重复投递结算：余额与流水不变', 'duplicate-settlement', ['演示内存幂等键命中；真实数据库仍须唯一约束及包含余额、分录、状态、inbox/outbox 的明确事务边界。', 'journals 展示结算分录；委托冻结与撤单桶内转换由步骤快照呈现。']);
  return packageCase('order', scenario, titles[scenario], snapshots, step);
}

export function buildWorkflowCase({ scenario = 'retry', step } = {}) {
  const titles = { retry: '跨分片失败后重试推进', compensate: '可用化决定前反向补偿', 'two-phase-commit': '2PC prepare / commit 概念示意' };
  if (!Object.hasOwn(titles, scenario)) throw new Error(`未知流程场景：${scenario}`);
  const book = accountBook(['BUYER', 'SELLER', 'CLEARING'], ['USDT']);
  book.get('BUYER', 'USDT').available = amount('100');
  const initial = book.totals(), snapshots = [], journals = [], applied = new Set();
  let decision = 'NONE';
  let buyerStatus = 'NEW', sellerStatus = 'NEW';
  const snapshot = (title, state, notes = []) => {
    const balances = book.view();
    const checks = [
      ...book.checks(initial),
      check('pending 不可花费', snapshots.every(row => row.decision === 'COMMIT' || row.balances.find(balance => balance.owner === 'SELLER').available === '0') && (decision === 'COMMIT' || book.get('SELLER', 'USDT').available === 0n), '花费能力只取 available；pending 不是可用余额'),
      check('可用化决定不可反悔', !snapshots.some(row => row.decision === 'COMMIT') || decision === 'COMMIT', '一旦 COMMIT，只能重试推进；不能切换 ABORT'),
      check('操作幂等', journals.length === applied.size * 2, '每个唯一操作只产生一组两条转移分录'),
    ];
    if (checks.some(row => !row.ok)) throw new Error('跨分片教学模型不变量失败');
    const branch = [['BUYER', buyerStatus], ['SELLER', sellerStatus]].map(([id, status]) => ({ id, status, pending: balances.find(row => row.owner === id).pending, available: balances.find(row => row.owner === id).available }));
    // 允许中止协议不等于当前示例实现了该阶段的反向业务；只标记已实现的补偿入口。
    const canCompensate = scenario !== 'two-phase-commit' && decision === 'NONE' && book.get('CLEARING', 'USDT').pending === amount('100');
    snapshots.push({ step: snapshots.length, title, state, phase: state, decision, abortAllowed: decision === 'NONE', canCompensate, branch, participants: branch.map(row => ({ ...row, detail: `仅为需要持久化的概念状态：${row.status}` })), balances, journals: journals.map(row => ({ ...row })), idempotencyKeys: [...applied], checks, notes });
  };
  const move = (operation, source, sourceBucket, target, targetBucket) => {
    if (applied.has(operation)) return false;
    const value = amount('100');
    if (book.get(source, 'USDT')[sourceBucket] < value) throw new Error('转出桶余额不足');
    for (const [owner, bucket, delta] of [[source, sourceBucket, -value], [target, targetBucket, value]]) {
      book.get(owner, 'USDT')[bucket] += delta;
      journals.push({ id: `${operation}:${journals.length + 1}`, operation, owner, asset: 'USDT', bucket, delta: decimal(delta) });
    }
    applied.add(operation);
    return true;
  };
  const decide = value => {
    if (decision !== 'NONE' && decision !== value) throw new Error('决定已不可逆，只能推进原决定');
    decision = value;
  };
  const compensate = () => {
    if (decision === 'COMMIT') return false;
    decide('ABORT');
    move('COMPENSATION', 'CLEARING', 'pending', 'BUYER', 'available');
    buyerStatus = 'COMPENSATED';
    sellerStatus = 'ABORTED';
    return true;
  };

  snapshot('初始：BUYER 可用 100 USDT', 'INITIAL', ['独立 100 USDT 划转示意，不复用上面的 ABC 成交账。', LIMITATION]);
  if (scenario === 'two-phase-commit') {
    move('PREPARE_BUYER', 'BUYER', 'available', 'BUYER', 'frozen');
    buyerStatus = 'PREPARED'; sellerStatus = 'PREPARED';
    snapshot('两个参与方 prepare 后等待协调者', 'PREPARED_WAITING', ['准备阶段持有资源；SELLER 还不能花费。', '真实 2PC 需要可恢复的协调者日志和参与方事务；这里没有 XA 资源管理器。']);
    decide('COMMIT');
    snapshot('协调者记录 COMMIT 决定', 'COMMIT_DECIDED', ['模拟需要持久化的决定；协调者不可将 COMMIT 改成 ABORT。']);
    move('COMMIT_BUYER', 'BUYER', 'frozen', 'CLEARING', 'pending');
    buyerStatus = 'COMMITTED'; sellerStatus = 'COMMIT_RETRY';
    snapshot('BUYER 提交，SELLER 提交响应失败', 'COMMIT_RETRY', ['结果未知/响应失败不等于全局回滚；应查询并推进已决定的 COMMIT。']);
    move('COMMIT_SELLER', 'CLEARING', 'pending', 'SELLER', 'available');
    sellerStatus = 'COMMITTED';
    snapshot('重试 SELLER 提交完成', 'COMPLETED');
    move('COMMIT_SELLER', 'CLEARING', 'pending', 'SELLER', 'available');
    snapshot('重复提交消息：无新分录', 'DUPLICATE');
  } else {
    move('POST_BUYER', 'BUYER', 'available', 'CLEARING', 'pending');
    buyerStatus = 'POSTED'; sellerStatus = 'RETRY_REQUIRED';
    snapshot('BUYER 已记账，SELLER 分片失败', 'SELLER_FAILED', ['BUYER 成功分录保留；100 暂存过渡账户，SELLER 还没有可用余额。', '生产系统需持久化流程状态、分支状态、幂等键和恢复任务；当前只是内存字段。']);
    if (scenario === 'compensate') {
      compensate();
      snapshot('决定前 ABORT：追加独立反向补偿', 'COMPENSATED', ['原成功分录保留，新增 COMPENSATION 反向流水；不修改成功历史。']);
      compensate();
      snapshot('重复补偿：余额与流水不变', 'DUPLICATE');
    } else {
      move('POST_SELLER', 'CLEARING', 'pending', 'SELLER', 'pending');
      sellerStatus = 'POSTED_PENDING';
      snapshot('重试 SELLER 成功，入 pending', 'BOTH_POSTED', ['SELLER 的 100 是 pending，可花费余额仍为 0。']);
      decide('COMMIT');
      snapshot('记录不可逆的可用化 COMMIT 决定', 'RELEASE_DECIDED', ['发生恢复时只能继续释放可用余额；不能因后续失败改做补偿。']);
      const rejected = !compensate();
      snapshot('模拟决定后补偿请求：被拒绝', 'COMPENSATION_REJECTED', [rejected ? 'COMMIT 决定阻止 ABORT；余额和成功历史均未变化。' : '意外状态']);
      move('RELEASE_SELLER', 'SELLER', 'pending', 'SELLER', 'available');
      sellerStatus = 'AVAILABLE';
      snapshot('推进可用化，SELLER 可花费 100', 'COMPLETED');
      move('POST_BUYER', 'BUYER', 'available', 'CLEARING', 'pending');
      move('POST_SELLER', 'CLEARING', 'pending', 'SELLER', 'pending');
      move('RELEASE_SELLER', 'SELLER', 'pending', 'SELLER', 'available');
      snapshot('重复所有成功消息：无新分录', 'DUPLICATE');
    }
  }
  return packageCase('workflow', scenario, titles[scenario], snapshots, step);
}

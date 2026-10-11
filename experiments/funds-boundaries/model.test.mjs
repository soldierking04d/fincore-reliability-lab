import test from 'node:test';
import assert from 'node:assert/strict';
import { buildOrderCase, buildWorkflowCase } from './model.mjs';

const last = result => result.snapshots.at(-1);
const balance = (snapshot, owner, asset) => snapshot.balances.find(row => row.owner === owner && row.asset === asset);
const order = (snapshot, id) => snapshot.orders.find(row => row.id === id);

test('四个订单、三个撮合、六个参与方执行；成交与账务进度分开', () => {
  const result = buildOrderCase();
  const matched = result.snapshots.find(row => row.event === 'matched');
  assert.equal(matched.orders.length, 4);
  assert.equal(matched.trades.length, 3);
  assert.equal(matched.executions.length, 6);
  assert.deepEqual(matched.trades.map(row => row.quantity), ['40', '40', '20']);
  assert.equal(order(matched, 'U').filledQuantity, '100');
  assert.equal(order(matched, 'U').settledQuantity, '0');
  assert.equal(order(matched, 'U').status, 'FILLED');
  assert.equal(order(matched, 'U').settlementStatus, 'PENDING');
  assert.equal(balance(matched, 'U', 'USDT').available, '500');
  assert.equal(balance(matched, 'U', 'USDT').frozen, '1000');
  assert.deepEqual(matched.executions.filter(row => row.orderId === 'U').map(row => row.cumulativeQuantity), ['40', '80', '100']);
  assert.deepEqual(matched.executions.filter(row => row.side === 'SELL').map(row => row.cumulativeQuantity), ['40', '40', '20']);
});

test('成交后独立金额预期、手续费和 C 未成交 30 保留冻结', () => {
  const final = last(buildOrderCase());
  assert.deepEqual([balance(final, 'U', 'ABC').available, balance(final, 'U', 'USDT').available], ['99.9', '500']);
  assert.deepEqual(['A', 'B', 'C'].map(owner => balance(final, owner, 'USDT').available), ['399.8', '399.8', '199.9']);
  assert.deepEqual([balance(final, 'FEE', 'ABC').available, balance(final, 'FEE', 'USDT').available], ['0.1', '0.5']);
  assert.deepEqual([balance(final, 'C', 'ABC').available, balance(final, 'C', 'ABC').frozen], ['0', '30']);
  assert.equal(order(final, 'C').remainingQuantity, '30');
  assert.equal(order(final, 'C').settledQuantity, '20');
  assert.equal(final.journals.length, 18);
});

test('T3 未结算时先撤 C 的 30，只释放未成交部分，20 仍冻结', () => {
  const result = buildOrderCase({ scenario: 'cancel-before-settle' });
  const canceled = result.snapshots.find(row => row.event === 'canceled');
  assert.deepEqual([balance(canceled, 'C', 'ABC').available, balance(canceled, 'C', 'ABC').frozen], ['30', '20']);
  assert.equal(order(canceled, 'C').canceledQuantity, '30');
  assert.equal(order(canceled, 'C').settledQuantity, '0');
  assert.equal(order(canceled, 'C').status, 'CANCELED');
  assert.equal(order(canceled, 'C').settlementStatus, 'PENDING');
  assert.deepEqual([balance(last(result), 'C', 'ABC').available, balance(last(result), 'C', 'ABC').frozen], ['30', '0']);
  assert.equal(balance(last(result), 'C', 'USDT').available, '199.9');
});

test('新买家 D 的 T4 吃完余下 30，费用独立累计', () => {
  const final = last(buildOrderCase({ scenario: 'fill-rest' }));
  assert.deepEqual([final.orders.length, final.trades.length, final.executions.length], [5, 4, 8]);
  assert.equal(balance(final, 'D', 'ABC').available, '29.97');
  assert.equal(balance(final, 'D', 'USDT').available, '0');
  assert.equal(balance(final, 'C', 'USDT').available, '499.75');
  assert.equal(balance(final, 'C', 'ABC').frozen, '0');
  assert.equal(order(final, 'C').filledQuantity, '50');
  assert.equal(order(final, 'C').cumulativeFee, '0.25');
  assert.equal(final.executions.find(row => row.tradeId === 'T4' && row.owner === 'C').cumulativeQuantity, '50');
  assert.deepEqual([balance(final, 'FEE', 'ABC').available, balance(final, 'FEE', 'USDT').available], ['0.13', '0.65']);
});

test('权威状态又成交 10 后，撤单只能撤 20，待结算 30 不释放', () => {
  const result = buildOrderCase({ scenario: 'fill-then-cancel' });
  const canceled = result.snapshots.find(row => row.event === 'canceled');
  assert.equal(order(canceled, 'C').filledQuantity, '30');
  assert.equal(order(canceled, 'C').canceledQuantity, '20');
  assert.deepEqual([balance(canceled, 'C', 'ABC').available, balance(canceled, 'C', 'ABC').frozen], ['20', '30']);
  const final = last(result);
  assert.equal(balance(final, 'C', 'USDT').available, '299.85');
  assert.equal(balance(final, 'D', 'ABC').available, '9.99');
  assert.equal(balance(final, 'C', 'ABC').available, '20');
  assert.equal(balance(final, 'C', 'ABC').frozen, '0');
});

test('同一 C 卖单先 Taker 后 Maker，按每笔角色锁定费用', () => {
  const normal = last(buildOrderCase());
  const result = buildOrderCase({ scenario: 'roles-reversed' });
  const reversed = last(result);
  assert(normal.executions.filter(row => row.side === 'BUY').every(row => row.role === 'taker'));
  assert(reversed.executions.filter(row => row.owner === 'U').every(row => row.role === 'maker'));
  assert.deepEqual(reversed.executions.filter(row => row.owner === 'C').map(row => [row.tradeId, row.role, row.fee, row.cumulativeQuantity]), [['T3', 'taker', '0.2', '20'], ['T4', 'maker', '0.15', '50']]);
  assert.equal(order(reversed, 'C').role, 'mixed');
  assert.equal(order(reversed, 'C').cumulativeFee, '0.35');
  assert.deepEqual(['A', 'B'].map(owner => order(reversed, owner).cumulativeFee), ['0.4', '0.4']);
  assert.equal(balance(reversed, 'U', 'ABC').available, '99.95');
  assert.equal(balance(reversed, 'D', 'ABC').available, '29.97');
  assert.equal(balance(reversed, 'C', 'USDT').available, '499.65');
  assert.equal(balance(reversed, 'C', 'ABC').frozen, '0');
  assert.deepEqual([balance(reversed, 'FEE', 'ABC').available, balance(reversed, 'FEE', 'USDT').available], ['0.08', '1.15']);
  assert.deepEqual([reversed.orders.length, reversed.trades.length, reversed.executions.length], [5, 4, 8]);
  const matched = result.snapshots.find(row => row.event === 'matched');
  assert.deepEqual(matched.trades.map(row => [row.buyerFee, row.sellerFee]), [['0.02', '0.4'], ['0.02', '0.4'], ['0.01', '0.2']]);
  assert.deepEqual(reversed.trades.slice(0, 3).map(row => [row.buyerFee, row.sellerFee]), matched.trades.map(row => [row.buyerFee, row.sellerFee]));
  assert.deepEqual([reversed.trades[3].buyerFee, reversed.trades[3].sellerFee], ['0.03', '0.15']);
});

test('所有分支每一步守恒且无负数；重复投递不改变余额和历史流水', () => {
  for (const scenario of ['rest', 'cancel-before-settle', 'fill-rest', 'fill-then-cancel', 'roles-reversed']) {
    const result = buildOrderCase({ scenario });
    for (const snapshot of result.snapshots) assert(snapshot.checks.every(check => check.ok), `${scenario}/${snapshot.title}`);
    const previous = result.snapshots.at(-2);
    const replay = last(result);
    assert.equal(replay.event, 'duplicate-settlement');
    assert.deepEqual(replay.balances, previous.balances);
    assert.deepEqual(replay.journals, previous.journals);
    assert.deepEqual(replay.executions, previous.executions);
    assert.doesNotThrow(() => JSON.stringify(result));
  }
  assert.equal(buildOrderCase({ step: 2 }).current.step, 2);
  assert.throws(() => buildOrderCase({ scenario: 'unknown' }), /未知/);
  assert.throws(() => buildOrderCase({ step: 999 }), /步骤/);
});

test('跨分片失败后资金留在过渡桶，pending 不可花，可用化决定后推进', () => {
  const result = buildWorkflowCase();
  const failed = result.snapshots.find(row => row.state === 'SELLER_FAILED');
  assert.equal(balance(failed, 'BUYER', 'USDT').available, '0');
  assert.equal(balance(failed, 'CLEARING', 'USDT').pending, '100');
  assert.equal(failed.canCompensate, true);
  const pending = result.snapshots.find(row => row.state === 'BOTH_POSTED');
  assert.equal(balance(pending, 'SELLER', 'USDT').pending, '100');
  assert.equal(balance(pending, 'SELLER', 'USDT').available, '0');
  assert.equal(pending.abortAllowed, true);
  assert.equal(pending.canCompensate, false);
  assert.equal(result.snapshots[0].canCompensate, false);
  const decided = result.snapshots.find(row => row.state === 'RELEASE_DECIDED');
  assert.equal(decided.decision, 'COMMIT');
  assert.equal(decided.canCompensate, false);
  const final = last(result);
  assert.equal(balance(final, 'SELLER', 'USDT').available, '100');
  assert.deepEqual(final.journals, result.snapshots.at(-2).journals);
});

test('决定前补偿用新增反向流水，2PC prepare 不释放可用余额', () => {
  const compensated = buildWorkflowCase({ scenario: 'compensate' });
  assert.equal(balance(last(compensated), 'BUYER', 'USDT').available, '100');
  assert.equal(balance(last(compensated), 'SELLER', 'USDT').available, '0');
  assert.equal(last(compensated).journals.length, 4);
  assert.equal(last(compensated).journals[2].operation, 'COMPENSATION');
  const prepared = buildWorkflowCase({ scenario: 'two-phase-commit' });
  const waiting = prepared.snapshots.find(row => row.state === 'PREPARED_WAITING');
  assert.equal(balance(waiting, 'BUYER', 'USDT').frozen, '100');
  assert.equal(balance(waiting, 'SELLER', 'USDT').available, '0');
  assert.equal(waiting.abortAllowed, true);
  assert.equal(waiting.canCompensate, false);
  assert.equal(last(prepared).decision, 'COMMIT');
  for (const result of [compensated, prepared]) for (const snapshot of result.snapshots) assert(snapshot.checks.every(check => check.ok));
});

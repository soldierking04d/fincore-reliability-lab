# 交易资金边界：独立教学模型

这是顺序执行的纯内存状态模型，不连接数据库、网络或钱包，不修改 Java 服务与生产表。它不能证明数据库原子性、真实并发安全、持久化、崩溃恢复、XA 或生产高可用。跨分片场景中的“记录决定”只是表示真实系统必须持久化哪些状态，没有实际落盘。

## 运行与复核

从仓库根目录运行：

```sh
node --test experiments/funds-boundaries/model.test.mjs
node experiments/funds-boundaries/run.mjs --summary
node experiments/funds-boundaries/run.mjs
```

最后一条命令向标准输出生成完整确定性 JSON 证据；`--summary` 只输出逐场景检查和最终余额。模型本身不依赖 Node 专用接口，可原样作为浏览器 ES 模块使用。测试与命令入口使用 Node 内置功能，无需安装依赖。

## 交易案例

ABC/USDT 固定成交价 10。A/B/C 各有 40/40/50 ABC 并先挂卖单；U 有 1500 USDT，买 100 ABC，只冻结 1000 USDT。T1/T2/T3 分别成交 40/40/20，产生 4 个订单、3 笔 trade 和 6 条双侧参与方 execution 教学明细。这不声称生产 `trade_execution` 表会插入六行。

手续费按每笔参与方角色收取：Maker 5bp（0.05%），Taker 10bp（0.1%），从收到的资产扣除。方向决定费用币种，角色决定费率；角色由该笔成交的到达顺序决定。撮合时锁定费率和费用，结算直接使用锁定金额。基线买方是 Taker、卖方是 Maker，最终 U 获得 99.9 ABC 并保留 500 USDT；A/B/C 获得 399.8/399.8/199.9 USDT；手续费账户收到 0.1 ABC 与 0.5 USDT；C 的剩余 30 ABC 仍冻结。

支持五条确定性分支：

- `rest`：C 未成交 30 保留。
- `cancel-before-settle`：T3 未结算时撤 C 的 30，先释放 30，留下 20 冻结给 T3。
- `fill-rest`：D 另有 300 USDT，T4 买完 C 的 30；C 累计成交 50，D 独立累计 30。
- `fill-then-cancel`：T3 未结算时权威状态又成交 10，撤单只能撤 20；T3/T4 共 30 仍冻结。D 的初始 100 USDT 从第一步纳入守恒。
- `roles-reversed`：U 买单先挂，U 是 Maker，后来 A/B/C 卖方是 Taker。C 主动卖 50，T3 先成交 20、余下 30 挂住；D 另有 300 USDT，T4 主动买入 30，C 在同一个订单内变为 Maker。最终 U 收到 99.95 ABC、D 收到 29.97 ABC、C 收到 499.65 USDT；C 累计费用为 0.2 + 0.15 = 0.35 USDT，A/B 费用各 0.4 USDT；手续费账户共收到 0.08 ABC 和 1.15 USDT。

## 浏览器接口

```js
import { buildOrderCase, buildWorkflowCase } from './model.mjs';
const result = buildOrderCase({ scenario: 'rest', step: 2 });
const workflow = buildWorkflowCase({ scenario: 'retry' });
```

两个函数都返回 `{ kind, scenario, title, units, snapshots, current, maxStep, limitation }`。不传 `step` 时 `current` 是最终步骤；`snapshots` 总是含完整步骤。所有对外金额和数量都是可 JSON 序列化的普通十进制字符串，如 `"39.96"`。内部按四位精度的 BigInt 最小单位计算；固定案例费用都能精确表示，不引入隐式舍入规则。步骤下标可使用 Number。

每个快照含 `step/title/balances/checks/notes/journals/idempotencyKeys`。余额行是 `{ owner, asset, available, frozen, pending, total }`；检查行是 `{ name, ok, detail }`。

订单快照另含 `event/orders/trades/executions`。订单有独立 `filledQuantity` 和 `settledQuantity`；`FILLED` 可能仍是账务 `PENDING`。trade 的 `buyerFee/sellerFee`、`buyerFeeBps/sellerFeeBps` 与 execution 的 `fee/feeBps` 是撮合时锁定值；`cumulativeFee` 是已成交预期费用，到账时间由结算状态决定。订单 `role` 是其参与方执行角色的汇总，未成交为 `unfilled`，两种角色都有则为 `mixed`，不能由买卖方向推断。`frozenQuantity` 的币种由 `frozenAsset` 指明：买单是 USDT，卖单是 ABC。`journals` 仅展示结算分录，冻结与撤单桶内转换通过步骤快照呈现。

流程快照另含 `state/phase/decision/abortAllowed/canCompensate/branch/participants`。`branch` 行是 `{ id, status, pending, available }`，用于分支状态表。`abortAllowed` 表示协议尚未做出不可逆决定；`canCompensate` 只表示本示例已经实现的“资金仍在过渡桶”的补偿入口，不宣称实现了所有 pending 阶段的反向业务。

## 跨分片与 2PC 的能力边界

`buildWorkflowCase` 是独立 100 USDT 划转示意，支持 `retry`、`compensate`、`two-phase-commit`：

- `retry`：BUYER 已记账，SELLER 失败时资金在过渡桶；重试后进入 SELLER pending，不可花费；COMMIT 可用化决定之后拒绝补偿，只能推进到 available。重复消息不会再次记账。
- `compensate`：在可用化决定之前选择 ABORT，追加独立反向分录；成功历史不修改、不删除。
- `two-phase-commit`：准备阶段预占资源，协调者决定 COMMIT 后即使部分提交失败，也重试推进该决定。它不是 XA 实现，也未模拟数据库 prepare、日志刷盘和恢复扫描。

顺序脚本只演示重试、部分失败和撤单竞争的状态结果，不运行真实并发线程。真实系统仍需确定顺序的账户锁、事务内余额/分录/状态/inbox/outbox 写入、数据库唯一约束、持久决议及恢复流程。内存 Set 只是可见的教学幂等状态，不能当最终金融账本。

## 验证约束

每一步检查按币种守恒（含手续费及过渡账户）、桶非负、原始委托数量闭合、冻结覆盖未成交及待结算成交、账务进度不超过成交进度，以及重复结算不新增流水。测试另以固定外部数字校验 40/40/20、费用、C 三类余量分支和反转角色，避免只拿模型自己的总数证明模型正确。

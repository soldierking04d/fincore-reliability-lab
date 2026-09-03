package dev.fincore.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fincore.domain.OrderSide;
import dev.fincore.domain.UuidOrder;
import dev.fincore.infrastructure.persistence.mapper.DerivativesLabMapper;
import dev.fincore.infrastructure.persistence.mapper.DerivativesLabMapper.AccountRow;
import dev.fincore.infrastructure.persistence.mapper.DerivativesLabMapper.OperationRow;
import dev.fincore.infrastructure.persistence.mapper.DerivativesLabMapper.PositionRow;
import dev.fincore.infrastructure.persistence.mapper.OutboxMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * USDT 线性合约的四类可靠性实验，不接真实资金、不提供公开资金操作 HTTP 接口。
 *
 * <p>统一按账户锁串行修改保证金、仓位和钱包；多账户按 UUID 排序锁定。业务键而非
 * 消息编号决定财务幂等，余额、不可变决定、双腿账本、Inbox、Outbox 同事务提交。
 * 未知提交结果必须使用原业务键重试，数据库异常向上抛出，不能改用新键掩盖错误。</p>
 * <p>预算占用为独立实验；强平只实现风险重验和状态准入，不实现强平成交、保险基金、
 * ADL、阶梯保证金或组合保证金。每账户仅一个净仓位，结算池仅是模拟对手方。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.3.0
 */
@Profile("lab")
@Service
public class DerivativesLabService {
    /** 实验资产精度，不代表所有币种或交易所的精度。 */
    private static final int SCALE = 8;
    /** 固定维持保证金率仅用于可复算示例，不是交易所规则。 */
    private static final BigDecimal MAINTENANCE_RATE = new BigDecimal("0.005");
    /** 每次执行时标记价最多陈旧五秒，来自实验注入而非真实价格源。 */
    private static final Duration MARK_MAX_AGE = Duration.ofSeconds(5);
    /** 与 NUMERIC(28,8) 一致的金额上界。 */
    private static final BigDecimal LIMIT = BigDecimal.TEN.pow(20);
    /** 不可变金融事实及锁定状态的持久化接口。 */
    private final DerivativesLabMapper mapper;
    /** 复用现有事务 Outbox，使用 DERIVATIVE_LAB 事件类型标明隔离实验。 */
    private final OutboxMapper outbox;
    /** 规范化后的请求用于核对业务键载荷，禁止仅按键盲目返回成功。 */
    private final ObjectMapper json;

    /** 注入 MyBatis、事务 Outbox 与序列化器。 */
    public DerivativesLabService(DerivativesLabMapper mapper, OutboxMapper outbox, ObjectMapper json) {
        this.mapper = mapper;
        this.outbox = outbox;
        this.json = json;
    }

    /** 创建实验期初余额；重复编号失败，不覆盖旧账户，也不伪装成真实充值。 */
    @Transactional
    public void openAccount(UUID account, BigDecimal wallet) {
        wallet = decimal(wallet);
        require(wallet.signum() >= 0, "期初余额不能为负数");
        changed(mapper.openAccount(Objects.requireNonNull(account), wallet));
    }

    /** 仅在新账户上准备仓位夹具，不作为开仓接口使用。 */
    @Transactional
    public void seedPosition(UUID account, String symbol, BigDecimal quantity, BigDecimal entryPrice) {
        AccountRow row = lock(account);
        require(row.version() == 0 && mapper.position(account) == null, "只能给新账户准备一次仓位");
        quantity = decimal(quantity);
        require(quantity.signum() != 0, "初始仓位不能为零");
        changed(mapper.seedPosition(account, text(symbol, 50), quantity, positive(entryPrice)));
        changed(mapper.changeAccount(account, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    /**
     * 原子占用指定预算。同账户跨交易对仍串行；金额由可信实验给定，不代替生产 IM 计算。
     * 拒绝也是终态决定，稍后余额增加不能让原拒绝单变成成功。
     */
    @Transactional
    public Result reserve(UUID account, String orderKey, String symbol, BigDecimal amount) {
        amount = positive(amount);
        String request = payload(text(symbol, 50), amount);
        orderKey = text(orderKey, 100);
        AccountRow row = lock(account);
        Result replay = replay(account, "RESERVE", orderKey, request);
        if (replay != null) {
            return replay;
        }
        String status = !row.state().equals("ACTIVE") ? "ACCOUNT_FROZEN"
            : row.wallet().subtract(row.reserved()).compareTo(amount) < 0 ? "INSUFFICIENT_MARGIN" : "RESERVED";
        BigDecimal effect = status.equals("RESERVED") ? amount : BigDecimal.ZERO;
        if (effect.signum() != 0) {
            changed(mapper.changeAccount(account, BigDecimal.ZERO, effect));
        }
        return decision(account, "RESERVE", orderKey, request, status, effect);
    }

    /** 原订单预算释放一次，保留原占用决定；尚不存在的订单不能释放。 */
    @Transactional
    public Result release(UUID account, String orderKey) {
        orderKey = text(orderKey, 100);
        lock(account);
        String request = payload(orderKey);
        Result replay = replay(account, "RELEASE", orderKey, request);
        if (replay != null) {
            return replay;
        }
        OperationRow reservation = mapper.operation(account, "RESERVE", orderKey);
        require(reservation != null, "原预算占用不存在");
        BigDecimal effect = reservation.effect();
        if (effect.signum() != 0) {
            changed(mapper.changeAccount(account, BigDecimal.ZERO, effect.negate()));
        }
        return decision(account, "RELEASE", orderKey, request, "RELEASED", effect);
    }

    /**
     * 实验在明确的逻辑周期边界调用一次，固化当时的净仓位、标记价、费率和账户版本。
     * 相同周期重放不重读当前仓位；费率或标记价发生冲突必须报错，不能覆盖原快照。
     * 本方法不实现跨分片事件水位和历史仓位查询，不能拿现在的仓位补算真实历史周期。
     */
    @Transactional
    public void captureFunding(UUID account, String symbol, Instant cycle,
                                BigDecimal mark, BigDecimal rate) {
        symbol = text(symbol, 50);
        cycle = cycle(cycle);
        mark = positive(mark);
        rate = decimal(rate);
        require(rate.abs().compareTo(BigDecimal.ONE) <= 0, "费率绝对值超过实验上限");
        AccountRow row = lock(account);
        var existing = mapper.funding(account, symbol, cycle);
        if (existing != null) {
            require(existing.markPrice().compareTo(mark) == 0 && existing.rate().compareTo(rate) == 0,
                "同一资金费周期出现冲突载荷，必须人工复核");
            return;
        }
        PositionRow position = position(account, symbol);
        changed(mapper.captureFunding(account, symbol, cycle, position.quantity(), mark, rate, row.version()));
    }

    /**
     * 按固化周期扣收资金费，正费率多头支付、空头收取；负费率相反。
     * 不以剩余开仓预算拒绝已发生的费用：可以暴露负权益，随后由风险重验处理。
     * 结算池不属于实际保险基金；没有实现交易所范围内所有多空账户的净额结算。
     */
    @Transactional
    public Result applyFunding(UUID account, UUID pool, String symbol, Instant cycle, UUID message) {
        symbol = text(symbol, 50);
        cycle = cycle(cycle);
        Objects.requireNonNull(message, "消息编号不能为空");
        lockPair(account, pool);
        var snapshot = mapper.funding(account, symbol, cycle);
        require(snapshot != null, "缺少权威周期快照，不能按当前仓位猜测资金费");
        String key = symbol + ":" + cycle;
        String request = payload(pool, snapshot);
        Result replay = replay(account, "FUNDING", key, request);
        if (replay != null) {
            inbox(message, replay.operationId());
            return replay;
        }
        BigDecimal delta = rounded(snapshot.quantity().multiply(snapshot.markPrice())
            .multiply(snapshot.rate()).negate());
        OperationRow operation = operation(account, "FUNDING", key, request, "SETTLED", delta);
        changed(mapper.insertOperation(operation));
        cash(operation, account, pool, delta);
        inbox(message, operation.operationId());
        return publish(operation);
    }

    /**
     * 执行时检查当前仓位和方向，按 min(请求量, 当前仓位绝对值) 平仓，余量取消不反向开仓。
     * 使用实际执行量计算线性已实现盈亏，双方资金与仓位同事务提交。价格来自可信实验成交，
     * 不包含撮合、挂单排队、滑点、手续费和双向持仓模式。
     */
    @Transactional
    public Result reduceOnly(UUID account, UUID pool, String executionKey, String symbol,
                              OrderSide side, BigDecimal quantity, BigDecimal executionPrice) {
        quantity = positive(quantity);
        executionPrice = positive(executionPrice);
        symbol = text(symbol, 50);
        executionKey = text(executionKey, 100);
        Objects.requireNonNull(side, "方向不能为空");
        String request = payload(pool, symbol, side, quantity, executionPrice);
        lockPair(account, pool);
        Result replay = replay(account, "REDUCE_ONLY", executionKey, request);
        if (replay != null) {
            return replay;
        }
        AccountRow row = lock(account);
        PositionRow position = position(account, symbol);
        String rejected = !row.state().equals("ACTIVE") ? "ACCOUNT_FROZEN"
            : position.quantity().signum() == 0 ? "NO_POSITION"
            : (position.quantity().signum() > 0) != (side == OrderSide.SELL) ? "WRONG_SIDE" : null;
        if (rejected != null) {
            return decision(account, "REDUCE_ONLY", executionKey, request, rejected, BigDecimal.ZERO);
        }
        BigDecimal executed = quantity.min(position.quantity().abs());
        BigDecimal signedExecuted = executed.multiply(BigDecimal.valueOf(position.quantity().signum()));
        BigDecimal pnl = rounded(executionPrice.subtract(position.entryPrice()).multiply(signedExecuted));
        OperationRow operation = operation(account, "REDUCE_ONLY", executionKey, request, "FILLED", executed);
        changed(mapper.insertOperation(operation));
        changed(mapper.updatePosition(account, position.quantity().subtract(signedExecuted)));
        cash(operation, account, pool, pnl);
        return publish(operation);
    }

    /** 模拟从独立结算池补充保证金，双腿记账并使旧风险快照失效。 */
    @Transactional
    public Result topUp(UUID account, UUID pool, String key, BigDecimal amount) {
        amount = positive(amount);
        key = text(key, 100);
        String request = payload(pool, amount);
        lockPair(account, pool);
        Result replay = replay(account, "TOP_UP", key, request);
        if (replay != null) {
            return replay;
        }
        AccountRow payer = lock(pool);
        require(payer.state().equals("ACTIVE") && payer.wallet().subtract(payer.reserved()).compareTo(amount) >= 0,
            "模拟资金来源余额不足或已冻结");
        OperationRow operation = operation(account, "TOP_UP", key, request, "SETTLED", amount);
        changed(mapper.insertOperation(operation));
        cash(operation, account, pool, amount);
        return publish(operation);
    }

    /**
     * 手动注入接管事件；幂等的接管编号保证响应丢失重试不多升一次 Epoch。
     * 这是 fencing 实验，不替代生产 Worker 选主、租约过期和身份认证。
     */
    @Transactional
    public long takeover(UUID account, UUID command) {
        AccountRow row = lock(account);
        String key = Objects.requireNonNull(command).toString();
        String request = payload(command);
        Result replay = replay(account, "TAKEOVER", key, request);
        if (replay != null) {
            return replay.effect().longValueExact();
        }
        long epoch = Math.addExact(row.epoch(), 1);
        changed(mapper.incrementEpoch(account));
        decision(account, "TAKEOVER", key, request, "CLAIMED", BigDecimal.valueOf(epoch));
        return epoch;
    }

    /** 拍摄版本化风险输入，不相信调用者给出的风险布尔值；执行时重新计算。 */
    @Transactional
    public RiskSnapshot assess(UUID account, String symbol, BigDecimal mark, Instant observedAt) {
        AccountRow row = lock(account);
        position(account, text(symbol, 50));
        return new RiskSnapshot(account, symbol, row.version(), positive(mark),
            Objects.requireNonNull(observedAt).truncatedTo(ChronoUnit.MILLIS));
    }

    /**
     * 强平准入而非强平成交：锁内重验 Epoch、账户版本、标记价时间以及实时权益/MM。
     * 先补保证金/先平仓则旧快照失效；先进入强平则普通新单与平仓不能越过冻结状态。
     * 强平状态不会在本实验中自动退出，也不自动执行损失分摊。
     */
    @Transactional
    public Result liquidate(UUID command, RiskSnapshot snapshot, long workerEpoch) {
        Objects.requireNonNull(snapshot);
        require(workerEpoch > 0, "必须先取得有效 Epoch");
        require(snapshot.accountVersion() >= 0, "账户版本不能为负数");
        positive(snapshot.mark());
        Objects.requireNonNull(snapshot.observedAt());
        String key = Objects.requireNonNull(command).toString();
        String request = payload(snapshot, workerEpoch);
        AccountRow row = lock(snapshot.account());
        Result replay = replay(snapshot.account(), "LIQUIDATE", key, request);
        if (replay != null) {
            return replay;
        }
        String status = liquidationStatus(row, snapshot, workerEpoch);
        if (status.equals("LIQUIDATING")) {
            changed(mapper.enterLiquidation(row.accountId(), row.version(), workerEpoch));
        }
        return decision(row.accountId(), "LIQUIDATE", key, request, status, BigDecimal.ZERO);
    }

    /** 在持有账户锁的同一写事务中完成全部风险检查。 */
    private String liquidationStatus(AccountRow row, RiskSnapshot snapshot, long epoch) {
        if (row.epoch() != epoch) {
            return "STALE_EPOCH";
        }
        if (row.version() != snapshot.accountVersion()) {
            return "STALE_ACCOUNT";
        }
        Duration age = Duration.between(snapshot.observedAt(), mapper.now());
        if (age.isNegative() || age.compareTo(MARK_MAX_AGE) > 0) {
            return "STALE_MARK";
        }
        if (!row.state().equals("ACTIVE")) {
            return "ACCOUNT_FROZEN";
        }
        PositionRow position = position(row.accountId(), snapshot.symbol());
        BigDecimal equity = row.wallet().add(snapshot.mark().subtract(position.entryPrice())
            .multiply(position.quantity()));
        BigDecimal maintenance = position.quantity().abs().multiply(snapshot.mark()).multiply(MAINTENANCE_RATE);
        return position.quantity().signum() != 0 && equity.compareTo(maintenance) <= 0
            ? "LIQUIDATING" : "NOT_REQUIRED";
    }

    /** 固定顺序锁住两个不同账户；禁止交易一方同时充当自己的结算池。 */
    private void lockPair(UUID account, UUID pool) {
        Objects.requireNonNull(account);
        Objects.requireNonNull(pool);
        require(!account.equals(pool), "资金交易的两个账户必须不同");
        for (UUID id : UuidOrder.uniqueSorted(account, pool)) {
            lock(id);
        }
    }

    /** 账户不存在时明确失败，不能默认创建零余额账户。 */
    private AccountRow lock(UUID account) {
        AccountRow row = mapper.lockAccount(Objects.requireNonNull(account));
        require(row != null, "模拟账户不存在");
        return row;
    }

    /** 本实验一账户一净仓位，错合约不能借用其他合约仓位。 */
    private PositionRow position(UUID account, String symbol) {
        PositionRow row = mapper.position(account);
        require(row != null && row.symbol().equals(symbol), "当前合约仓位不存在");
        return row;
    }

    /** 规范化后的完整请求必须一致；重复决定不再修改余额或仓位。 */
    private Result replay(UUID account, String kind, String key, String request) {
        OperationRow row = mapper.operation(account, kind, key);
        if (row == null) {
            return null;
        }
        require(row.request().equals(request), "业务键载荷冲突，禁止换键自动重试");
        return new Result(row.operationId(), row.status(), row.effect(), true);
    }

    /** 追加一条终态决定与同事务 Outbox。 */
    private Result decision(UUID account, String kind, String key, String request,
                             String status, BigDecimal effect) {
        OperationRow operation = operation(account, kind, key, request, status, effect);
        changed(mapper.insertOperation(operation));
        return publish(operation);
    }

    /** 构造唯一、不可变的审计操作。 */
    private OperationRow operation(UUID account, String kind, String key, String request,
                                    String status, BigDecimal effect) {
        return new OperationRow(UUID.randomUUID(), account, kind, text(key, 100), request, status, decimal(effect));
    }

    /** 现金变动与原子双腿账本绑定；零盈亏仍增加账户版本以覆盖仓位变更。 */
    private void cash(OperationRow operation, UUID account, UUID pool, BigDecimal delta) {
        if (delta.signum() != 0) {
            require(mapper.postPair(operation.operationId(), account, pool, delta) == 2, "双腿账本必须同时写入");
        }
        changed(mapper.changeAccount(account, delta, BigDecimal.ZERO));
        changed(mapper.changeAccount(pool, delta.negate(), BigDecimal.ZERO));
    }

    /** 先写业务操作再关联 Inbox；消息键冲突会回滚整个当前事务。 */
    private void inbox(UUID message, UUID operation) {
        mapper.insertInbox(message, operation);
        var received = mapper.inboxOperation(message);
        require(received != null && operation.equals(received.operationId()), "消息编号对应了其他业务操作");
    }

    /** Outbox 放在财务修改之后；写入异常触发整个事务回滚，不吞异常。 */
    private Result publish(OperationRow operation) {
        changed(outbox.insert(operation.operationId(), operation.accountId().toString(),
            "DERIVATIVE_LAB_" + operation.kind(), payload(operation)));
        return new Result(operation.operationId(), operation.status(), operation.effect(), false);
    }

    /** 序列化失败不能降级成空载荷或成功结果。 */
    private String payload(Object... values) {
        try {
            return json.writeValueAsString(Arrays.asList(values));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("实验请求无法序列化", exception);
        }
    }

    /** 周期统一到秒，拒绝隐含截断导致不同输入碰撞同一数据库时间。 */
    private static Instant cycle(Instant value) {
        Objects.requireNonNull(value, "周期不能为空");
        require(value.getNano() == 0, "周期必须使用整秒 UTC 时间");
        return value;
    }

    /** 只接受非空、无首尾空格的有界业务标识。 */
    private static String text(String value, int max) {
        require(value != null && !value.isBlank() && value.equals(value.strip()) && value.length() <= max,
            "业务标识为空、包含首尾空格或长度超限");
        return value;
    }

    /** 用户输入必须精确表示为八位小数，不能静默丢失资金精度。 */
    private static BigDecimal decimal(BigDecimal value) {
        require(value != null && value.abs().compareTo(LIMIT) < 0, "金额为空或超出实验精度范围");
        try {
            return value.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("金额最多允许八位有效小数", exception);
        }
    }

    /** 业务数量、价格与预算必须严格为正。 */
    private static BigDecimal positive(BigDecimal value) {
        value = decimal(value);
        require(value.signum() > 0, "金额或数量必须为正数");
        return value;
    }

    /** 派生资金费/已实现盈亏统一 HALF_EVEN，双方使用同一已舍入金额的相反数。 */
    private static BigDecimal rounded(BigDecimal value) {
        return decimal(value.setScale(SCALE, RoundingMode.HALF_EVEN));
    }

    /** 持久化行数不符意味着状态与预期不同，必须回滚。 */
    private static void changed(int rows) {
        if (rows != 1) {
            throw new IllegalStateException("金融状态修改未命中唯一目标，事务已拒绝");
        }
    }

    /** 校验失败返回明确错误，不转换成成功。 */
    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }

    /** effect 含义随操作固定：占用/释放预算、实际平仓量或用户现金增量。 */
    public record Result(UUID operationId, String status, BigDecimal effect, boolean duplicate) { }

    /** 版本化风险输入；不是经过授权签名的生产价格凭证。 */
    public record RiskSnapshot(UUID account, String symbol, long accountVersion,
                               BigDecimal mark, Instant observedAt) { }
}

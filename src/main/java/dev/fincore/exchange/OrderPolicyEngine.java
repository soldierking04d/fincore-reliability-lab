package dev.fincore.exchange;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 交易对配置、订单有效期、自成交和撤改单结果的纯计算规则。
 *
 * <p>该引擎不落库、不冻结资金，只负责把订单语义转成明确决定。正式交易链路仍需在同一受控事务中
 * 保存决定、资金预占和订单状态；调用方不得把本类返回的 {@code ACCEPTED} 单独当成成交成功。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.1.0
 */
public final class OrderPolicyEngine {
    /** 订单有效期。 */
    public enum TimeInForce {
        /** 一直有效，直到成交或撤销。 */
        GTC,
        /** 立即成交可成交部分，剩余取消。 */
        IOC,
        /** 必须立即全部成交，否则全部拒绝。 */
        FOK,
        /** 有效到指定时间。 */
        GTD
    }

    /** 交易对运行模式。 */
    public enum TradingMode {
        /** 禁止一切新订单。 */
        TRADING_DISABLED,
        /** 只允许取消已有订单。 */
        CANCEL_ONLY,
        /** 只允许增加流动性的限价单。 */
        POST_ONLY,
        /** 只允许限价单。 */
        LIMIT_ONLY,
        /** 正常连续交易。 */
        FULL_TRADING,
        /** 集合竞价收单阶段。 */
        AUCTION
    }

    /** 自成交保护策略。 */
    public enum SelfTradePolicy {
        /** 减少双方可成交量并取消较小一方。 */
        DECREMENT_AND_CANCEL,
        /** 取消更早进入订单簿的委托。 */
        CANCEL_OLDEST,
        /** 取消最新进入的主动委托。 */
        CANCEL_NEWEST,
        /** 同时取消双方。 */
        CANCEL_BOTH
    }

    /** 订单类型。 */
    public enum Kind {
        /** 限价订单。 */
        LIMIT,
        /** 市价订单。 */
        MARKET,
        /** 到价后触发的止损限价订单。 */
        STOP_LIMIT
    }

    /** 买卖方向。 */
    public enum Side {
        /** 买入。 */
        BUY,
        /** 卖出。 */
        SELL
    }

    /**
     * 校验订单并计算立即执行量、挂单量和取消量。
     *
     * @param request 订单请求
     * @param instrument 当前生效的交易对配置
     * @param bestOppositePrice 对手方最优价格；无流动性时可以为 {@code null}
     * @param immediatelyAvailable 当前价格约束下可立即成交的数量
     * @param now 当前时间
     * @return 不包含资金动作的明确执行计划
     */
    public ExecutionPlan plan(OrderRequest request, InstrumentConfig instrument,
                              BigDecimal bestOppositePrice,
                              BigDecimal immediatelyAvailable, Instant now) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(instrument, "instrument");
        Objects.requireNonNull(immediatelyAvailable, "immediatelyAvailable");
        Objects.requireNonNull(now, "now");
        String rejection = validate(request, instrument, bestOppositePrice, now);
        if (rejection != null) {
            return ExecutionPlan.rejected(rejection, request.quantity());
        }
        BigDecimal available = immediatelyAvailable.max(BigDecimal.ZERO);
        BigDecimal executable = request.quantity().min(available);
        if (request.timeInForce() == TimeInForce.FOK
            && executable.compareTo(request.quantity()) < 0) {
            return ExecutionPlan.rejected("FOK_DEPTH_INSUFFICIENT", request.quantity());
        }
        if (request.postOnly() && executable.signum() > 0) {
            return ExecutionPlan.rejected("POST_ONLY_WOULD_TAKE", request.quantity());
        }
        if (request.timeInForce() == TimeInForce.IOC || request.kind() == Kind.MARKET) {
            return new ExecutionPlan("ACCEPTED", executable, BigDecimal.ZERO,
                request.quantity().subtract(executable), null);
        }
        return new ExecutionPlan("ACCEPTED", executable,
            request.quantity().subtract(executable), BigDecimal.ZERO, null);
    }

    /**
     * 计算同一受益所有人两张交叉订单的自成交处理结果。
     *
     * @param makerQuantity 被动单剩余量
     * @param takerQuantity 主动单剩余量
     * @param policy 自成交保护策略
     * @return 双方最终剩余量和取消原因
     */
    public SelfTradeResult preventSelfTrade(BigDecimal makerQuantity,
                                            BigDecimal takerQuantity,
                                            SelfTradePolicy policy) {
        requirePositive(makerQuantity, "makerQuantity");
        requirePositive(takerQuantity, "takerQuantity");
        Objects.requireNonNull(policy, "policy");
        return switch (policy) {
            case CANCEL_OLDEST -> new SelfTradeResult(BigDecimal.ZERO, takerQuantity,
                "MAKER_CANCELED");
            case CANCEL_NEWEST -> new SelfTradeResult(makerQuantity, BigDecimal.ZERO,
                "TAKER_CANCELED");
            case CANCEL_BOTH -> new SelfTradeResult(BigDecimal.ZERO, BigDecimal.ZERO,
                "BOTH_CANCELED");
            case DECREMENT_AND_CANCEL -> {
                BigDecimal decrement = makerQuantity.min(takerQuantity);
                yield new SelfTradeResult(makerQuantity.subtract(decrement),
                    takerQuantity.subtract(decrement), "DECREMENTED_WITHOUT_TRADE");
            }
        };
    }

    /**
     * 归一化撤改单的两个独立结果，防止把部分成功折叠成一个布尔值。
     *
     * @param cancelSucceeded 原订单是否撤销成功
     * @param replacementAccepted 新订单是否被接收
     * @return 可用于OMS恢复的组合结果
     */
    public CancelReplaceResult cancelReplace(boolean cancelSucceeded,
                                              boolean replacementAccepted) {
        if (cancelSucceeded && replacementAccepted) {
            return new CancelReplaceResult("REPLACED", true, true,
                "原订单已取消，新订单已接收");
        }
        if (cancelSucceeded) {
            return new CancelReplaceResult("CANCELED_ONLY", true, false,
                "原订单已取消，新订单被拒绝");
        }
        if (replacementAccepted) {
            return new CancelReplaceResult("NEW_ACCEPTED_CANCEL_FAILED", false, true,
                "原订单撤销失败但新订单已接收，必须立即查询两张订单");
        }
        return new CancelReplaceResult("UNCHANGED", false, false,
            "原订单仍可能有效，新订单未被接收");
    }

    /** 执行订单字段与产品状态校验。 */
    private String validate(OrderRequest request, InstrumentConfig instrument,
                            BigDecimal bestOppositePrice, Instant now) {
        if (!instrument.symbol().equals(request.symbol())) {
            return "UNKNOWN_SYMBOL";
        }
        if (instrument.mode() == TradingMode.TRADING_DISABLED
            || instrument.mode() == TradingMode.CANCEL_ONLY) {
            return "TRADING_MODE_REJECTED";
        }
        if ((instrument.mode() == TradingMode.LIMIT_ONLY
            || instrument.mode() == TradingMode.POST_ONLY)
            && request.kind() != Kind.LIMIT) {
            return "LIMIT_ORDER_REQUIRED";
        }
        if (instrument.mode() == TradingMode.POST_ONLY && !request.postOnly()) {
            return "POST_ONLY_REQUIRED";
        }
        if (request.quantity().signum() <= 0
            || request.quantity().remainder(instrument.sizeIncrement()).signum() != 0) {
            return "INVALID_SIZE_INCREMENT";
        }
        if (request.kind() != Kind.MARKET) {
            if (request.price() == null || request.price().signum() <= 0
                || request.price().remainder(instrument.priceIncrement()).signum() != 0) {
                return "INVALID_PRICE_INCREMENT";
            }
            if (request.price().multiply(request.quantity())
                .compareTo(instrument.minimumNotional()) < 0) {
                return "MINIMUM_NOTIONAL";
            }
        }
        if (request.timeInForce() == TimeInForce.GTD
            && (request.expireAt() == null || !request.expireAt().isAfter(now))) {
            return "INVALID_EXPIRE_TIME";
        }
        if (request.postOnly() && bestOppositePrice != null
            && crosses(request.side(), request.price(), bestOppositePrice)) {
            return "POST_ONLY_WOULD_TAKE";
        }
        return null;
    }

    /** 判断限价是否会立即吃到对手方最优价。 */
    private boolean crosses(Side side, BigDecimal price, BigDecimal bestOppositePrice) {
        return side == Side.BUY
            ? price.compareTo(bestOppositePrice) >= 0
            : price.compareTo(bestOppositePrice) <= 0;
    }

    /** 检查数量必须为正数。 */
    private void requirePositive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + "必须大于零");
        }
    }

    /** 版本化交易对配置。 */
    public record InstrumentConfig(String symbol, long version, TradingMode mode,
                                   BigDecimal priceIncrement, BigDecimal sizeIncrement,
                                   BigDecimal minimumNotional) {
        /** 检查配置完整性。 */
        public InstrumentConfig {
            Objects.requireNonNull(symbol, "symbol");
            Objects.requireNonNull(mode, "mode");
            if (version < 1L) {
                throw new IllegalArgumentException("配置版本必须大于零");
            }
            requirePositiveStatic(priceIncrement, "priceIncrement");
            requirePositiveStatic(sizeIncrement, "sizeIncrement");
            requirePositiveStatic(minimumNotional, "minimumNotional");
        }
    }

    /** 订单规则输入。 */
    public record OrderRequest(String clientOrderId, String symbol, Side side, Kind kind,
                               TimeInForce timeInForce, BigDecimal price,
                               BigDecimal quantity, boolean postOnly, Instant expireAt) {
        /** 检查不可为空的订单字段。 */
        public OrderRequest {
            Objects.requireNonNull(clientOrderId, "clientOrderId");
            Objects.requireNonNull(symbol, "symbol");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(timeInForce, "timeInForce");
            Objects.requireNonNull(quantity, "quantity");
        }
    }

    /** 订单在当前盘口下的确定性执行计划。 */
    public record ExecutionPlan(String status, BigDecimal executableQuantity,
                                BigDecimal restingQuantity, BigDecimal canceledQuantity,
                                String rejectionReason) {
        /** 创建明确拒绝结果。 */
        private static ExecutionPlan rejected(String reason, BigDecimal quantity) {
            return new ExecutionPlan("REJECTED", BigDecimal.ZERO, BigDecimal.ZERO,
                quantity, reason);
        }
    }

    /** 自成交保护后的双方剩余量。 */
    public record SelfTradeResult(BigDecimal makerRemaining, BigDecimal takerRemaining,
                                  String action) {
    }

    /** 撤单与新单两个动作的组合结果。 */
    public record CancelReplaceResult(String status, boolean cancelSucceeded,
                                      boolean replacementAccepted, String recoveryAction) {
    }

    /** 静态校验记录构造参数。 */
    private static void requirePositiveStatic(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + "必须大于零");
        }
    }
}

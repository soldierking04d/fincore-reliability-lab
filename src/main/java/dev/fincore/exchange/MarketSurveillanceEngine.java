package dev.fincore.exchange;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 自成交、快速大单撤销、分层挂单与最佳执行偏差的规则化监控模型。
 *
 * <p>规则命中只生成调查线索，不能自动认定用户违规或直接修改资金。正式市场监察还需要关联账户、设备、
 * 受益所有人、盘口上下文、人工复核和合规留痕。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.1.0
 */
public final class MarketSurveillanceEngine {
    /** 分层挂撤单信号要求出现的不同价格层数量。 */
    private static final int LAYERING_LEVEL_COUNT = 3;
    /** 已见新单，用于把撤单与原始数量和时间关联。 */
    private final Map<String, OrderEvent> openOrders = new HashMap<>();
    /** 每个受益所有人的新单数量。 */
    private final Map<String, Integer> newCounts = new HashMap<>();
    /** 每个受益所有人的撤单数量。 */
    private final Map<String, Integer> cancelCounts = new HashMap<>();
    /** 已生成的监察信号。 */
    private final List<Signal> signals = new ArrayList<>();
    /** 短窗口内已撤销的不同价格层。 */
    private final Map<String, Set<BigDecimal>> canceledLevels = new HashMap<>();

    /** 订单事件类型。 */
    public enum OrderAction {
        /** 新订单进入订单簿。 */
        NEW,
        /** 未成交剩余量被撤销。 */
        CANCEL
    }

    /** 买卖方向。 */
    public enum Side {
        /** 买入。 */
        BUY,
        /** 卖出。 */
        SELL
    }

    /** 监察信号类型。 */
    public enum SignalType {
        /** 买卖双方属于同一受益所有人。 */
        WASH_TRADE,
        /** 大额订单在极短时间内撤销。 */
        RAPID_LARGE_CANCEL,
        /** 同方向多个价格层快速撤销。 */
        LAYERING_PATTERN,
        /** 撤单占比超过观察阈值。 */
        HIGH_CANCEL_RATIO,
        /** 成交价格相对决策时参考价偏差过大。 */
        EXECUTION_SLIPPAGE
    }

    /**
     * 记录新单或撤单事件，并生成非定罪性质的调查信号。
     *
     * @param event 订单生命周期事件
     * @param largeOrderThreshold 大额订单数量阈值
     * @param rapidWindow 快速撤销时间窗口
     */
    public void onOrder(OrderEvent event, BigDecimal largeOrderThreshold,
                        Duration rapidWindow) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(largeOrderThreshold, "largeOrderThreshold");
        Objects.requireNonNull(rapidWindow, "rapidWindow");
        if (event.action() == OrderAction.NEW) {
            openOrders.put(event.orderId(), event);
            newCounts.merge(event.beneficialOwner(), 1, Integer::sum);
            return;
        }
        cancelCounts.merge(event.beneficialOwner(), 1, Integer::sum);
        OrderEvent original = openOrders.remove(event.orderId());
        if (original == null) {
            signals.add(new Signal(SignalType.HIGH_CANCEL_RATIO, event.beneficialOwner(),
                event.orderId(), "撤单没有对应的本地新单，需关联其他会话或恢复数据"));
            return;
        }
        Duration lifetime = Duration.between(original.occurredAt(), event.occurredAt());
        if (!lifetime.isNegative() && lifetime.compareTo(rapidWindow) <= 0
            && original.quantity().compareTo(largeOrderThreshold) >= 0) {
            signals.add(new Signal(SignalType.RAPID_LARGE_CANCEL,
                event.beneficialOwner(), event.orderId(),
                "大额订单在" + lifetime.toMillis() + "毫秒内撤销"));
        }
        String layerKey = event.beneficialOwner() + '|' + event.side();
        Set<BigDecimal> levels = canceledLevels.computeIfAbsent(layerKey,
            ignored -> new HashSet<>());
        // BigDecimal 的 equals 会比较小数位，100.0 与 100.00 在业务上应视为同一价位。
        levels.add(event.price().stripTrailingZeros());
        if (levels.size() == LAYERING_LEVEL_COUNT) {
            signals.add(new Signal(SignalType.LAYERING_PATTERN,
                event.beneficialOwner(), event.orderId(), "同方向三个不同价格层快速撤销"));
        }
    }

    /**
     * 记录成交并检查同一受益所有人对敲。
     *
     * @param trade 成交事件
     */
    public void onTrade(TradeEvent trade) {
        Objects.requireNonNull(trade, "trade");
        if (trade.buyerBeneficialOwner().equals(trade.sellerBeneficialOwner())) {
            signals.add(new Signal(SignalType.WASH_TRADE,
                trade.buyerBeneficialOwner(), trade.tradeId(),
                "买卖双方归属于同一受益所有人"));
        }
    }

    /**
     * 在一个观察周期结束时检查撤单率。
     *
     * @param minimumOrders 最低样本量
     * @param threshold 最大允许撤单率
     */
    public void closeWindow(int minimumOrders, BigDecimal threshold) {
        if (minimumOrders < 1 || threshold.signum() < 0
            || threshold.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("撤单率观察参数不合法");
        }
        for (Map.Entry<String, Integer> entry : newCounts.entrySet()) {
            int orders = entry.getValue();
            int cancellations = cancelCounts.getOrDefault(entry.getKey(), 0);
            if (orders < minimumOrders) {
                continue;
            }
            BigDecimal ratio = BigDecimal.valueOf(cancellations)
                .divide(BigDecimal.valueOf(orders), 8, RoundingMode.HALF_EVEN);
            if (ratio.compareTo(threshold) > 0) {
                signals.add(new Signal(SignalType.HIGH_CANCEL_RATIO, entry.getKey(),
                    "window", "撤单率=" + ratio.toPlainString()));
            }
        }
    }

    /**
     * 检查成交相对决策时参考价的方向性滑点。
     *
     * @param side 用户成交方向
     * @param referencePrice 决策时参考价
     * @param executionPrice 实际成交价
     * @param maximumAdverseBps 最大允许不利滑点基点
     * @param owner 受益所有人
     * @param tradeId 成交编号
     * @return 方向性滑点基点；正数代表对用户不利
     */
    public BigDecimal checkExecution(Side side, BigDecimal referencePrice,
                                     BigDecimal executionPrice,
                                     BigDecimal maximumAdverseBps,
                                     String owner, String tradeId) {
        requirePositive(referencePrice, "referencePrice");
        requirePositive(executionPrice, "executionPrice");
        Objects.requireNonNull(maximumAdverseBps, "maximumAdverseBps");
        BigDecimal difference = side == Side.BUY
            ? executionPrice.subtract(referencePrice)
            : referencePrice.subtract(executionPrice);
        BigDecimal basisPoints = difference.multiply(new BigDecimal("10000"))
            .divide(referencePrice, 8, RoundingMode.HALF_EVEN);
        if (basisPoints.compareTo(maximumAdverseBps) > 0) {
            signals.add(new Signal(SignalType.EXECUTION_SLIPPAGE, owner, tradeId,
                "不利滑点=" + basisPoints.toPlainString() + "bps"));
        }
        return basisPoints;
    }

    /** @return 按生成顺序返回不可修改的监察信号 */
    public List<Signal> signals() {
        return List.copyOf(signals);
    }

    /** @return 按类型汇总当前监察信号数量 */
    public Map<SignalType, Long> summary() {
        Map<SignalType, Long> summary = new LinkedHashMap<>();
        for (Signal signal : signals) {
            summary.merge(signal.type(), 1L, Long::sum);
        }
        return summary;
    }

    /** 校验正数金额或数量。 */
    private static void requirePositive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + "必须大于零");
        }
    }

    /** 订单生命周期事件。 */
    public record OrderEvent(String orderId, String beneficialOwner, Side side,
                             BigDecimal price, BigDecimal quantity,
                             OrderAction action, Instant occurredAt) {
        /** 检查必要字段和正数数量。 */
        public OrderEvent {
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(beneficialOwner, "beneficialOwner");
            Objects.requireNonNull(side, "side");
            requirePositive(price, "price");
            requirePositive(quantity, "quantity");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /** 成交监察事件。 */
    public record TradeEvent(String tradeId, String buyerBeneficialOwner,
                             String sellerBeneficialOwner, BigDecimal price,
                             BigDecimal quantity, Instant occurredAt) {
        /** 检查必要字段和正数金额。 */
        public TradeEvent {
            Objects.requireNonNull(tradeId, "tradeId");
            Objects.requireNonNull(buyerBeneficialOwner, "buyerBeneficialOwner");
            Objects.requireNonNull(sellerBeneficialOwner, "sellerBeneficialOwner");
            requirePositive(price, "price");
            requirePositive(quantity, "quantity");
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /** 需要进一步调查的市场监察信号。 */
    public record Signal(SignalType type, String beneficialOwner,
                         String referenceId, String reason) {
    }
}

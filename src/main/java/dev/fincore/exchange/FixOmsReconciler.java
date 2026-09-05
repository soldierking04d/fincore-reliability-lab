package dev.fincore.exchange;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * FIX会话序号、未知订单结果和Drop Copy回报对账模型。
 *
 * <p>该模型把传输序号、请求身份和成交回报身份分开：FIX序号用于发现会话缺口，客户端订单号用于定位
 * 业务意图，执行编号用于防止成交回报重复。任何网络超时都先进入 {@link OrderState#UNKNOWN}，只有查询
 * 或权威执行报告才能把它推进到明确状态。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.1.0
 */
public final class FixOmsReconciler {
    /** 下一个应接收的FIX入站序号。 */
    private long expectedSequence = 1L;
    /** 已处理执行编号，防止Drop Copy和下单会话重复记成交。 */
    private final Set<String> executionIds = new HashSet<>();
    /** 客户端订单号对应的OMS状态。 */
    private final Map<String, OrderSnapshot> orders = new LinkedHashMap<>();

    /** FIX序号处理结果。 */
    public enum SequenceOutcome {
        /** 序号连续，可以处理业务消息。 */
        ACCEPTED,
        /** 重发的已处理消息，忽略业务效果。 */
        POSSIBLE_DUPLICATE_IGNORED,
        /** 发现缺口，必须先请求补发。 */
        GAP_DETECTED,
        /** 序号倒退但未携带重复标记，拒绝会话消息。 */
        INVALID_REPLAY
    }

    /** OMS订单状态。 */
    public enum OrderState {
        /** 已发出但尚未收到权威确认。 */
        PENDING_ACK,
        /** 网络结果不明确，禁止盲目换订单号重发。 */
        UNKNOWN,
        /** 已被交易所接收。 */
        OPEN,
        /** 部分成交。 */
        PARTIALLY_FILLED,
        /** 全部成交。 */
        FILLED,
        /** 已取消。 */
        CANCELED,
        /** 已拒绝。 */
        REJECTED,
        /** 等待取消确认。 */
        PENDING_CANCEL
    }

    /** 回报来源。 */
    public enum ReportSource {
        /** 下单会话实时回报。 */
        ORDER_ENTRY,
        /** 独立Drop Copy会话。 */
        DROP_COPY,
        /** 主动查询得到的权威状态。 */
        STATUS_QUERY
    }

    /**
     * 校验一条FIX消息的入站序号。
     *
     * @param sequence 消息序号
     * @param possibleDuplicate 是否携带PossDup标志
     * @return 是否接受、忽略或要求补发
     */
    public SequenceOutcome acceptSequence(long sequence, boolean possibleDuplicate) {
        if (sequence == expectedSequence) {
            expectedSequence++;
            return SequenceOutcome.ACCEPTED;
        }
        if (sequence > expectedSequence) {
            return SequenceOutcome.GAP_DETECTED;
        }
        return possibleDuplicate
            ? SequenceOutcome.POSSIBLE_DUPLICATE_IGNORED
            : SequenceOutcome.INVALID_REPLAY;
    }

    /**
     * 在发送订单前登记业务意图。
     *
     * @param clientOrderId 客户端订单号
     * @param quantity 原始数量
     * @return 新建的OMS快照
     */
    public OrderSnapshot submit(String clientOrderId, BigDecimal quantity) {
        Objects.requireNonNull(clientOrderId, "clientOrderId");
        requireNonNegative(quantity, "quantity");
        if (quantity.signum() == 0) {
            throw new IllegalArgumentException("订单数量必须大于零");
        }
        OrderSnapshot created = new OrderSnapshot(clientOrderId, OrderState.PENDING_ACK,
            BigDecimal.ZERO, quantity, null, null);
        OrderSnapshot existing = orders.putIfAbsent(clientOrderId, created);
        if (existing != null && existing.originalQuantity().compareTo(quantity) != 0) {
            throw new IllegalArgumentException("相同客户端订单号的数量发生冲突");
        }
        return existing == null ? created : existing;
    }

    /**
     * 把通信超时记录为未知结果。
     *
     * @param clientOrderId 客户端订单号
     * @return 更新后的状态
     */
    public OrderSnapshot markUnknown(String clientOrderId) {
        OrderSnapshot current = requireOrder(clientOrderId);
        if (current.terminal()) {
            return current;
        }
        OrderSnapshot changed = current.withState(OrderState.UNKNOWN, current.exchangeOrderId(),
            "等待状态查询或Drop Copy回报");
        orders.put(clientOrderId, changed);
        return changed;
    }

    /**
     * 应用执行报告；执行编号重复时只返回原快照，不重复累计成交量。
     *
     * @param report 交易所执行报告
     * @return 应用后的状态及是否为重复执行报告
     */
    public ApplyResult apply(ExecutionReport report) {
        Objects.requireNonNull(report, "report");
        OrderSnapshot current = requireOrder(report.clientOrderId());
        if (!executionIds.add(report.executionId())) {
            return new ApplyResult(current, true);
        }
        requireNonNegative(report.cumulativeQuantity(), "cumulativeQuantity");
        requireNonNegative(report.leavesQuantity(), "leavesQuantity");
        if (report.cumulativeQuantity().add(report.leavesQuantity())
            .compareTo(current.originalQuantity()) > 0) {
            throw new IllegalArgumentException("累计成交量与剩余量超过原始数量");
        }
        if (report.cumulativeQuantity().compareTo(current.cumulativeQuantity()) < 0) {
            throw new IllegalArgumentException("累计成交量不允许倒退");
        }
        if (current.terminal() && current.state() != report.state()) {
            throw new IllegalStateException("终态订单收到冲突回报");
        }
        OrderSnapshot changed = new OrderSnapshot(report.clientOrderId(), report.state(),
            report.cumulativeQuantity(), report.leavesQuantity(), report.exchangeOrderId(),
            report.source().name());
        orders.put(report.clientOrderId(), changed);
        return new ApplyResult(changed, false);
    }

    /**
     * 断线时生成需要发送的撤单列表，但不提前把订单标为已取消。
     *
     * @param cancelOnDisconnect 是否启用断线撤单策略
     * @return 需要等待交易所确认的客户端订单号
     */
    public List<String> onDisconnect(boolean cancelOnDisconnect) {
        if (!cancelOnDisconnect) {
            return List.of();
        }
        List<String> pending = new ArrayList<>();
        for (Map.Entry<String, OrderSnapshot> entry : orders.entrySet()) {
            OrderSnapshot order = entry.getValue();
            if (!order.terminal()) {
                OrderSnapshot changed = order.withState(OrderState.PENDING_CANCEL,
                    order.exchangeOrderId(), "断线撤单已请求，等待执行报告");
                entry.setValue(changed);
                pending.add(entry.getKey());
            }
        }
        return List.copyOf(pending);
    }

    /**
     * 比较OMS与一批Drop Copy权威回报，返回需要修复的订单号。
     *
     * @param reports 独立回报会话中的最新订单快照
     * @return 状态或成交量不一致的订单号及原因
     */
    public Map<String, String> differences(List<ExecutionReport> reports) {
        Objects.requireNonNull(reports, "reports");
        // Java 19 的工厂按预期元素数计算底层容量，避免批量对账装载时发生一次无意义扩容。
        Map<String, ExecutionReport> latest = HashMap.newHashMap(reports.size());
        for (ExecutionReport report : reports) {
            latest.put(report.clientOrderId(), report);
        }
        Map<String, String> differences = new LinkedHashMap<>();
        for (Map.Entry<String, OrderSnapshot> entry : orders.entrySet()) {
            ExecutionReport authoritative = latest.get(entry.getKey());
            if (authoritative == null) {
                if (entry.getValue().state() == OrderState.UNKNOWN
                    || entry.getValue().state() == OrderState.PENDING_ACK
                    || entry.getValue().state() == OrderState.PENDING_CANCEL) {
                    differences.put(entry.getKey(), "MISSING_AUTHORITY");
                }
                continue;
            }
            OrderSnapshot local = entry.getValue();
            if (local.state() != authoritative.state()
                || local.cumulativeQuantity().compareTo(authoritative.cumulativeQuantity()) != 0
                || local.leavesQuantity().compareTo(authoritative.leavesQuantity()) != 0) {
                differences.put(entry.getKey(), "STATE_OR_QUANTITY_MISMATCH");
            }
        }
        return differences;
    }

    /**
     * 获取当前订单快照。
     *
     * @param clientOrderId 客户端订单号
     * @return 当前OMS快照
     */
    public OrderSnapshot order(String clientOrderId) {
        return requireOrder(clientOrderId);
    }

    /** @return 下一个应接收的FIX序号 */
    public long expectedSequence() {
        return expectedSequence;
    }

    /** 查询订单并在缺失时明确失败。 */
    private OrderSnapshot requireOrder(String clientOrderId) {
        OrderSnapshot snapshot = orders.get(clientOrderId);
        if (snapshot == null) {
            throw new IllegalArgumentException("未知客户端订单号：" + clientOrderId);
        }
        return snapshot;
    }

    /** 校验非负数量。 */
    private static void requireNonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + "不能为负数");
        }
    }

    /** OMS订单快照。 */
    public record OrderSnapshot(String clientOrderId, OrderState state,
                                BigDecimal cumulativeQuantity, BigDecimal leavesQuantity,
                                String exchangeOrderId, String lastAuthority) {
        /** @return 是否已经进入不可回退的终态 */
        public boolean terminal() {
            return state == OrderState.FILLED || state == OrderState.CANCELED
                || state == OrderState.REJECTED;
        }

        /** @return 原始订单数量 */
        public BigDecimal originalQuantity() {
            return cumulativeQuantity.add(leavesQuantity);
        }

        /** 创建只变更状态和权威来源的新快照。 */
        private OrderSnapshot withState(OrderState newState, String newExchangeOrderId,
                                        String authority) {
            return new OrderSnapshot(clientOrderId, newState, cumulativeQuantity,
                leavesQuantity, newExchangeOrderId, authority);
        }
    }

    /** FIX或查询接口返回的执行报告。 */
    public record ExecutionReport(String executionId, String clientOrderId,
                                  String exchangeOrderId, OrderState state,
                                  BigDecimal cumulativeQuantity,
                                  BigDecimal leavesQuantity, ReportSource source) {
        /** 检查必要字段。 */
        public ExecutionReport {
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(clientOrderId, "clientOrderId");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(cumulativeQuantity, "cumulativeQuantity");
            Objects.requireNonNull(leavesQuantity, "leavesQuantity");
            Objects.requireNonNull(source, "source");
        }
    }

    /** 执行报告应用结果。 */
    public record ApplyResult(OrderSnapshot order, boolean duplicateExecution) {
    }
}

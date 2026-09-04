package dev.fincore.exchange;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 行情序列、快照恢复、多源择优与慢消费者保护的确定性模型。
 *
 * <p>模型只保留每个来源的最新报价，不把行情缓存当作金融账本。序列出现缺口后，来源会被标记为
 * {@link SourceHealth#STALE}，必须使用快照和排队增量恢复后才能重新参与参考价选择。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.1.0
 */
public final class MarketDataReliability {
    /** 每个来源的最后序号和健康状态。 */
    private final Map<String, SourceState> sources = new LinkedHashMap<>();

    /** 行情增量处理结果。 */
    public enum DeltaOutcome {
        /** 增量连续并已应用。 */
        APPLIED,
        /** 消息已经处理或比当前状态更旧。 */
        DUPLICATE,
        /** 序号不连续，来源需要通过快照恢复。 */
        GAP,
        /** 买卖价倒挂或字段不合法。 */
        INVALID
    }

    /** 行情来源健康状态。 */
    public enum SourceHealth {
        /** 可以参与参考行情选择。 */
        HEALTHY,
        /** 发生序号缺口，当前报价不可继续使用。 */
        STALE
    }

    /**
     * 接收一条行情增量。
     *
     * @param quote 带来源、序号和前序号的最优买卖报价
     * @return 应用、重复、缺口或非法结果
     */
    public DeltaOutcome onDelta(SourceQuote quote) {
        Objects.requireNonNull(quote, "quote");
        if (!quote.isValid()) {
            return DeltaOutcome.INVALID;
        }
        SourceState state = sources.get(quote.source());
        if (state == null) {
            if (quote.previousSequence() != 0L) {
                sources.put(quote.source(), new SourceState(null, SourceHealth.STALE));
                return DeltaOutcome.GAP;
            }
            sources.put(quote.source(), new SourceState(quote, SourceHealth.HEALTHY));
            return DeltaOutcome.APPLIED;
        }
        if (state.quote() != null && quote.sequence() <= state.quote().sequence()) {
            return DeltaOutcome.DUPLICATE;
        }
        if (state.quote() == null || quote.previousSequence() != state.quote().sequence()) {
            sources.put(quote.source(), new SourceState(state.quote(), SourceHealth.STALE));
            return DeltaOutcome.GAP;
        }
        sources.put(quote.source(), new SourceState(quote, SourceHealth.HEALTHY));
        return DeltaOutcome.APPLIED;
    }

    /**
     * 使用权威快照和已经排队的增量恢复单个来源。
     *
     * <p>只回放序号大于快照的消息；任一回放缺口都会使恢复失败，避免把不完整订单簿重新标为健康。</p>
     *
     * @param snapshot 来源快照
     * @param queued 在获取快照期间排队的增量
     * @return 恢复后的来源健康状态
     */
    public SourceHealth recover(SourceQuote snapshot, List<SourceQuote> queued) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(queued, "queued");
        if (!snapshot.isValid()) {
            throw new IllegalArgumentException("行情快照字段不合法");
        }
        sources.put(snapshot.source(), new SourceState(snapshot, SourceHealth.HEALTHY));
        List<SourceQuote> ordered = new ArrayList<>(queued);
        ordered.sort(Comparator.comparingLong(SourceQuote::sequence));
        for (SourceQuote delta : ordered) {
            if (!snapshot.source().equals(delta.source()) || delta.sequence() <= snapshot.sequence()) {
                continue;
            }
            if (onDelta(delta) != DeltaOutcome.APPLIED) {
                SourceState current = sources.get(snapshot.source());
                sources.put(snapshot.source(), new SourceState(current.quote(), SourceHealth.STALE));
                break;
            }
        }
        return sources.get(snapshot.source()).health();
    }

    /**
     * 从健康且未过期的来源中选择参考报价。
     *
     * <p>先计算候选中间价的中位数，再剔除偏离超过阈值的来源；最终选择最新报价。返回的是单一来源
     * 的完整买卖价，避免把不同来源的最高买价和最低卖价拼成不存在或倒挂的合成盘口。</p>
     *
     * @param now 当前时间
     * @param maxAge 最大允许行情年龄
     * @param maxDeviationBps 相对中位价最大偏离基点
     * @return 可用的参考报价
     */
    public ConsolidatedQuote selectReference(Instant now, Duration maxAge,
                                             BigDecimal maxDeviationBps) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(maxAge, "maxAge");
        Objects.requireNonNull(maxDeviationBps, "maxDeviationBps");
        List<SourceQuote> candidates = sources.values().stream()
            .filter(state -> state.health() == SourceHealth.HEALTHY)
            .map(SourceState::quote)
            .filter(Objects::nonNull)
            .filter(quote -> !quote.observedAt().plus(maxAge).isBefore(now))
            .sorted(Comparator.comparing(SourceQuote::mid))
            .toList();
        if (candidates.isEmpty()) {
            throw new IllegalStateException("没有健康且未过期的行情来源");
        }
        BigDecimal median = candidates.get(candidates.size() / 2).mid();
        BigDecimal divisor = new BigDecimal("10000");
        List<SourceQuote> accepted = candidates.stream()
            .filter(quote -> quote.mid().subtract(median).abs()
                .multiply(divisor)
                .divide(median, 8, RoundingMode.HALF_EVEN)
                .compareTo(maxDeviationBps) <= 0)
            .toList();
        SourceQuote selected = accepted.stream()
            .max(Comparator.comparing(SourceQuote::observedAt)
                .thenComparingLong(SourceQuote::sequence))
            .orElseThrow(() -> new IllegalStateException("全部行情来源均偏离中位价"));
        return new ConsolidatedQuote(selected.source(), selected.bid(), selected.ask(),
            selected.mid(), selected.sequence(), selected.observedAt(), accepted.size());
    }

    /**
     * 查询指定来源当前健康状态。
     *
     * @param source 行情来源
     * @return 健康状态；未知来源按过期处理
     */
    public SourceHealth health(String source) {
        SourceState state = sources.get(source);
        return state == null ? SourceHealth.STALE : state.health();
    }

    /** 单一来源的最优买卖报价。 */
    public record SourceQuote(String source, String symbol, long sequence,
                              long previousSequence, BigDecimal bid, BigDecimal ask,
                              Instant observedAt) {
        /**
         * 创建后立即检查必填字段，金额仍由业务方法判断正数和买卖关系。
         */
        public SourceQuote {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(symbol, "symbol");
            Objects.requireNonNull(bid, "bid");
            Objects.requireNonNull(ask, "ask");
            Objects.requireNonNull(observedAt, "observedAt");
        }

        /** @return 买卖价中间值 */
        public BigDecimal mid() {
            return bid.add(ask).divide(new BigDecimal("2"), 8, RoundingMode.HALF_EVEN);
        }

        /** @return 字段和价格关系是否可以作为行情事实 */
        public boolean isValid() {
            return !source.isBlank() && !symbol.isBlank() && sequence >= 0L
                && previousSequence >= 0L && bid.signum() > 0 && ask.signum() > 0
                && bid.compareTo(ask) < 0;
        }
    }

    /** 最终被盘前风控消费的参考报价。 */
    public record ConsolidatedQuote(String source, BigDecimal bid, BigDecimal ask,
                                    BigDecimal mid, long sequence, Instant observedAt,
                                    int acceptedSources) {
    }

    /** 单来源内部状态。 */
    private record SourceState(SourceQuote quote, SourceHealth health) {
    }

    /**
     * 面向WebSocket慢消费者的有界最新值缓冲。
     *
     * <p>同一交易对的新行情覆盖缓冲中的旧行情；不同交易对超过容量时明确丢弃并计数。该结构只适合
     * 可合并的行情展示，不得用于订单、成交或账务事件。</p>
     */
    public static final class LatestValueBuffer {
        /** 最大不同交易对数量。 */
        private final int capacity;
        /** 按交易对保存的最新报价。 */
        private final LinkedHashMap<String, ConsolidatedQuote> latest = new LinkedHashMap<>();
        /** 因容量不足丢弃的新交易对事件数。 */
        private long dropped;
        /** 被更新报价覆盖的旧事件数。 */
        private long coalesced;

        /**
         * 创建有界缓冲。
         *
         * @param capacity 最大不同交易对数量
         */
        public LatestValueBuffer(int capacity) {
            if (capacity < 1) {
                throw new IllegalArgumentException("缓冲容量必须大于零");
            }
            this.capacity = capacity;
        }

        /**
         * 放入一条最新行情。
         *
         * @param symbol 交易对
         * @param quote 最新行情
         * @return 是否已接收
         */
        public boolean offer(String symbol, ConsolidatedQuote quote) {
            Objects.requireNonNull(symbol, "symbol");
            Objects.requireNonNull(quote, "quote");
            if (latest.containsKey(symbol)) {
                latest.put(symbol, quote);
                coalesced++;
                return true;
            }
            if (latest.size() >= capacity) {
                dropped++;
                return false;
            }
            latest.put(symbol, quote);
            return true;
        }

        /** @return 当前不同交易对数量 */
        public int size() {
            return latest.size();
        }

        /** @return 被覆盖的旧行情数量 */
        public long coalesced() {
            return coalesced;
        }

        /** @return 容量不足而明确丢弃的数量 */
        public long dropped() {
            return dropped;
        }
    }
}

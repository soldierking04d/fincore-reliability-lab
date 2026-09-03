package dev.fincore.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 成交交割通知只携带幂等键和成交编号；金额、方向和账户必须从数据库事实读取。
 *
 * @param messageId 消息幂等键
 * @param tradeId 已提交的权威成交编号
 * @author FinCore Reliability Lab
 * @since 1.3.0
 */
public record SpotDeliveryCommand(String messageId, UUID tradeId) {
    /** 拒绝缺失或过长的消息键。 */
    public SpotDeliveryCommand {
        Objects.requireNonNull(tradeId, "tradeId");
        if (messageId == null || messageId.isBlank() || messageId.length() > 100) {
            throw new IllegalArgumentException("invalid delivery messageId");
        }
    }
}

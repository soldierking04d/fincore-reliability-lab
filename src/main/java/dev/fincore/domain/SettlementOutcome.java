package dev.fincore.domain;

/**
 * 结算处理结果。
 *
 * @param businessKey 结算业务键
 * @param status 最终或当前结算状态
 * @param duplicate 是否由重复消息或重复业务请求返回
 * @param detail 处理说明
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
public record SettlementOutcome(String businessKey, SettlementStatus status, boolean duplicate, String detail) {
    /**
     * 创建已经成功处理过的幂等返回值。
     *
     * @param key 结算业务键
     * @return 标记为重复但业务结果成功的结算结果
     */
    public static SettlementOutcome duplicate(String key) {
        return new SettlementOutcome(key, SettlementStatus.SUCCESS, true, "message already processed");
    }
}

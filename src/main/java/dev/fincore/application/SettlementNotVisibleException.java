package dev.fincore.application;

/**
 * 查询时尚无已提交的结算结果；命令被接收与资金完成结算是两个独立阶段。
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-21
 */
public class SettlementNotVisibleException extends RuntimeException {
    /** 结果尚不可见的业务键。 */
    private final String businessKey;

    /** @param businessKey 尚无已提交结果的业务键 */
    public SettlementNotVisibleException(String businessKey) {
        super("settlement result is not visible; it may still be processing");
        this.businessKey = businessKey;
    }

    /** @return 本次查询的业务键 */
    public String businessKey() {
        return businessKey;
    }
}

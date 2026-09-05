package dev.fincore.application;

/**
 * 表示请求参数本身合法，但与当前权威业务状态冲突。
 *
 * <p>该异常只用于调用方能够理解并停止盲目重试的确定性冲突，例如撤销已成交订单或反向补偿
 * 非成功结算。数据库竞争、CAS 失败、序号分配失败等内部一致性问题不得使用本异常，必须继续
 * 作为服务端故障上报和告警。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-05
 */
public class BusinessConflictException extends RuntimeException {
    /**
     * 创建确定性业务冲突。
     *
     * @param message 可安全返回调用方的稳定业务说明
     */
    public BusinessConflictException(String message) {
        super(message);
    }
}

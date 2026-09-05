package dev.fincore.application;

/**
 * 数据面围栏已经失效时抛出的明确异常。
 *
 * <p>异常类型而不是消息文本承担机器判断职责。调用方可以据此丢弃本地 Lease 缓存并重新获取
 * 所有权；异常消息只用于人员排障，修改文案不会改变控制流。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.4.0
 */
public class FenceRejectedException extends IllegalStateException {
    /**
     * 创建围栏拒绝异常。
     *
     * @param message 不包含密钥或账户隐私的诊断信息
     */
    public FenceRejectedException(String message) {
        super(message);
    }
}

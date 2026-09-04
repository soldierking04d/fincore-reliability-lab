package dev.fincore.exchange;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 多链充值确认、重组、提现未知结果、Nonce围栏和交易替换的状态机实验。
 *
 * <p>模型使用整数最小单位表示链上金额，不使用浮点数。它只模拟节点观察结果，不连接测试网、不保存私钥、
 * 不执行真实签名；生产实现仍需多RPC、节点索引器、HSM/MPC、审批、链上链下账本和安全评审。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.1.0
 */
public final class DigitalAssetWorkflow {
    /** 不同链的核心交易模型。 */
    public enum ChainFamily {
        /** Ethereum、Tron等账户与Nonce模型。 */
        ACCOUNT_NONCE,
        /** Solana等区块哈希和账户锁模型。 */
        RECENT_BLOCKHASH,
        /** Bitcoin等UTXO输入输出模型。 */
        UTXO
    }

    /** 充值状态。 */
    public enum DepositStatus {
        /** 已发现但尚未达到确认策略。 */
        CONFIRMING,
        /** 已达到确认策略并产生唯一入账意图。 */
        CREDITED,
        /** 未入账事件已因重组失效。 */
        REORGED,
        /** 已入账事件发生重组，需要冻结和授权调整。 */
        ADJUSTMENT_REQUIRED
    }

    /** 提现状态。 */
    public enum WithdrawalStatus {
        /** 已接收唯一业务请求。 */
        REQUESTED,
        /** 已批准并等待签名。 */
        APPROVED,
        /** 签名或广播调用超时，外部结果未知。 */
        UNKNOWN,
        /** 已广播并等待链上确认。 */
        CONFIRMING,
        /** 已被同Nonce或同输入的新交易替换。 */
        REPLACED,
        /** 规范链已经确认。 */
        COMPLETED
    }

    /** 充值事件唯一键对应的状态。 */
    private final Map<String, Deposit> deposits = new LinkedHashMap<>();
    /** 提现请求编号对应的状态。 */
    private final Map<String, Withdrawal> withdrawals = new LinkedHashMap<>();
    /** 账户模型地址的下一个本地Nonce。 */
    private final Map<String, Long> nextNonces = new HashMap<>();
    /** Nonce协调者当前Epoch。 */
    private long nonceEpoch = 1L;

    /**
     * 发现充值事件；相同业务键和相同载荷只返回原状态。
     *
     * @param eventKey EVM使用chainId+txHash+logIndex，UTXO使用txid+vout
     * @param chain 链标识
     * @param asset 资产标识
     * @param amountSmallestUnit 整数最小单位金额
     * @param blockHeight 所在区块高度
     * @param blockHash 所在区块哈希
     * @return 当前充值状态
     */
    public Deposit detectDeposit(String eventKey, String chain, String asset,
                                 BigInteger amountSmallestUnit, long blockHeight,
                                 String blockHash) {
        requireText(eventKey, "eventKey");
        requireText(chain, "chain");
        requireText(asset, "asset");
        requirePositive(amountSmallestUnit, "amountSmallestUnit");
        requireText(blockHash, "blockHash");
        Deposit candidate = new Deposit(eventKey, chain, asset, amountSmallestUnit,
            blockHeight, blockHash, DepositStatus.CONFIRMING, false);
        Deposit existing = deposits.putIfAbsent(eventKey, candidate);
        if (existing != null && !existing.sameEvent(candidate)) {
            throw new IllegalArgumentException("相同充值事件键的链上载荷发生冲突");
        }
        return existing == null ? candidate : existing;
    }

    /**
     * 按规范链确认数推进充值并生成唯一入账意图。
     *
     * @param eventKey 充值事件唯一键
     * @param canonicalBlockHash 当前规范链区块哈希
     * @param confirmations 当前确认数
     * @param requiredConfirmations 资产策略要求的确认数
     * @return 推进后的充值状态
     */
    public Deposit confirmDeposit(String eventKey, String canonicalBlockHash,
                                  long confirmations, long requiredConfirmations) {
        Deposit current = requireDeposit(eventKey);
        if (!current.blockHash().equals(canonicalBlockHash)) {
            return markReorg(eventKey);
        }
        if (confirmations < requiredConfirmations || requiredConfirmations < 1L) {
            return current;
        }
        if (current.status() == DepositStatus.CREDITED) {
            return current;
        }
        Deposit credited = current.withStatus(DepositStatus.CREDITED, true);
        deposits.put(eventKey, credited);
        return credited;
    }

    /**
     * 标记链重组；已经入账的充值只能进入授权调整流程，不能删除历史入账。
     *
     * @param eventKey 充值事件唯一键
     * @return 重组后的状态
     */
    public Deposit markReorg(String eventKey) {
        Deposit current = requireDeposit(eventKey);
        DepositStatus status = current.creditPosted()
            ? DepositStatus.ADJUSTMENT_REQUIRED : DepositStatus.REORGED;
        Deposit changed = current.withStatus(status, current.creditPosted());
        deposits.put(eventKey, changed);
        return changed;
    }

    /**
     * 创建提现请求；相同请求号不能变更资产、金额或目标地址。
     *
     * @param requestId 提现业务编号
     * @param chain 链标识
     * @param asset 资产标识
     * @param amountSmallestUnit 整数最小单位金额
     * @param destination 目标地址
     * @return 提现状态
     */
    public Withdrawal requestWithdrawal(String requestId, String chain, String asset,
                                        BigInteger amountSmallestUnit,
                                        String destination) {
        requireText(requestId, "requestId");
        requireText(chain, "chain");
        requireText(asset, "asset");
        requirePositive(amountSmallestUnit, "amountSmallestUnit");
        requireText(destination, "destination");
        Withdrawal candidate = new Withdrawal(requestId, chain, asset,
            amountSmallestUnit, destination, WithdrawalStatus.REQUESTED,
            null, List.of(), null);
        Withdrawal existing = withdrawals.putIfAbsent(requestId, candidate);
        if (existing != null && !existing.sameIntent(candidate)) {
            throw new IllegalArgumentException("相同提现请求号的业务载荷发生冲突");
        }
        return existing == null ? candidate : existing;
    }

    /**
     * 批准提现并在账户模型下分配有Epoch保护的Nonce。
     *
     * @param requestId 提现请求号
     * @param chainAddress 链与发送地址组合键
     * @param ownerEpoch 协调者Epoch
     * @return 带Nonce的已批准提现
     */
    public Withdrawal approveWithNonce(String requestId, String chainAddress,
                                       long ownerEpoch) {
        if (ownerEpoch != nonceEpoch) {
            throw new IllegalStateException("旧Epoch不能继续分配Nonce");
        }
        Withdrawal current = requireWithdrawal(requestId);
        if (current.nonce() != null) {
            return current;
        }
        long nonce = nextNonces.getOrDefault(chainAddress, 0L);
        nextNonces.put(chainAddress, nonce + 1L);
        Withdrawal changed = current.withState(WithdrawalStatus.APPROVED,
            nonce, current.transactionHashes(), null);
        withdrawals.put(requestId, changed);
        return changed;
    }

    /**
     * 标记签名或广播结果未知，恢复任务必须先按原意图查询。
     *
     * @param requestId 提现请求号
     * @return 未知结果状态
     */
    public Withdrawal markUnknown(String requestId) {
        Withdrawal current = requireWithdrawal(requestId);
        Withdrawal changed = current.withState(WithdrawalStatus.UNKNOWN,
            current.nonce(), current.transactionHashes(), "先查询签名意图和节点");
        withdrawals.put(requestId, changed);
        return changed;
    }

    /**
     * 记录首次广播或手续费替换交易。
     *
     * @param requestId 提现请求号
     * @param transactionHash 新交易哈希
     * @param replacesHash 被替换的旧交易哈希；首次广播传{@code null}
     * @return 确认中状态及完整替换链
     */
    public Withdrawal broadcast(String requestId, String transactionHash,
                                String replacesHash) {
        requireText(transactionHash, "transactionHash");
        Withdrawal current = requireWithdrawal(requestId);
        List<String> hashes = new ArrayList<>(current.transactionHashes());
        if (replacesHash != null && !hashes.contains(replacesHash)) {
            throw new IllegalArgumentException("替换交易没有引用已知交易哈希");
        }
        if (!hashes.contains(transactionHash)) {
            hashes.add(transactionHash);
        }
        WithdrawalStatus status = replacesHash == null
            ? WithdrawalStatus.CONFIRMING : WithdrawalStatus.REPLACED;
        Withdrawal changed = current.withState(status, current.nonce(),
            List.copyOf(hashes), replacesHash);
        withdrawals.put(requestId, changed);
        return changed;
    }

    /**
     * 只有替换链中的一个交易进入规范链时，才能完成原提现业务。
     *
     * @param requestId 提现请求号
     * @param winningHash 最终确认交易哈希
     * @return 完成状态
     */
    public Withdrawal complete(String requestId, String winningHash) {
        Withdrawal current = requireWithdrawal(requestId);
        if (!current.transactionHashes().contains(winningHash)) {
            throw new IllegalArgumentException("确认交易不属于当前提现替换链");
        }
        Withdrawal changed = current.withState(WithdrawalStatus.COMPLETED,
            current.nonce(), current.transactionHashes(), winningHash);
        withdrawals.put(requestId, changed);
        return changed;
    }

    /** @return 新Nonce协调者Epoch */
    public long takeoverNonceCoordinator() {
        return ++nonceEpoch;
    }

    /** 查询充值。 */
    private Deposit requireDeposit(String eventKey) {
        Deposit deposit = deposits.get(eventKey);
        if (deposit == null) {
            throw new IllegalArgumentException("未知充值事件：" + eventKey);
        }
        return deposit;
    }

    /** 查询提现。 */
    private Withdrawal requireWithdrawal(String requestId) {
        Withdrawal withdrawal = withdrawals.get(requestId);
        if (withdrawal == null) {
            throw new IllegalArgumentException("未知提现请求：" + requestId);
        }
        return withdrawal;
    }

    /** 检查文本字段。 */
    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
    }

    /** 检查整数最小单位金额。 */
    private static void requirePositive(BigInteger value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + "必须大于零");
        }
    }

    /** 充值事件状态。 */
    public record Deposit(String eventKey, String chain, String asset,
                          BigInteger amountSmallestUnit, long blockHeight,
                          String blockHash, DepositStatus status,
                          boolean creditPosted) {
        /** 判断两个扫描结果是否指向完全相同的链上事件。 */
        private boolean sameEvent(Deposit other) {
            return chain.equals(other.chain) && asset.equals(other.asset)
                && amountSmallestUnit.equals(other.amountSmallestUnit)
                && blockHeight == other.blockHeight && blockHash.equals(other.blockHash);
        }

        /** 创建只改变状态和入账标记的新快照。 */
        private Deposit withStatus(DepositStatus newStatus, boolean posted) {
            return new Deposit(eventKey, chain, asset, amountSmallestUnit,
                blockHeight, blockHash, newStatus, posted);
        }
    }

    /** 提现意图、Nonce和交易替换链。 */
    public record Withdrawal(String requestId, String chain, String asset,
                             BigInteger amountSmallestUnit, String destination,
                             WithdrawalStatus status, Long nonce,
                             List<String> transactionHashes, String statusDetail) {
        /** 防止调用方修改交易哈希替换链。 */
        public Withdrawal {
            transactionHashes = List.copyOf(transactionHashes);
        }

        /** 判断两个请求是否为同一业务意图。 */
        private boolean sameIntent(Withdrawal other) {
            return chain.equals(other.chain) && asset.equals(other.asset)
                && amountSmallestUnit.equals(other.amountSmallestUnit)
                && destination.equals(other.destination);
        }

        /** 创建只改变执行状态的新快照。 */
        private Withdrawal withState(WithdrawalStatus newStatus, Long newNonce,
                                     List<String> hashes, String detail) {
            return new Withdrawal(requestId, chain, asset, amountSmallestUnit,
                destination, newStatus, newNonce, hashes, detail);
        }
    }
}

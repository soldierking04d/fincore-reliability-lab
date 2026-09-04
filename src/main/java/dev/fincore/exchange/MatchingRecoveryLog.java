package dev.fincore.exchange;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单写者撮合命令日志、快照、确定性重放与Epoch围栏模型。
 *
 * <p>日志是撮合状态的恢复输入，快照只用于缩短恢复时间；恢复时必须验证序号连续并重新计算状态摘要。
 * 本模型只保存订单剩余量，没有实现真实低延迟订单簿、磁盘刷写、复制协议或主备选举。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.1.0
 */
public final class MatchingRecoveryLog {
    /** 当前合法写入Epoch。 */
    private long epoch = 1L;
    /** 下一个命令序号。 */
    private long nextSequence = 1L;
    /** 不可变命令日志。 */
    private final List<Command> journal = new ArrayList<>();
    /** 当前订单剩余量。 */
    private final Map<String, BigDecimal> remainingByOrder = new LinkedHashMap<>();
    /** 已处理业务命令及其载荷指纹。 */
    private final Map<String, String> commandFingerprints = new LinkedHashMap<>();

    /** 撮合命令类型。 */
    public enum CommandType {
        /** 创建一张新订单。 */
        PLACE,
        /** 取消订单剩余量。 */
        CANCEL
    }

    /**
     * 追加并执行一条命令。
     *
     * @param commandId 端到端业务命令编号
     * @param ownerEpoch 写入者持有的Epoch
     * @param type 命令类型
     * @param orderId 订单编号
     * @param quantity PLACE时为原始数量；CANCEL时必须为零
     * @return 追加结果；重复相同命令返回原序号
     */
    public synchronized AppendResult append(String commandId, long ownerEpoch,
                                            CommandType type, String orderId,
                                            BigDecimal quantity) {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(quantity, "quantity");
        if (ownerEpoch != epoch) {
            return new AppendResult("STALE_EPOCH", -1L, checksum(), false);
        }
        String fingerprint = type + "|" + orderId + "|" + quantity.toPlainString();
        String existing = commandFingerprints.get(commandId);
        if (existing != null) {
            if (!existing.equals(fingerprint)) {
                throw new IllegalArgumentException("相同命令编号的载荷发生冲突");
            }
            long sequence = journal.stream()
                .filter(command -> command.commandId().equals(commandId))
                .findFirst().orElseThrow().sequence();
            return new AppendResult("DUPLICATE", sequence, checksum(), true);
        }
        validate(type, orderId, quantity);
        Command command = new Command(nextSequence++, ownerEpoch, commandId,
            type, orderId, quantity);
        apply(remainingByOrder, command);
        journal.add(command);
        commandFingerprints.put(commandId, fingerprint);
        return new AppendResult("APPLIED", command.sequence(), checksum(), false);
    }

    /**
     * 提升Epoch并把旧写入者永久隔离。
     *
     * @return 新Epoch
     */
    public synchronized long takeover() {
        epoch++;
        return epoch;
    }

    /**
     * 在明确序号边界创建订单状态快照。
     *
     * @return 包含状态摘要的不可变快照
     */
    public synchronized Snapshot snapshot() {
        long lastSequence = nextSequence - 1L;
        Map<String, BigDecimal> state = Map.copyOf(remainingByOrder);
        return new Snapshot(lastSequence, epoch, state, checksum(state));
    }

    /**
     * 使用快照和后续命令重放出完整状态。
     *
     * @param snapshot 起始快照
     * @param tail 快照之后的命令日志
     * @return 恢复状态与摘要
     */
    public static RecoveredState recover(Snapshot snapshot, List<Command> tail) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(tail, "tail");
        if (!snapshot.checksum().equals(checksum(snapshot.remainingByOrder()))) {
            throw new IllegalArgumentException("快照摘要不匹配");
        }
        Map<String, BigDecimal> state = new LinkedHashMap<>(snapshot.remainingByOrder());
        List<Command> ordered = tail.stream()
            .filter(command -> command.sequence() > snapshot.lastSequence())
            .sorted(Comparator.comparingLong(Command::sequence))
            .toList();
        long expected = snapshot.lastSequence() + 1L;
        for (Command command : ordered) {
            if (command.sequence() != expected) {
                throw new IllegalStateException("命令日志存在序号缺口：期待" + expected
                    + "，实际" + command.sequence());
            }
            apply(state, command);
            expected++;
        }
        return new RecoveredState(expected - 1L, Map.copyOf(state), checksum(state));
    }

    /** @return 当前完整不可变命令日志 */
    public synchronized List<Command> journal() {
        return List.copyOf(journal);
    }

    /** @return 当前状态摘要 */
    public synchronized String checksum() {
        return checksum(remainingByOrder);
    }

    /** @return 当前Epoch */
    public synchronized long epoch() {
        return epoch;
    }

    /** 校验命令不会覆盖已有订单或取消未知订单。 */
    private void validate(CommandType type, String orderId, BigDecimal quantity) {
        if (type == CommandType.PLACE) {
            if (quantity.signum() <= 0) {
                throw new IllegalArgumentException("下单数量必须大于零");
            }
            if (remainingByOrder.containsKey(orderId)) {
                throw new IllegalArgumentException("订单已经存在");
            }
            return;
        }
        if (quantity.signum() != 0) {
            throw new IllegalArgumentException("撤单命令数量必须为零");
        }
        if (!remainingByOrder.containsKey(orderId)) {
            throw new IllegalArgumentException("不能撤销未知订单");
        }
    }

    /** 将一条已验证命令应用到给定状态。 */
    private static void apply(Map<String, BigDecimal> state, Command command) {
        if (command.type() == CommandType.PLACE) {
            if (state.putIfAbsent(command.orderId(), command.quantity()) != null) {
                throw new IllegalStateException("重放时发现重复订单");
            }
        } else if (state.replace(command.orderId(), BigDecimal.ZERO) == null) {
            throw new IllegalStateException("重放时撤销未知订单");
        }
    }

    /** 为排序后的订单状态计算稳定SHA-256摘要。 */
    private static String checksum(Map<String, BigDecimal> state) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            state.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '=');
                digest.update(entry.getValue().stripTrailingZeros().toPlainString()
                    .getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前JVM不支持SHA-256", exception);
        }
    }

    /** 不可变命令日志记录。 */
    public record Command(long sequence, long epoch, String commandId,
                          CommandType type, String orderId, BigDecimal quantity) {
    }

    /** 追加命令结果。 */
    public record AppendResult(String status, long sequence, String stateChecksum,
                               boolean duplicate) {
    }

    /** 可持久化的撮合状态快照。 */
    public record Snapshot(long lastSequence, long epoch,
                           Map<String, BigDecimal> remainingByOrder,
                           String checksum) {
        /** 防止调用方修改快照状态。 */
        public Snapshot {
            remainingByOrder = Map.copyOf(remainingByOrder);
        }
    }

    /** 确定性重放得到的状态。 */
    public record RecoveredState(long lastSequence,
                                 Map<String, BigDecimal> remainingByOrder,
                                 String checksum) {
        /** 防止调用方修改恢复结果。 */
        public RecoveredState {
            remainingByOrder = Map.copyOf(remainingByOrder);
        }
    }
}

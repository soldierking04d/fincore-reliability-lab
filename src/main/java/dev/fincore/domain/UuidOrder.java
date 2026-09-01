package dev.fincore.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 账户锁使用的稳定 UUID 顺序。
 *
 * <p>直接比较 UUID 的两个 64 位分量，避免高频资金事务为了排序创建 UUID 字符串。全部资金服务
 * 必须复用同一比较器，才能在并发锁定多个账户时避免锁顺序环。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.1.0
 */
public final class UuidOrder {
    /** 按 UUID 无符号 128 位数值排序的共享比较器。 */
    public static final Comparator<UUID> COMPARATOR = (left, right) -> {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0
            ? high
            : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    };

    /** 禁止实例化工具类。 */
    private UuidOrder() {
    }

    /**
     * 去重并按共享顺序排列 UUID。
     *
     * @param identifiers 待排序编号
     * @return 新建的有序列表
     */
    public static List<UUID> uniqueSorted(UUID... identifiers) {
        List<UUID> ordered = new ArrayList<>(identifiers.length);
        for (UUID identifier : identifiers) {
            if (!ordered.contains(identifier)) {
                ordered.add(identifier);
            }
        }
        ordered.sort(COMPARATOR);
        return ordered;
    }

    /** 对已有可变列表应用共享顺序并移除重复编号。 */
    public static void sortAndRemoveDuplicates(List<UUID> identifiers) {
        identifiers.sort(COMPARATOR);
        for (int index = identifiers.size() - 1; index > 0; index--) {
            if (identifiers.get(index).equals(identifiers.get(index - 1))) {
                identifiers.remove(index);
            }
        }
    }
}

package dev.fincore.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 账户锁使用的稳定 UUID 顺序。
 *
 * <p><strong>解决的问题：</strong>两个事务若以相反顺序锁定账户，会形成死锁环。全部资金服务复用
 * 同一个 128 位全序，使任意账户组合都按相同顺序进入数据库行锁。</p>
 *
 * <p><strong>CPU 与分配优化：</strong>直接比较 UUID 的两个 64 位分量，不调用 {@code toString()}，
 * 因而不创建字符串和字符数组。小规模账户集合直接线性去重，省去 HashSet、装箱节点和二次复制；
 * 当前单笔资金事务通常只有 2～4 个账户，此时 O(n²) 比额外哈希结构更可控。</p>
 *
 * <p><strong>正确性边界：</strong>排序只能预防本项目内部的锁序环，不能替代数据库超时、死锁监控和
 * 事务重试；新增资金模块必须使用同一比较器，不能按业务角色或 UUID 字符串另定顺序。</p>
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
        // 这里的 n 是单笔事务账户数而非全量账户数；小集合线性去重避免创建 HashSet。
        List<UUID> ordered = new ArrayList<>(identifiers.length);
        for (UUID identifier : identifiers) {
            if (!ordered.contains(identifier)) {
                ordered.add(identifier);
            }
        }
        ordered.sort(COMPARATOR);
        return ordered;
    }

    /**
     * 对已有可变列表原地应用共享顺序并移除相邻重复编号。
     *
     * <p>原地处理避免再复制完整列表；必须倒序删除，防止索引移动后漏检。</p>
     */
    public static void sortAndRemoveDuplicates(List<UUID> identifiers) {
        identifiers.sort(COMPARATOR);
        for (int index = identifiers.size() - 1; index > 0; index--) {
            if (identifiers.get(index).equals(identifiers.get(index - 1))) {
                identifiers.remove(index);
            }
        }
    }
}

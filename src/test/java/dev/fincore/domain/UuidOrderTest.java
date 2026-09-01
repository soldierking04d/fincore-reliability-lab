package dev.fincore.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 多账户确定性锁顺序测试。 */
class UuidOrderTest {

    /** 无论输入次序和重复项如何，全部资金服务都能得到同一锁顺序。 */
    @Test
    void uniqueSortedProducesStableOrderWithoutDuplicateLocks() {
        UUID high = new UUID(-1L, 1L);
        UUID middle = new UUID(5L, -1L);
        UUID low = new UUID(0L, 2L);

        List<UUID> first = UuidOrder.uniqueSorted(high, low, middle, low);
        List<UUID> second = UuidOrder.uniqueSorted(middle, high, low);

        assertEquals(first, second);
        assertEquals(3, first.size());
        assertTrue(UuidOrder.COMPARATOR.compare(first.get(0), first.get(1)) < 0);
        assertTrue(UuidOrder.COMPARATOR.compare(first.get(1), first.get(2)) < 0);
    }

    /** 原地排序方法同样移除相邻重复编号。 */
    @Test
    void mutableSortRemovesDuplicates() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        List<UUID> values = new ArrayList<>(List.of(second, first, second));

        UuidOrder.sortAndRemoveDuplicates(values);

        assertEquals(2, values.size());
        assertTrue(UuidOrder.COMPARATOR.compare(values.get(0), values.get(1)) < 0);
    }
}

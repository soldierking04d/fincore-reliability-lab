package dev.fincore.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 分片路由确定性、边界与分片数量约束的单元测试。
 *
 * @author FinCore Reliability Lab
 * @since 1.0.0
 */
class ShardRouterTest {
    @Test void routingIsDeterministicAndBounded() {
        ShardRouter router = new ShardRouter(8);
        int shard = router.shardFor("user-123");
        assertEquals(shard, router.shardFor("user-123"));
        assertTrue(shard >= 0 && shard < 8);
    }

    @Test void feeShardCountMustBePowerOfTwo() {
        assertThrows(IllegalArgumentException.class, () -> new FeeShardRouter(10));
    }
}

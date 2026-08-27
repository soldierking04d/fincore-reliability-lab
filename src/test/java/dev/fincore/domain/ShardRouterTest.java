package dev.fincore.domain;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

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


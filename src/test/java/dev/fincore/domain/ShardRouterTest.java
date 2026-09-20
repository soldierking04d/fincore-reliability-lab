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
        for (int invalidCount : new int[]{0, -1, 3, 10, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new FeeShardRouter(invalidCount));
        }
    }

    /** 重投和新建路由实例必须保持同一业务事实的费用账户选择。 */
    @Test void feeRoutingIsDeterministicAcrossInstancesAndReplays() {
        FeeShardRouter first = new FeeShardRouter(16);
        FeeShardRouter restarted = new FeeShardRouter(16);
        for (int order = 0; order < 512; order++) {
            String key = settlementKey(order);
            int expected = first.shardFor(key);
            assertEquals(expected, first.shardFor(key), key);
            assertEquals(expected, restarted.shardFor(key), key);
        }
    }

    /** 费用路由在单分片、常用规模及最大合法正分片数下都不能越界。 */
    @Test void feeRoutingStaysInsideEveryConfiguredShardRange() {
        String[] edgeKeys = {"a", "Aa", "BB", "订单:手续费:001", "polygenelubricants"};
        for (int count : new int[]{1, 2, 4, 16, 64, 1024, 1 << 30}) {
            FeeShardRouter router = new FeeShardRouter(count);
            for (String key : edgeKeys) {
                assertFeeShardInRange(router, count, key);
            }
            for (int order = 0; order < 512; order++) {
                assertFeeShardInRange(router, count, settlementKey(order));
            }
        }
    }

    /** 固定业务键样本检测明显路由集中退化，不用统计分布推断数据库吞吐。 */
    @Test void fixedSettlementKeysReachAllFeeShardsWithoutGrossConcentration() {
        int sampleCount = 4096;
        int shardCount = 16;
        int[] histogram = new int[shardCount];
        FeeShardRouter router = new FeeShardRouter(shardCount);
        for (int order = 0; order < sampleCount; order++) {
            histogram[router.shardFor(settlementKey(order))]++;
        }
        int observed = 0;
        for (int shard = 0; shard < shardCount; shard++) {
            int samples = histogram[shard];
            observed += samples;
            assertTrue(samples > 0, "固定样本没有到达费用分片 " + shard);
            // 容许远大于均匀分布的偏斜，只阻止一处分片吸收超过四分之一的样本。
            assertTrue(samples <= sampleCount / 4, "固定样本过度集中于费用分片 " + shard);
        }
        assertEquals(sampleCount, observed);
    }

    /** FeeShardRouter 的既有契约拒绝 null 和 String.isBlank 定义的空白键。 */
    @Test void feeRoutingRejectsMissingBusinessKeys() {
        FeeShardRouter router = new FeeShardRouter(16);
        assertThrows(IllegalArgumentException.class, () -> router.shardFor(null));
        for (String key : new String[]{"", " ", "\t\n", "\u2003"}) {
            assertThrows(IllegalArgumentException.class, () -> router.shardFor(key));
        }
    }

    /** 不含随机值的成交费用键；相同日期、交易对与递增成交编号可重复构造。 */
    private static String settlementKey(int order) {
        String[] symbols = {"BTC-USDT", "ETH-USDT", "SOL-USDT", "BTC-USDC"};
        return "settlement:" + symbols[order % symbols.length]
            + ":20260920:00000000-0000-4000-8000-" + (100_000_000_000L + order);
    }

    /** 只验证输出契约，不在测试里复制路由器的 hash 混合公式。 */
    private static void assertFeeShardInRange(FeeShardRouter router, int count, String key) {
        int shard = router.shardFor(key);
        assertTrue(shard >= 0 && shard < count, "分片越界 count=" + count + ", key=" + key);
    }
}

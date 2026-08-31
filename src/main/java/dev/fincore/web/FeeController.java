package dev.fincore.web;

import dev.fincore.application.FeeAggregationService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手续费分片和归集接口。
 *
 * <p>通过确定性分片降低单个手续费账户的热点写竞争，并支持把各分片余额以幂等方式
 * 汇总到财资账户。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@RestController
@RequestMapping("/api/fees")
public class FeeController {
    /** 手续费应用服务。 */
    private final FeeAggregationService fees;

    /** @param fees 手续费应用服务 */
    public FeeController(FeeAggregationService fees) {
        this.fees = fees;
    }

    /**
     * 按资产确保手续费分片账户全部存在。
     *
     * @param asset 资产代码
     * @param count 分片数量
     * @return 分片账户列表
     */
    @PostMapping("/shards")
    public List<FeeAggregationService.FeeAccount> ensureShards(@RequestParam String asset,
                                                               @RequestParam(defaultValue = "16") int count) {
        return fees.ensureShards(asset, count);
    }

    /**
     * 查询业务键应使用的手续费账户。
     *
     * @param asset 资产代码
     * @param count 分片数量
     * @param businessKey 稳定业务键
     * @return 目标手续费账户
     */
    @GetMapping("/route")
    public FeeAggregationService.FeeAccount route(@RequestParam String asset,
                                                   @RequestParam(defaultValue = "16") int count,
                                                   @RequestParam String businessKey) {
        return fees.route(asset, count, businessKey);
    }

    /**
     * 确保指定资产的财资账户存在。
     *
     * @param asset 资产代码
     * @return 财资账户
     */
    @PostMapping("/treasury")
    public FeeAggregationService.FeeAccount createTreasury(@RequestParam String asset) {
        return fees.ensureTreasury(asset);
    }

    /**
     * 将手续费分片余额幂等归集到财资账户。
     *
     * @param request 归集幂等键、资产和财资账户
     * @return 归集结果
     */
    @PostMapping("/aggregate")
    public FeeAggregationService.AggregationOutcome aggregate(@RequestBody AggregationRequest request) {
        return fees.aggregate(request.aggregationKey(), request.asset(), request.treasuryAccountId());
    }

    /**
     * 手续费归集请求。
     *
     * @param aggregationKey 归集幂等键
     * @param asset 资产代码
     * @param treasuryAccountId 财资账户编号
     */
    public record AggregationRequest(String aggregationKey, String asset, UUID treasuryAccountId) {
    }
}

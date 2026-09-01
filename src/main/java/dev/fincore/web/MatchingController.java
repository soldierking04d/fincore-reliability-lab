package dev.fincore.web;

import dev.fincore.application.MatchingCommandCoordinator;
import dev.fincore.domain.MatchingResult;
import dev.fincore.domain.OrderBookView;
import dev.fincore.domain.OrderView;
import dev.fincore.domain.PlaceOrderCommand;
import dev.fincore.domain.TradeView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 撮合订单、订单簿和成交查询接口。
 *
 * <p>控制器不直接访问数据库；交易对锁、价格时间优先、幂等和数量守恒全部由
 * {@link dev.fincore.application.MatchingService} 在事务内保证；写命令先经过有界 Lane 执行器，
 * 队列饱和时明确返回可重试错误。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@RestController
@RequestMapping("/api/matching")
public class MatchingController {
    /** 撮合应用服务。 */
    private final MatchingCommandCoordinator matching;

    /** @param matching 撮合应用服务 */
    public MatchingController(MatchingCommandCoordinator matching) {
        this.matching = matching;
    }

    /**
     * 提交限价单或市价单。
     *
     * @param command 下单命令
     * @return 订单最终快照和本次形成的成交
     */
    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public MatchingResult place(@RequestBody PlaceOrderCommand command) {
        return matching.place(command);
    }

    /** @return 指定订单的当前快照 */
    @GetMapping("/orders/{orderId}")
    public OrderView get(@PathVariable UUID orderId) {
        return matching.get(orderId);
    }

    /**
     * 取消仍处于开放状态的订单。
     *
     * @param orderId 订单编号
     * @param userId 发起取消的用户
     * @return 取消后的订单快照
     */
    @DeleteMapping("/orders/{orderId}")
    public OrderView cancel(@PathVariable UUID orderId, @RequestParam String userId) {
        return matching.cancel(orderId, userId);
    }

    /**
     * 查询按价格聚合的订单簿。
     *
     * @param symbol 交易对
     * @param depth 最大档位深度
     * @return 买卖盘聚合快照
     */
    @GetMapping("/books/{symbol}")
    public OrderBookView book(@PathVariable String symbol,
                              @RequestParam(defaultValue = "20") int depth) {
        return matching.book(symbol, depth);
    }

    /**
     * 查询交易对最近成交。
     *
     * @param symbol 交易对
     * @param limit 最大返回数量
     * @return 按成交序列倒序排列的成交
     */
    @GetMapping("/trades/{symbol}")
    public List<TradeView> trades(@PathVariable String symbol,
                                  @RequestParam(defaultValue = "50") int limit) {
        return matching.recentTrades(symbol, limit);
    }
}

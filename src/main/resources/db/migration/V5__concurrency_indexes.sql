-- Outbox 抢占只扫描真正可发布的记录；INCLUDE 让高频抢占减少回表。
CREATE INDEX idx_outbox_ready_batch
    ON outbox_event(next_attempt_at, created_at)
    INCLUDE (event_id)
    WHERE status='PENDING';

-- Taker 幂等重放直接按订单读取其成交，避免并发重试时扫描全部成交。
CREATE INDEX idx_trade_taker_sequence
    ON trade_execution(taker_order_id, trade_sequence);

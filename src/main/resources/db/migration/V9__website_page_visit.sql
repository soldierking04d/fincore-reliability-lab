-- 页面访问日志独立于资金账本；事件 UUID 是重试去重边界，时间由数据库生成。
CREATE TABLE website_page_visit (
    event_id UUID PRIMARY KEY,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    client_ip INET,
    page_path VARCHAR(160) NOT NULL,
    referrer_origin VARCHAR(255),
    user_agent VARCHAR(512),
    language VARCHAR(64)
);

-- 保留期清理只扫描此日志表的到期前缀，避免全表扫描。
CREATE INDEX idx_website_page_visit_received_at ON website_page_visit (received_at);

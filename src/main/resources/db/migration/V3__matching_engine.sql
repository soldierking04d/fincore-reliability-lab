CREATE TABLE matching_sequence (
    symbol VARCHAR(50) PRIMARY KEY,
    next_value BIGINT NOT NULL,
    CONSTRAINT ck_matching_sequence_positive CHECK (next_value > 0)
);

CREATE TABLE matching_order (
    order_id UUID PRIMARY KEY,
    client_order_id VARCHAR(100) NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    symbol VARCHAR(50) NOT NULL,
    side VARCHAR(10) NOT NULL,
    order_type VARCHAR(10) NOT NULL,
    price NUMERIC(38, 18),
    original_quantity NUMERIC(38, 18) NOT NULL,
    executed_quantity NUMERIC(38, 18) NOT NULL DEFAULT 0,
    remaining_quantity NUMERIC(38, 18) NOT NULL,
    status VARCHAR(30) NOT NULL,
    order_sequence BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    detail VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_matching_client_order UNIQUE(user_id, client_order_id),
    CONSTRAINT uk_matching_symbol_order_sequence UNIQUE(symbol, order_sequence),
    CONSTRAINT ck_matching_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_matching_type CHECK (order_type IN ('LIMIT', 'MARKET')),
    CONSTRAINT ck_matching_status CHECK (status IN ('OPEN', 'PARTIALLY_FILLED', 'FILLED', 'CANCELED', 'REJECTED')),
    CONSTRAINT ck_matching_quantity_positive CHECK (original_quantity > 0),
    CONSTRAINT ck_matching_quantity_non_negative CHECK (executed_quantity >= 0 AND remaining_quantity >= 0),
    CONSTRAINT ck_matching_quantity_conserved CHECK (executed_quantity + remaining_quantity = original_quantity),
    CONSTRAINT ck_matching_price CHECK (
        (order_type = 'LIMIT' AND price > 0) OR
        (order_type = 'MARKET' AND price IS NULL)
    )
);

CREATE INDEX idx_matching_active_book
    ON matching_order(symbol, side, price, order_sequence)
    WHERE status IN ('OPEN', 'PARTIALLY_FILLED');

CREATE TABLE trade_execution (
    trade_id UUID PRIMARY KEY,
    symbol VARCHAR(50) NOT NULL,
    maker_order_id UUID NOT NULL REFERENCES matching_order(order_id),
    taker_order_id UUID NOT NULL REFERENCES matching_order(order_id),
    price NUMERIC(38, 18) NOT NULL,
    quantity NUMERIC(38, 18) NOT NULL,
    quote_amount NUMERIC(38, 18) NOT NULL,
    trade_sequence BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_trade_symbol_sequence UNIQUE(symbol, trade_sequence),
    CONSTRAINT ck_trade_price_positive CHECK (price > 0),
    CONSTRAINT ck_trade_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_trade_quote_positive CHECK (quote_amount > 0),
    CONSTRAINT ck_trade_distinct_orders CHECK (maker_order_id <> taker_order_id)
);
CREATE INDEX idx_trade_symbol_time ON trade_execution(symbol, trade_sequence DESC);

CREATE TABLE matching_audit (
    audit_id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES matching_order(order_id),
    from_status VARCHAR(30),
    to_status VARCHAR(30) NOT NULL,
    reason VARCHAR(500),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_matching_audit_order ON matching_audit(order_id, changed_at);

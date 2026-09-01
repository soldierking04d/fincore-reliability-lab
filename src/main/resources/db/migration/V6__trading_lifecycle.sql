CREATE TABLE customer_profile (
    user_id VARCHAR(100) PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    country_code VARCHAR(2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    kyc_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_customer_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT ck_customer_kyc CHECK (kyc_status IN ('PENDING', 'VERIFIED', 'REJECTED'))
);

CREATE TABLE risk_profile (
    user_id VARCHAR(100) PRIMARY KEY REFERENCES customer_profile(user_id),
    risk_level VARCHAR(20) NOT NULL,
    trading_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    max_order_notional NUMERIC(38, 18) NOT NULL,
    max_daily_notional NUMERIC(38, 18) NOT NULL,
    max_price_deviation NUMERIC(10, 6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_risk_level CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_risk_order_limit CHECK (max_order_notional > 0),
    CONSTRAINT ck_risk_daily_limit CHECK (max_daily_notional >= max_order_notional),
    CONSTRAINT ck_risk_price_deviation CHECK (max_price_deviation > 0 AND max_price_deviation <= 1)
);

CREATE TABLE market_reference_price (
    symbol VARCHAR(50) PRIMARY KEY,
    price NUMERIC(38, 18) NOT NULL,
    source VARCHAR(100) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_market_price_positive CHECK (price > 0)
);

CREATE TABLE pre_trade_decision (
    decision_id UUID PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL,
    client_order_id VARCHAR(100) NOT NULL,
    symbol VARCHAR(50) NOT NULL,
    side VARCHAR(10) NOT NULL,
    order_type VARCHAR(10) NOT NULL,
    limit_price NUMERIC(38, 18),
    quantity NUMERIC(38, 18) NOT NULL,
    reference_price NUMERIC(38, 18),
    order_notional NUMERIC(38, 18),
    account_id UUID REFERENCES account(account_id),
    decision VARCHAR(20) NOT NULL,
    reason_code VARCHAR(50) NOT NULL,
    reason_detail VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_pre_trade_order UNIQUE(user_id, client_order_id),
    CONSTRAINT ck_pre_trade_side CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT ck_pre_trade_type CHECK (order_type IN ('LIMIT', 'MARKET')),
    CONSTRAINT ck_pre_trade_decision CHECK (decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ck_pre_trade_quantity CHECK (quantity > 0)
);

CREATE INDEX idx_pre_trade_user_day
    ON pre_trade_decision(user_id, created_at)
    WHERE decision='APPROVED';

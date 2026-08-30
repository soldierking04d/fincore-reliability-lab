CREATE TABLE trade_sync_inbox (
    event_id UUID PRIMARY KEY,
    trade_id UUID NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    CONSTRAINT ck_trade_sync_inbox_status
        CHECK (status IN ('RECEIVED', 'PROCESSED'))
);
CREATE INDEX idx_trade_sync_inbox_trade ON trade_sync_inbox(trade_id);

CREATE TABLE trade_projection (
    trade_id UUID PRIMARY KEY,
    symbol VARCHAR(50) NOT NULL,
    maker_order_id UUID NOT NULL,
    taker_order_id UUID NOT NULL,
    price NUMERIC(38, 18) NOT NULL,
    quantity NUMERIC(38, 18) NOT NULL,
    quote_amount NUMERIC(38, 18) NOT NULL,
    trade_sequence BIGINT NOT NULL,
    source_event_id UUID,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_trade_projection_price CHECK (price > 0),
    CONSTRAINT ck_trade_projection_quantity CHECK (quantity > 0),
    CONSTRAINT ck_trade_projection_quote CHECK (quote_amount > 0),
    CONSTRAINT ck_trade_projection_status CHECK (status IN ('ACTIVE', 'QUARANTINED'))
);
CREATE UNIQUE INDEX uk_trade_projection_active_sequence
    ON trade_projection(symbol, trade_sequence) WHERE status='ACTIVE';
CREATE INDEX idx_trade_projection_symbol_status
    ON trade_projection(symbol, status, trade_sequence);

CREATE TABLE trade_reconciliation_run (
    run_id UUID PRIMARY KEY,
    symbol VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL,
    source_count BIGINT NOT NULL DEFAULT 0,
    projection_count BIGINT NOT NULL DEFAULT 0,
    missing_count INT NOT NULL DEFAULT 0,
    mismatch_count INT NOT NULL DEFAULT 0,
    extra_count INT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_trade_reconciliation_status
        CHECK (status IN ('RUNNING', 'CLEAN', 'DIFFERENCE_FOUND'))
);

CREATE TABLE trade_reconciliation_difference (
    difference_id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES trade_reconciliation_run(run_id),
    trade_id UUID NOT NULL,
    difference_type VARCHAR(20) NOT NULL,
    expected_payload TEXT,
    actual_payload TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    repaired_at TIMESTAMPTZ,
    CONSTRAINT uk_trade_reconciliation_difference
        UNIQUE(run_id, trade_id, difference_type),
    CONSTRAINT ck_trade_difference_type
        CHECK (difference_type IN ('MISSING', 'MISMATCH', 'EXTRA')),
    CONSTRAINT ck_trade_difference_status
        CHECK (status IN ('OPEN', 'REPAIRED'))
);
CREATE INDEX idx_trade_difference_run_status
    ON trade_reconciliation_difference(run_id, status);

CREATE TABLE trade_projection_repair (
    repair_id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES trade_reconciliation_run(run_id),
    idempotency_key VARCHAR(120) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL,
    repaired_count INT NOT NULL DEFAULT 0,
    quarantined_count INT NOT NULL DEFAULT 0,
    detail TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_trade_projection_repair_status
        CHECK (status IN ('PROCESSING', 'SUCCESS', 'FAILED'))
);

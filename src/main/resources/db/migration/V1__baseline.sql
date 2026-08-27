CREATE TABLE account (
    account_id UUID PRIMARY KEY,
    owner_id VARCHAR(100) NOT NULL,
    asset VARCHAR(20) NOT NULL,
    account_type VARCHAR(30) NOT NULL,
    opening_balance NUMERIC(38, 18) NOT NULL DEFAULT 0,
    balance NUMERIC(38, 18) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_account_owner_asset_type UNIQUE(owner_id, asset, account_type),
    CONSTRAINT ck_account_non_negative CHECK (balance >= 0)
);

CREATE TABLE settlement_order (
    business_key VARCHAR(100) PRIMARY KEY,
    message_id VARCHAR(100) NOT NULL UNIQUE,
    payer_account_id UUID NOT NULL REFERENCES account(account_id),
    payee_account_id UUID NOT NULL REFERENCES account(account_id),
    fee_account_id UUID NOT NULL REFERENCES account(account_id),
    asset VARCHAR(20) NOT NULL,
    amount NUMERIC(38, 18) NOT NULL,
    fee NUMERIC(38, 18) NOT NULL,
    status VARCHAR(30) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    failure_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_settlement_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_settlement_fee_non_negative CHECK (fee >= 0)
);

CREATE TABLE inbox_message (
    message_id VARCHAR(100) PRIMARY KEY,
    message_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);

CREATE TABLE ledger_transaction (
    transaction_id UUID PRIMARY KEY,
    business_key VARCHAR(100) NOT NULL UNIQUE,
    transaction_type VARCHAR(30) NOT NULL,
    asset VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entry (
    entry_id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES ledger_transaction(transaction_id),
    account_id UUID NOT NULL REFERENCES account(account_id),
    direction VARCHAR(10) NOT NULL,
    amount NUMERIC(38, 18) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_ledger_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_ledger_direction CHECK (direction IN ('DEBIT', 'CREDIT'))
);
CREATE INDEX idx_ledger_entry_account ON ledger_entry(account_id, created_at);

CREATE TABLE state_audit (
    audit_id UUID PRIMARY KEY,
    business_key VARCHAR(100) NOT NULL,
    from_status VARCHAR(30),
    to_status VARCHAR(30) NOT NULL,
    reason VARCHAR(500),
    changed_by VARCHAR(100) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox_event (
    event_id UUID PRIMARY KEY,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox_event(status, next_attempt_at);

CREATE TABLE reconciliation_issue (
    issue_id UUID PRIMARY KEY,
    account_id UUID REFERENCES account(account_id),
    business_key VARCHAR(100),
    issue_type VARCHAR(50) NOT NULL,
    expected_amount NUMERIC(38, 18),
    actual_amount NUMERIC(38, 18),
    risk_level VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    details TEXT,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX uk_reconciliation_open_account_type
    ON reconciliation_issue(account_id, issue_type) WHERE status='OPEN';

CREATE TABLE compensation_order (
    compensation_id UUID PRIMARY KEY,
    original_business_key VARCHAR(100) NOT NULL,
    compensation_business_key VARCHAR(160) NOT NULL,
    status VARCHAR(30) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE shard_lease (
    shard_id INT PRIMARY KEY,
    owner_id VARCHAR(100) NOT NULL,
    epoch BIGINT NOT NULL,
    state VARCHAR(20) NOT NULL,
    lease_until TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

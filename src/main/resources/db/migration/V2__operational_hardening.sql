ALTER TABLE outbox_event ADD COLUMN claimed_at TIMESTAMPTZ;
ALTER TABLE outbox_event ADD COLUMN publisher_id VARCHAR(100);

CREATE TABLE fee_aggregation (
    aggregation_key VARCHAR(120) PRIMARY KEY,
    asset VARCHAR(20) NOT NULL,
    treasury_account_id UUID NOT NULL REFERENCES account(account_id),
    status VARCHAR(30) NOT NULL,
    total_amount NUMERIC(38, 18),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);


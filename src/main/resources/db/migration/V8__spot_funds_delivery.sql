-- 现货资金账户保持总余额口径；可用 = 总余额 - 委托预占 - 成交在途。
-- 增量迁移不把既有未冻结订单追认为资金订单，历史实验保持原样。
ALTER TABLE account ADD COLUMN reserved_balance NUMERIC(38,18) NOT NULL DEFAULT 0;
ALTER TABLE account ADD COLUMN pending_debit NUMERIC(38,18) NOT NULL DEFAULT 0;
ALTER TABLE account ADD COLUMN financial_hold BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE account ADD CONSTRAINT ck_account_funds_covered
    CHECK (reserved_balance >= 0 AND pending_debit >= 0 AND balance >= reserved_balance + pending_debit);

CREATE TABLE spot_funded_market (
    symbol VARCHAR(50) PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE spot_order_reservation (
    order_id UUID PRIMARY KEY REFERENCES matching_order(order_id),
    payer_account_id UUID NOT NULL REFERENCES account(account_id),
    receiver_account_id UUID NOT NULL REFERENCES account(account_id),
    initial_amount NUMERIC(38,18) NOT NULL CHECK (initial_amount > 0),
    held NUMERIC(38,18) NOT NULL CHECK (held >= 0),
    pending NUMERIC(38,18) NOT NULL DEFAULT 0 CHECK (pending >= 0),
    settled NUMERIC(38,18) NOT NULL DEFAULT 0 CHECK (settled >= 0),
    released NUMERIC(38,18) NOT NULL DEFAULT 0 CHECK (released >= 0),
    version BIGINT NOT NULL DEFAULT 0,
    CHECK (held + pending + settled + released = initial_amount),
    CHECK (payer_account_id <> receiver_account_id)
);
CREATE INDEX idx_spot_reservation_payer ON spot_order_reservation(payer_account_id);

CREATE TABLE spot_delivery (
    trade_id UUID PRIMARY KEY REFERENCES trade_execution(trade_id),
    buy_order_id UUID NOT NULL REFERENCES spot_order_reservation(order_id),
    sell_order_id UUID NOT NULL REFERENCES spot_order_reservation(order_id),
    buyer_quote_id UUID NOT NULL REFERENCES account(account_id),
    buyer_base_id UUID NOT NULL REFERENCES account(account_id),
    seller_base_id UUID NOT NULL REFERENCES account(account_id),
    seller_quote_id UUID NOT NULL REFERENCES account(account_id),
    base_asset VARCHAR(20) NOT NULL,
    quote_asset VARCHAR(20) NOT NULL,
    quantity NUMERIC(38,18) NOT NULL CHECK (quantity > 0),
    quote_amount NUMERIC(38,18) NOT NULL CHECK (quote_amount > 0),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SETTLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at TIMESTAMPTZ,
    CHECK (base_asset <> quote_asset),
    CHECK ((status='SETTLED') = (settled_at IS NOT NULL))
);
CREATE INDEX idx_spot_delivery_pending ON spot_delivery(created_at) WHERE status='PENDING';

-- 分桶变动与总余额变动的逐账户守恒审计。只追加，不提供历史覆盖接口。
CREATE TABLE spot_fund_journal (
    event_key VARCHAR(160) NOT NULL,
    account_id UUID NOT NULL REFERENCES account(account_id),
    available_delta NUMERIC(38,18) NOT NULL,
    reserved_delta NUMERIC(38,18) NOT NULL,
    pending_delta NUMERIC(38,18) NOT NULL,
    balance_delta NUMERIC(38,18) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(event_key, account_id),
    CHECK (available_delta + reserved_delta + pending_delta = balance_delta)
);
CREATE INDEX idx_spot_journal_account ON spot_fund_journal(account_id);

CREATE TABLE spot_delivery_inbox (
    message_id VARCHAR(100) PRIMARY KEY,
    trade_id UUID NOT NULL REFERENCES spot_delivery(trade_id),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE FUNCTION reject_spot_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'spot financial history is append-only';
END;
$$;
CREATE TRIGGER immutable_spot_journal BEFORE UPDATE OR DELETE ON spot_fund_journal
    FOR EACH ROW EXECUTE FUNCTION reject_spot_history_mutation();
CREATE TRIGGER immutable_spot_inbox BEFORE UPDATE OR DELETE ON spot_delivery_inbox
    FOR EACH ROW EXECUTE FUNCTION reject_spot_history_mutation();

CREATE FUNCTION protect_spot_delivery() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR OLD.status='SETTLED' OR
       (to_jsonb(NEW) - 'status' - 'settled_at') IS DISTINCT FROM
       (to_jsonb(OLD) - 'status' - 'settled_at') THEN
        RAISE EXCEPTION 'spot delivery facts and settled result are immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER immutable_spot_delivery BEFORE UPDATE OR DELETE ON spot_delivery
    FOR EACH ROW EXECUTE FUNCTION protect_spot_delivery();

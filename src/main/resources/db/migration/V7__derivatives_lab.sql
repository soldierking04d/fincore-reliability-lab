-- 仅供 lab Profile 使用的独立模拟资产域，不与现货账户或真实资金混用。
-- NUMERIC(28,8) 的精度由 Java 入口显式校验，不允许数据库静默舍入用户输入。
CREATE TABLE lab_derivative_account (
    account_id UUID PRIMARY KEY,
    opening_wallet NUMERIC(28,8) NOT NULL CHECK (opening_wallet >= 0),
    wallet NUMERIC(28,8) NOT NULL,
    reserved NUMERIC(28,8) NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    version BIGINT NOT NULL DEFAULT 0,
    epoch BIGINT NOT NULL DEFAULT 0,
    state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE', 'LIQUIDATING'))
);
-- wallet 可以因已发生的资金费或亏损变负，不能通过拒绝记账隐藏穿仓。
-- 本实验没有保险基金/ADL；负权益需要暴露并停止新开仓，不宣称已完成穿仓处置。

CREATE TABLE lab_derivative_position (
    account_id UUID PRIMARY KEY REFERENCES lab_derivative_account(account_id),
    symbol VARCHAR(50) NOT NULL,
    quantity NUMERIC(28,8) NOT NULL,
    entry_price NUMERIC(28,8) NOT NULL CHECK (entry_price > 0)
);
-- 每个风险账户最多一个净仓位：风险判断是简化逐仓，不冒充全仓多资产组合保证金。

CREATE TABLE lab_derivative_operation (
    operation_id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES lab_derivative_account(account_id),
    kind VARCHAR(30) NOT NULL,
    business_key VARCHAR(100) NOT NULL,
    request TEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    effect NUMERIC(28,8) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(account_id, kind, business_key)
);

CREATE TABLE lab_derivative_ledger (
    operation_id UUID NOT NULL REFERENCES lab_derivative_operation(operation_id),
    account_id UUID NOT NULL REFERENCES lab_derivative_account(account_id),
    delta NUMERIC(28,8) NOT NULL CHECK (delta <> 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(operation_id, account_id)
);
CREATE INDEX idx_lab_derivative_ledger_account ON lab_derivative_ledger(account_id);

CREATE TABLE lab_derivative_funding (
    account_id UUID NOT NULL REFERENCES lab_derivative_account(account_id),
    symbol VARCHAR(50) NOT NULL,
    cycle_at TIMESTAMPTZ NOT NULL,
    quantity NUMERIC(28,8) NOT NULL,
    mark_price NUMERIC(28,8) NOT NULL CHECK (mark_price > 0),
    rate NUMERIC(28,8) NOT NULL CHECK (abs(rate) <= 1),
    account_version BIGINT NOT NULL,
    PRIMARY KEY(account_id, symbol, cycle_at)
);

CREATE TABLE lab_derivative_inbox (
    message_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES lab_derivative_operation(operation_id),
    received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- 历史决定、周期快照、账本和 Inbox 均只增不改；纠正只能另建冲正业务，不能覆写。
CREATE FUNCTION lab_derivative_reject_history_change() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'derivatives lab financial history is append-only';
END;
$$;
CREATE TRIGGER lab_derivative_operation_immutable BEFORE UPDATE OR DELETE ON lab_derivative_operation
    FOR EACH ROW EXECUTE FUNCTION lab_derivative_reject_history_change();
CREATE TRIGGER lab_derivative_ledger_immutable BEFORE UPDATE OR DELETE ON lab_derivative_ledger
    FOR EACH ROW EXECUTE FUNCTION lab_derivative_reject_history_change();
CREATE TRIGGER lab_derivative_funding_immutable BEFORE UPDATE OR DELETE ON lab_derivative_funding
    FOR EACH ROW EXECUTE FUNCTION lab_derivative_reject_history_change();
CREATE TRIGGER lab_derivative_inbox_immutable BEFORE UPDATE OR DELETE ON lab_derivative_inbox
    FOR EACH ROW EXECUTE FUNCTION lab_derivative_reject_history_change();

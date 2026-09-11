CREATE TABLE broker_live_daily_order_risk_lock (
    lock_id SMALLINT PRIMARY KEY,
    CONSTRAINT chk_broker_live_daily_order_risk_lock_singleton CHECK (lock_id = 1)
);

INSERT INTO broker_live_daily_order_risk_lock (lock_id) VALUES (1);

CREATE TABLE broker_live_daily_order_risk_reservations (
    reservation_key_hash VARCHAR(64) PRIMARY KEY,
    account_seq BIGINT NOT NULL,
    business_date DATE NOT NULL,
    currency VARCHAR(3) NOT NULL,
    quantity DECIMAL(30, 10),
    order_amount DECIMAL(30, 10) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_broker_live_daily_order_risk_account CHECK (account_seq > 0),
    CONSTRAINT chk_broker_live_daily_order_risk_currency CHECK (currency IN ('KRW', 'USD')),
    CONSTRAINT chk_broker_live_daily_order_risk_quantity CHECK (quantity IS NULL OR quantity > 0),
    CONSTRAINT chk_broker_live_daily_order_risk_amount CHECK (order_amount > 0)
);

CREATE INDEX idx_broker_live_daily_order_risk_account_date
    ON broker_live_daily_order_risk_reservations (account_seq, business_date);

CREATE INDEX idx_broker_live_daily_order_risk_account_date_currency
    ON broker_live_daily_order_risk_reservations (account_seq, business_date, currency);

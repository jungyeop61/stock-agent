CREATE TABLE broker_live_order_rate_limit_lock (
    lock_id SMALLINT PRIMARY KEY,
    CONSTRAINT chk_broker_live_order_rate_limit_lock_singleton CHECK (lock_id = 1)
);

INSERT INTO broker_live_order_rate_limit_lock (lock_id) VALUES (1);

CREATE TABLE broker_live_order_rate_limit_reservations (
    reservation_key_hash VARCHAR(64) PRIMARY KEY,
    account_seq BIGINT NOT NULL,
    symbol_hash VARCHAR(64) NOT NULL,
    window_start TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_broker_live_order_rate_limit_account CHECK (account_seq > 0)
);

CREATE INDEX idx_broker_live_order_rate_limit_account_window
    ON broker_live_order_rate_limit_reservations (account_seq, window_start);

CREATE INDEX idx_broker_live_order_rate_limit_instrument_window
    ON broker_live_order_rate_limit_reservations (account_seq, symbol_hash, window_start);

CREATE TABLE amount_order_previews (
    preview_id VARCHAR(36) PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    account_seq BIGINT NOT NULL,
    symbol VARCHAR(30) NOT NULL,
    side VARCHAR(8) NOT NULL,
    order_type VARCHAR(8) NOT NULL,
    order_amount NUMERIC(65, 30) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    market_country VARCHAR(2) NOT NULL,
    reference_price NUMERIC(65, 18) NOT NULL,
    estimated_quantity NUMERIC(65, 18) NOT NULL,
    commission_rate NUMERIC(65, 18) NOT NULL,
    estimated_commission NUMERIC(65, 18) NOT NULL,
    estimated_total_cost NUMERIC(65, 30) NOT NULL,
    exchange_rate NUMERIC(65, 18) NOT NULL,
    exchange_rate_valid_from TIMESTAMP WITH TIME ZONE NOT NULL,
    exchange_rate_valid_until TIMESTAMP WITH TIME ZONE NOT NULL,
    estimated_order_amount_krw NUMERIC(65, 18) NOT NULL,
    requires_high_value_confirmation BOOLEAN NOT NULL,
    order_ready BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL
);

CREATE INDEX idx_amount_order_previews_status_expires_at
    ON amount_order_previews (status, expires_at);

CREATE TABLE order_previews (
    preview_id VARCHAR(36) PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    account_seq BIGINT NOT NULL,
    symbol VARCHAR(30) NOT NULL,
    side VARCHAR(8) NOT NULL,
    order_type VARCHAR(8) NOT NULL,
    quantity NUMERIC(65, 18) NOT NULL,
    requested_price NUMERIC(65, 18),
    reference_price NUMERIC(65, 18) NOT NULL,
    calculation_price NUMERIC(65, 18) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    market_country VARCHAR(2) NOT NULL,
    commission_rate NUMERIC(65, 18) NOT NULL,
    estimated_order_amount NUMERIC(65, 18) NOT NULL,
    estimated_commission NUMERIC(65, 18) NOT NULL,
    estimated_amount_after_commission NUMERIC(65, 18) NOT NULL,
    sell_tax_excluded BOOLEAN NOT NULL,
    requires_high_value_confirmation BOOLEAN NOT NULL,
    order_ready BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL,
    approved_at TIMESTAMP WITH TIME ZONE,
    consumed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_order_previews_status_expires_at
    ON order_previews (status, expires_at);

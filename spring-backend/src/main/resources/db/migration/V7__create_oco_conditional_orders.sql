CREATE TABLE oco_conditional_order_previews (
    preview_id VARCHAR(36) PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    account_seq BIGINT NOT NULL,
    symbol VARCHAR(32) NOT NULL,
    conditional_order_type VARCHAR(16) NOT NULL,
    quantity NUMERIC(65, 18) NOT NULL,
    order_type VARCHAR(8) NOT NULL,
    expire_date DATE NOT NULL,
    reference_price NUMERIC(65, 18) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    market_country VARCHAR(2) NOT NULL,
    commission_rate NUMERIC(65, 18) NOT NULL,
    first_side VARCHAR(8) NOT NULL,
    first_trigger_price NUMERIC(65, 18) NOT NULL,
    first_order_price NUMERIC(65, 18) NOT NULL,
    first_estimated_order_amount NUMERIC(65, 18) NOT NULL,
    first_estimated_commission NUMERIC(65, 18) NOT NULL,
    first_estimated_proceeds NUMERIC(65, 18) NOT NULL,
    second_side VARCHAR(8) NOT NULL,
    second_trigger_price NUMERIC(65, 18) NOT NULL,
    second_order_price NUMERIC(65, 18) NOT NULL,
    second_estimated_order_amount NUMERIC(65, 18) NOT NULL,
    second_estimated_commission NUMERIC(65, 18) NOT NULL,
    second_estimated_proceeds NUMERIC(65, 18) NOT NULL,
    sell_tax_excluded BOOLEAN NOT NULL,
    requires_high_value_confirmation BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL,
    approved_at TIMESTAMP WITH TIME ZONE,
    consumed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_oco_conditional_previews_status_expires_at
    ON oco_conditional_order_previews (status, expires_at);

CREATE TABLE oco_conditional_order_executions (
    execution_id VARCHAR(36) PRIMARY KEY,
    preview_id VARCHAR(36) NOT NULL UNIQUE,
    client_order_id VARCHAR(36) NOT NULL UNIQUE,
    conditional_order_id VARCHAR(512),
    broker_mode VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_type VARCHAR(32),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_oco_conditional_execution_preview
        FOREIGN KEY (preview_id) REFERENCES oco_conditional_order_previews (preview_id)
);

CREATE INDEX idx_oco_conditional_executions_status_updated_at
    ON oco_conditional_order_executions (status, updated_at);

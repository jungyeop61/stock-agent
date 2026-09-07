CREATE TABLE conditional_order_cancellation_previews (
    preview_id VARCHAR(36) PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    account_seq BIGINT NOT NULL,
    conditional_order_id VARCHAR(512) NOT NULL,
    conditional_order_type VARCHAR(16) NOT NULL,
    original_status VARCHAR(32) NOT NULL,
    symbol VARCHAR(32) NOT NULL,
    market VARCHAR(8) NOT NULL,
    quantity NUMERIC(65, 18) NOT NULL,
    order_type VARCHAR(8) NOT NULL,
    expire_date DATE,
    first_type VARCHAR(32) NOT NULL,
    first_status VARCHAR(32) NOT NULL,
    first_trigger_price NUMERIC(65, 18),
    first_target_profit_rate NUMERIC(65, 18),
    first_order_price NUMERIC(65, 18),
    first_triggered_order_id VARCHAR(512),
    second_type VARCHAR(32),
    second_status VARCHAR(32),
    second_trigger_price NUMERIC(65, 18),
    second_target_profit_rate NUMERIC(65, 18),
    second_order_price NUMERIC(65, 18),
    second_triggered_order_id VARCHAR(512),
    conditional_order_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(32) NOT NULL,
    approved_at TIMESTAMP WITH TIME ZONE,
    consumed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_conditional_cancel_previews_status_expires_at
    ON conditional_order_cancellation_previews (status, expires_at);

CREATE INDEX idx_conditional_cancel_previews_target
    ON conditional_order_cancellation_previews (account_seq, conditional_order_id);

CREATE TABLE conditional_order_cancellation_executions (
    execution_id VARCHAR(36) PRIMARY KEY,
    preview_id VARCHAR(36) NOT NULL UNIQUE,
    account_seq BIGINT NOT NULL,
    conditional_order_id VARCHAR(512) NOT NULL,
    broker_mode VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_type VARCHAR(32),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_conditional_cancel_execution_preview
        FOREIGN KEY (preview_id) REFERENCES conditional_order_cancellation_previews (preview_id),
    CONSTRAINT uk_conditional_cancel_target
        UNIQUE (account_seq, conditional_order_id)
);

CREATE INDEX idx_conditional_cancel_executions_status_updated_at
    ON conditional_order_cancellation_executions (status, updated_at);

CREATE TABLE order_cancellation_previews (
    preview_id VARCHAR(36) PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    account_seq BIGINT NOT NULL,
    order_id VARCHAR(512) NOT NULL,
    symbol VARCHAR(32) NOT NULL,
    side VARCHAR(8) NOT NULL,
    order_type_code VARCHAR(64) NOT NULL,
    original_status VARCHAR(32) NOT NULL,
    price NUMERIC(65, 18),
    quantity NUMERIC(65, 18),
    filled_quantity NUMERIC(65, 18) NOT NULL,
    remaining_quantity NUMERIC(65, 18),
    order_amount NUMERIC(65, 18),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    approved_at TIMESTAMP WITH TIME ZONE,
    consumed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_order_cancellation_previews_status_expires_at
    ON order_cancellation_previews (status, expires_at);

CREATE TABLE order_cancellation_executions (
    execution_id VARCHAR(36) PRIMARY KEY,
    preview_id VARCHAR(36) NOT NULL UNIQUE,
    order_id VARCHAR(512) NOT NULL UNIQUE,
    broker_mode VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_type VARCHAR(32),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_order_cancellation_execution_preview
        FOREIGN KEY (preview_id) REFERENCES order_cancellation_previews (preview_id)
);

CREATE INDEX idx_order_cancellation_executions_status_updated_at
    ON order_cancellation_executions (status, updated_at);

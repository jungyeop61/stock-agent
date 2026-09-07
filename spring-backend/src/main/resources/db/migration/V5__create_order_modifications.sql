ALTER TABLE order_cancellation_executions
    ADD COLUMN operation_order_id VARCHAR(512);

CREATE TABLE order_modification_previews (
    preview_id VARCHAR(36) PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    account_seq BIGINT NOT NULL,
    original_order_id VARCHAR(512) NOT NULL,
    symbol VARCHAR(32) NOT NULL,
    side VARCHAR(8) NOT NULL,
    original_order_type_code VARCHAR(64) NOT NULL,
    original_time_in_force_code VARCHAR(64) NOT NULL,
    original_price NUMERIC(65, 18),
    original_quantity NUMERIC(65, 18),
    original_order_amount NUMERIC(65, 18),
    filled_quantity NUMERIC(65, 18) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    requested_order_type VARCHAR(16) NOT NULL,
    requested_quantity NUMERIC(65, 18),
    requested_price NUMERIC(65, 18),
    reference_price NUMERIC(65, 18),
    estimated_order_amount NUMERIC(65, 18),
    requires_high_value_confirmation BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL,
    approved_at TIMESTAMP WITH TIME ZONE,
    consumed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_order_modification_previews_status_expires_at
    ON order_modification_previews (status, expires_at);

CREATE TABLE order_modification_executions (
    execution_id VARCHAR(36) PRIMARY KEY,
    preview_id VARCHAR(36) NOT NULL UNIQUE,
    original_order_id VARCHAR(512) NOT NULL UNIQUE,
    operation_order_id VARCHAR(512),
    broker_mode VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_type VARCHAR(32),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_order_modification_execution_preview
        FOREIGN KEY (preview_id) REFERENCES order_modification_previews (preview_id)
);

CREATE INDEX idx_order_modification_executions_status_updated_at
    ON order_modification_executions (status, updated_at);

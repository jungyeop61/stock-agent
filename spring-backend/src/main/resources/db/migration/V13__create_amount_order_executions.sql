CREATE TABLE amount_order_executions (
    execution_id VARCHAR(36) PRIMARY KEY,
    preview_id VARCHAR(36) NOT NULL UNIQUE,
    client_order_id VARCHAR(36) NOT NULL UNIQUE,
    broker_mode VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    broker_order_id VARCHAR(128),
    failure_type VARCHAR(32),
    request_fingerprint VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_amount_order_executions_preview
        FOREIGN KEY (preview_id) REFERENCES amount_order_previews (preview_id)
);

CREATE INDEX idx_amount_order_executions_status_updated_at
    ON amount_order_executions (status, updated_at);

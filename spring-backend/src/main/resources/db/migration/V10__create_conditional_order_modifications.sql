CREATE TABLE conditional_order_modification_previews (
    preview_id VARCHAR(36) PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    account_seq BIGINT NOT NULL,
    original_conditional_order_id VARCHAR(512) NOT NULL,
    original_type VARCHAR(16) NOT NULL,
    original_status VARCHAR(32) NOT NULL,
    symbol VARCHAR(32) NOT NULL,
    market VARCHAR(8) NOT NULL,
    original_quantity NUMERIC(65, 18) NOT NULL,
    original_order_type VARCHAR(8) NOT NULL,
    original_expire_date DATE,
    original_first_type VARCHAR(32) NOT NULL,
    original_first_status VARCHAR(32) NOT NULL,
    original_first_trigger_price NUMERIC(65, 18),
    original_first_target_profit_rate NUMERIC(65, 18),
    original_first_order_price NUMERIC(65, 18),
    original_first_triggered_order_id VARCHAR(512),
    original_second_type VARCHAR(32),
    original_second_status VARCHAR(32),
    original_second_trigger_price NUMERIC(65, 18),
    original_second_target_profit_rate NUMERIC(65, 18),
    original_second_order_price NUMERIC(65, 18),
    original_second_triggered_order_id VARCHAR(512),
    original_created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    requested_type VARCHAR(16) NOT NULL,
    requested_quantity NUMERIC(65, 18) NOT NULL,
    requested_order_type VARCHAR(8) NOT NULL,
    requested_expire_date DATE NOT NULL,
    requested_first_side VARCHAR(8) NOT NULL,
    requested_first_trigger_price NUMERIC(65, 18) NOT NULL,
    requested_first_order_price NUMERIC(65, 18),
    requested_second_side VARCHAR(8),
    requested_second_trigger_price NUMERIC(65, 18),
    requested_second_order_price NUMERIC(65, 18),
    reference_price NUMERIC(65, 18) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    requires_high_value_confirmation BOOLEAN NOT NULL,
    status VARCHAR(32) NOT NULL,
    approved_at TIMESTAMP WITH TIME ZONE,
    consumed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_conditional_modify_previews_status_expires_at
    ON conditional_order_modification_previews (status, expires_at);

CREATE INDEX idx_conditional_modify_previews_target
    ON conditional_order_modification_previews (account_seq, original_conditional_order_id);

CREATE TABLE conditional_order_modification_executions (
    execution_id VARCHAR(36) PRIMARY KEY,
    preview_id VARCHAR(36) NOT NULL UNIQUE,
    account_seq BIGINT NOT NULL,
    original_conditional_order_id VARCHAR(512) NOT NULL,
    replacement_conditional_order_id VARCHAR(512),
    broker_mode VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_type VARCHAR(32),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    submitted_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_conditional_modify_execution_preview
        FOREIGN KEY (preview_id) REFERENCES conditional_order_modification_previews (preview_id),
    CONSTRAINT uk_conditional_modify_target
        UNIQUE (account_seq, original_conditional_order_id)
);

CREATE INDEX idx_conditional_modify_executions_status_updated_at
    ON conditional_order_modification_executions (status, updated_at);
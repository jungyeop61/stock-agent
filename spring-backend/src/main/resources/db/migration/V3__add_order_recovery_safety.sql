ALTER TABLE order_executions
    ADD COLUMN request_fingerprint VARCHAR(64);

ALTER TABLE order_executions
    ADD COLUMN recovery_attempted_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE amount_order_previews
    ADD COLUMN approved_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE amount_order_previews
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

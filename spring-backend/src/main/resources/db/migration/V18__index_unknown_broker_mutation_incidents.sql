CREATE INDEX idx_broker_mutation_audit_unknown_incident
    ON broker_mutation_audit_events (stage, outcome, audit_event_id);

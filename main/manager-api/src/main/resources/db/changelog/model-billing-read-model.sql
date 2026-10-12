-- Old sync runs have no provable account identity; keep their account_ref unknown.
ALTER TABLE ai_billing_sync_run ADD COLUMN account_ref varchar(128) NULL;
CREATE INDEX idx_billing_sync_account_period ON ai_billing_sync_run (provider, account_ref, bill_period, started_at);
CREATE INDEX idx_billing_bill_account_period ON ai_billing_bill (provider, account_ref, bill_period, level);

CREATE TABLE ai_billing_collector_health (
  instance_id varchar(64) NOT NULL PRIMARY KEY,
  started_at datetime(3) NOT NULL,
  received_at datetime(3) NOT NULL,
  health_json mediumtext NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

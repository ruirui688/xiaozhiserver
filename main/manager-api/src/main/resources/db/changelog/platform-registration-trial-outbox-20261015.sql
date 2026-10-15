-- Only future registrations and explicitly selected operator repairs create intents.
-- This migration never credits or backfills tenant accounts.
CREATE TABLE ai_platform_trial_grant (
  user_id BIGINT NOT NULL PRIMARY KEY,
  policy VARCHAR(64) NOT NULL,
  amount DECIMAL(30,9) NOT NULL,
  status VARCHAR(24) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3) NOT NULL,
  lease_token VARCHAR(36) NULL,
  reason_code VARCHAR(64) NULL,
  requested_by VARCHAR(64) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  KEY idx_platform_trial_due (status,next_attempt_at,user_id),
  KEY idx_platform_trial_status_user (status,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_platform_trial_gate (
  id INT NOT NULL PRIMARY KEY,
  next_scan_at DATETIME(3) NOT NULL,
  issued_users BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO ai_platform_trial_gate(id,next_scan_at,issued_users)
SELECT 1,'2000-01-01',COUNT(*) FROM ai_platform_account WHERE trial_policy IS NOT NULL;

CREATE TABLE ai_platform_trial_repair (
  batch_id VARCHAR(36) NOT NULL,
  user_id BIGINT NOT NULL,
  operator_id VARCHAR(64) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  PRIMARY KEY (batch_id,user_id),
  KEY idx_platform_trial_repair_user (user_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The shared per-operator daily budget must not scan unrelated ledger history.
ALTER TABLE ai_platform_ledger ADD INDEX idx_platform_ledger_operator_day (operator_id,entry_type,created_at);

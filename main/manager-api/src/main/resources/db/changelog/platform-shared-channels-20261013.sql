-- Platform upstream channels are independent of user accounts. No legacy key is enabled automatically.
CREATE TABLE ai_platform_channel (
  id varchar(64) NOT NULL PRIMARY KEY,
  name varchar(100) NOT NULL,
  model_id varchar(64) NOT NULL,
  provider varchar(32) NOT NULL,
  service_type varchar(16) NOT NULL,
  account_ref varchar(200) NOT NULL,
  region varchar(100) NULL,
  project_ref varchar(200) NULL,
  status varchar(16) NOT NULL,
  version bigint NOT NULL DEFAULT 1,
  credential_id varchar(64) NOT NULL,
  max_concurrency int NOT NULL DEFAULT 20,
  created_at datetime(3) NOT NULL,
  updated_at datetime(3) NOT NULL,
  UNIQUE KEY uk_platform_channel_model(model_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ai_platform_channel_credential (
  id varchar(64) NOT NULL PRIMARY KEY,
  channel_id varchar(64) NOT NULL,
  version bigint NOT NULL,
  secret_ciphertext text NOT NULL,
  created_at datetime(3) NOT NULL,
  UNIQUE KEY uk_platform_channel_credential_version(channel_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ai_platform_channel_operation (
  request_id varchar(128) NOT NULL PRIMARY KEY,
  payload_hash char(64) NOT NULL,
  result_json text NOT NULL,
  created_at datetime(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE ai_platform_reservation ADD COLUMN channel_id varchar(64) NULL;
CREATE INDEX idx_platform_reservation_channel ON ai_platform_reservation(channel_id,state,hard_expires_at);
ALTER TABLE ai_billing_usage_event ADD COLUMN reservation_id varchar(64) NULL;
ALTER TABLE ai_billing_usage_event ADD COLUMN channel_id varchar(64) NULL;
ALTER TABLE ai_billing_usage_event ADD COLUMN channel_version bigint NULL;
ALTER TABLE ai_billing_usage_event ADD COLUMN upstream_account_ref varchar(200) NULL;
ALTER TABLE ai_billing_usage_event ADD COLUMN credential_id varchar(64) NULL;
ALTER TABLE ai_billing_usage_event ADD COLUMN credential_version bigint NULL;
CREATE INDEX idx_billing_usage_channel ON ai_billing_usage_event(channel_id,started_at);

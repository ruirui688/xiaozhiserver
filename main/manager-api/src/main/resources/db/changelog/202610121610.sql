CREATE TABLE ai_platform_account (
  user_id bigint NOT NULL PRIMARY KEY,
  granted decimal(30,9) NOT NULL DEFAULT 0,
  spent decimal(30,9) NOT NULL DEFAULT 0,
  reserved decimal(30,9) NOT NULL DEFAULT 0,
  frozen tinyint NOT NULL DEFAULT 0,
  updated_at datetime(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_platform_pool (
  id varchar(32) NOT NULL PRIMARY KEY,
  total decimal(30,9) NOT NULL DEFAULT 0,
  allocated decimal(30,9) NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO ai_platform_pool(id,total,allocated) VALUES ('SERVICE',0,0);

CREATE TABLE ai_platform_ledger (
  id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
  entry_key varchar(180) NOT NULL,
  payload_hash char(64) NOT NULL,
  user_id bigint NULL,
  entry_type varchar(32) NOT NULL,
  amount decimal(30,9) NOT NULL,
  balance_after decimal(30,9) NULL,
  workspace_id varchar(64) NULL,
  reservation_id varchar(64) NULL,
  operator_id varchar(64) NULL,
  reason varchar(500) NOT NULL,
  created_at datetime(3) NOT NULL,
  UNIQUE KEY uk_platform_ledger_entry(entry_key),
  KEY idx_platform_ledger_user(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_platform_credential (
  id varchar(64) NOT NULL PRIMARY KEY,
  provider varchar(32) NOT NULL,
  upstream_id varchar(200) NOT NULL,
  fingerprint char(64) NOT NULL,
  user_id bigint NULL,
  secret_ciphertext text NOT NULL,
  masked_json text NOT NULL,
  version bigint NOT NULL DEFAULT 1,
  status varchar(20) NOT NULL,
  created_at datetime(3) NOT NULL,
  UNIQUE KEY uk_platform_credential_secret(provider,fingerprint),
  UNIQUE KEY uk_platform_credential_upstream(provider,upstream_id),
  KEY idx_platform_credential_inventory(provider,status,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_platform_user_credential (
  user_id bigint NOT NULL,
  provider varchar(32) NOT NULL,
  credential_id varchar(64) NULL,
  PRIMARY KEY(user_id,provider)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_platform_responsibility (
  workspace_id varchar(64) NOT NULL PRIMARY KEY,
  responsible_user_id bigint NOT NULL,
  version bigint NOT NULL DEFAULT 1,
  total_limit decimal(30,9) NULL,
  daily_limit decimal(30,9) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_platform_workspace_usage (
  workspace_id varchar(64) NOT NULL,
  period varchar(16) NOT NULL,
  spent decimal(30,9) NOT NULL DEFAULT 0,
  reserved decimal(30,9) NOT NULL DEFAULT 0,
  PRIMARY KEY(workspace_id,period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_platform_rate (
  id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
  model_id varchar(64) NOT NULL,
  version varchar(64) NOT NULL,
  metric_rates_json text NOT NULL,
  max_usage_json text NOT NULL,
  created_at datetime(3) NOT NULL,
  UNIQUE KEY uk_platform_rate_version(model_id,version),
  KEY idx_platform_rate_model(model_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_platform_reservation (
  id varchar(64) NOT NULL PRIMARY KEY,
  attempt_id varchar(128) NOT NULL,
  logical_call_id varchar(128) NOT NULL,
  request_hash char(64) NOT NULL,
  context_id varchar(64) NULL,
  model_id varchar(64) NOT NULL,
  service_type varchar(32) NOT NULL,
  payer_user_id bigint NOT NULL,
  workspace_id varchar(64) NULL,
  usage_period varchar(16) NOT NULL,
  route_json text NOT NULL,
  runtime_json text NOT NULL,
  credential_id varchar(64) NOT NULL,
  credential_version bigint NOT NULL,
  responsibility_version bigint NOT NULL,
  rate_version varchar(64) NOT NULL,
  metric_rates_json text NOT NULL,
  published_max_json text NOT NULL,
  max_usage_json text NOT NULL,
  reserved_amount decimal(30,9) NOT NULL,
  settled_amount decimal(30,9) NULL,
  state varchar(20) NOT NULL,
  permit_hash char(64) NOT NULL,
  permit_ciphertext text NOT NULL,
  settlement_hash char(64) NULL,
  provider_request_id varchar(255) NULL,
  expires_at datetime(3) NOT NULL,
  hard_expires_at datetime(3) NOT NULL,
  created_at datetime(3) NOT NULL,
  started_at datetime(3) NULL,
  settled_at datetime(3) NULL,
  UNIQUE KEY uk_platform_reservation_attempt(attempt_id),
  KEY idx_platform_reservation_payer(payer_user_id,state),
  KEY idx_platform_reservation_workspace(workspace_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Explicitly unallocated accounts: migration does not grant money or reuse global keys.
INSERT INTO ai_platform_account(user_id,updated_at) SELECT id,UTC_TIMESTAMP(3) FROM sys_user;
INSERT INTO ai_platform_responsibility(workspace_id,responsible_user_id,version)
SELECT w.id,w.owner_user_id,1 FROM sys_workspace w WHERE w.owner_user_id IS NOT NULL;

-- Internal services own isolated budget accounts; negative IDs never identify tenant users.
CREATE TABLE ai_platform_service_account (
  id varchar(64) PRIMARY KEY,
  account_id bigint NOT NULL UNIQUE,
  model_ids_json text NOT NULL,
  purposes_json text NOT NULL,
  status varchar(20) NOT NULL,
  created_at datetime(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ai_platform_credential_fingerprint (
 provider varchar(32) NOT NULL,
 component varchar(32) NOT NULL,
 fingerprint char(64) NOT NULL,
 credential_id varchar(64) NOT NULL,
 PRIMARY KEY(provider,component,fingerprint)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ai_platform_credential_operation (
 request_id varchar(128) PRIMARY KEY,
 payload_hash char(64) NOT NULL,
 result_json text NOT NULL,
 created_at datetime(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ai_platform_resolution (
 request_id varchar(128) PRIMARY KEY,
 reservation_id varchar(64) NOT NULL,
 evidence_ref varchar(500) NOT NULL,
 operator_id varchar(64) NOT NULL,
 payload_hash char(64) NOT NULL,
 created_at datetime(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ai_platform_legacy_secret_archive (
 source_key varchar(200) PRIMARY KEY,
 ciphertext longtext NOT NULL,
 archived_at datetime(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_billing_account_lock (
  id char(64) NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_billing_context (
  id varchar(64) NOT NULL PRIMARY KEY,
  token_hash char(64) NOT NULL,
  workspace_id varchar(64) NULL,
  agent_id varchar(64) NULL,
  device_id varchar(64) NULL,
  purpose varchar(64) NOT NULL,
  snapshot_json mediumtext NOT NULL,
  issued_at datetime(3) NOT NULL,
  KEY idx_billing_context_workspace (workspace_id, issued_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_billing_usage_event (
  event_id varchar(64) NOT NULL PRIMARY KEY,
  context_id varchar(64) NULL,
  payload_hash char(64) NOT NULL,
  workspace_id varchar(64) NULL,
  agent_id varchar(64) NULL,
  agent_name varchar(255) NULL,
  device_id varchar(64) NULL,
  device_name varchar(255) NULL,
  device_board varchar(128) NULL,
  model_id varchar(64) NOT NULL,
  model_name varchar(255) NULL,
  provider varchar(64) NOT NULL,
  service_type varchar(32) NOT NULL,
  purpose varchar(64) NOT NULL,
  session_id varchar(128) NULL,
  turn_id varchar(128) NULL,
  provider_request_id varchar(256) NULL,
  attempt_id varchar(128) NULL,
  logical_call_id varchar(128) NULL,
  sentence_id varchar(128) NULL,
  started_at datetime(3) NOT NULL,
  ended_at datetime(3) NOT NULL,
  status varchar(16) NOT NULL,
  usage_complete tinyint NOT NULL DEFAULT 0,
  received_at datetime(3) NOT NULL,
  KEY idx_billing_event_workspace_time (workspace_id, started_at),
  KEY idx_billing_event_agent_time (workspace_id, agent_id, started_at),
  KEY idx_billing_event_device_time (workspace_id, device_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_billing_usage_metric (
  event_id varchar(64) NOT NULL,
  metric varchar(64) NOT NULL,
  unit varchar(32) NOT NULL,
  source varchar(16) NOT NULL,
  quantity decimal(30,9) NOT NULL,
  PRIMARY KEY (event_id, metric, unit, source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_billing_binding (
  id varchar(64) NOT NULL PRIMARY KEY,
  provider varchar(64) NOT NULL,
  account_ref varchar(128) NOT NULL,
  scope_type varchar(16) NOT NULL,
  scope_value varchar(255) NOT NULL,
  workspace_id varchar(64) NOT NULL,
  agent_id varchar(64) NULL,
  device_id varchar(64) NULL,
  valid_from datetime(3) NOT NULL,
  valid_to datetime(3) NULL,
  note varchar(500) NULL,
  created_by bigint NOT NULL,
  created_at datetime(3) NOT NULL,
  KEY idx_billing_binding_scope (provider, account_ref, scope_type, scope_value(128)),
  KEY idx_billing_binding_workspace (workspace_id, valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_billing_bill (
  id char(64) NOT NULL PRIMARY KEY,
  provider varchar(64) NOT NULL,
  account_ref varchar(128) NOT NULL,
  bill_id varchar(256) NOT NULL,
  bill_detail_id varchar(256) NULL,
  level varchar(16) NOT NULL,
  detail_complete tinyint NOT NULL DEFAULT 0,
  bill_period varchar(7) NOT NULL,
  expense_begin datetime(3) NOT NULL,
  expense_end datetime(3) NOT NULL,
  product varchar(128) NULL,
  service_type varchar(32) NULL,
  model_id varchar(64) NULL,
  project varchar(255) NULL,
  resource_id varchar(255) NULL,
  currency varchar(12) NOT NULL,
  settlement_currency varchar(12) NULL,
  payable_amount decimal(30,9) NULL,
  paid_amount decimal(30,9) NULL,
  unpaid_amount decimal(30,9) NULL,
  coupon_amount decimal(30,9) NULL,
  settlement_type varchar(64) NULL,
  pay_status varchar(64) NULL,
  bill_category varchar(64) NULL,
  workspace_id varchar(64) NULL,
  agent_id varchar(64) NULL,
  device_id varchar(64) NULL,
  binding_id varchar(64) NULL,
  attribution_level varchar(16) NOT NULL,
  source varchar(32) NOT NULL,
  evidence_hash char(64) NOT NULL,
  evidence_json mediumtext NOT NULL,
  synced_at datetime(3) NOT NULL,
  KEY idx_billing_bill_workspace_time (workspace_id, expense_begin),
  KEY idx_billing_bill_parent (provider, account_ref, bill_id(128), level),
  KEY idx_billing_bill_binding (binding_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_billing_sync_run (
  id varchar(64) NOT NULL PRIMARY KEY,
  provider varchar(64) NOT NULL,
  bill_period varchar(7) NULL,
  status varchar(16) NOT NULL,
  imported_count int NOT NULL DEFAULT 0,
  error_code varchar(64) NULL,
  created_by bigint NOT NULL,
  started_at datetime(3) NOT NULL,
  finished_at datetime(3) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

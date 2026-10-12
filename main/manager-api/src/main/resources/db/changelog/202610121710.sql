-- User-initiated allowance requests. Users cannot top up, so an exhausted account
-- needs an explicit path to ask the operator. The request is intent only: it never
-- changes a balance, and the credited amount is always decided by the operator and
-- recorded in ai_platform_ledger.
CREATE TABLE ai_platform_allowance_request (
  id varchar(64) NOT NULL PRIMARY KEY,
  user_id bigint NOT NULL,
  workspace_id varchar(64) NULL,
  request_key varchar(180) NOT NULL,
  requested_amount decimal(30,9) NULL,
  reason varchar(500) NOT NULL,
  status varchar(20) NOT NULL,
  operator_id varchar(64) NULL,
  granted_amount decimal(30,9) NULL,
  decision_reason varchar(500) NULL,
  created_at datetime(3) NOT NULL,
  decided_at datetime(3) NULL,
  UNIQUE KEY uk_allowance_request_key(request_key),
  KEY idx_allowance_request_user(user_id,id),
  KEY idx_allowance_request_status(status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

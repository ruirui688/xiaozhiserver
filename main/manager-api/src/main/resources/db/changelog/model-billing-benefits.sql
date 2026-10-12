CREATE TABLE ai_billing_benefit_snapshot (
  provider varchar(64) NOT NULL,
  account_ref varchar(128) NOT NULL,
  data_json mediumtext NULL,
  status varchar(32) NOT NULL,
  synced_at datetime(3) NULL,
  attempted_at datetime(3) NOT NULL,
  PRIMARY KEY (provider, account_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

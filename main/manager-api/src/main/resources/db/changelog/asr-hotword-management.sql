CREATE TABLE IF NOT EXISTS ai_asr_hotword (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    workspace_id VARCHAR(64) NOT NULL,
    name VARCHAR(80) NOT NULL,
    model_id VARCHAR(64) NOT NULL,
    entries_json JSON NOT NULL,
    remote_id VARCHAR(128) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    last_error VARCHAR(255) NOT NULL DEFAULT '',
    operation_token VARCHAR(32) NULL,
    credential_fingerprint CHAR(64) NULL,
    update_date DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    create_date DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_hotword_workspace (workspace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS ai_asr_hotword_revision (
    remote_name VARCHAR(80) NOT NULL PRIMARY KEY,
    hotword_id VARCHAR(32) NOT NULL,
    remote_id VARCHAR(128) NULL,
    KEY idx_hotword_revision_owner (hotword_id),
    KEY idx_hotword_revision_remote (remote_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

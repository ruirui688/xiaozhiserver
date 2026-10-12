-- Employees and their sessions are independent of tenant users. No automatic legacy role mapping.
ALTER TABLE sys_user_token ADD COLUMN audience VARCHAR(16) NOT NULL DEFAULT 'USER';
UPDATE sys_user_token SET expire_date = CURRENT_TIMESTAMP;
CREATE TABLE ops_employee (
 id VARCHAR(36) PRIMARY KEY, username VARCHAR(100) NOT NULL UNIQUE,
 password_hash VARCHAR(255) NOT NULL, mfa_cipher TEXT NOT NULL, recovery_hash CHAR(64) NULL,
 mfa_last_step BIGINT NOT NULL DEFAULT -1, status INT NOT NULL DEFAULT 1,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE ops_session (
 id VARCHAR(36) PRIMARY KEY, employee_id VARCHAR(36) NOT NULL,
 token_hash CHAR(64) NOT NULL UNIQUE, expires_at TIMESTAMP NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX idx_ops_session_employee(employee_id)
);
CREATE TABLE ops_role_grant (
 id VARCHAR(36) PRIMARY KEY, employee_id VARCHAR(36) NOT NULL,
 role_code VARCHAR(64) NOT NULL, scope_type VARCHAR(32) NOT NULL, scope_id VARCHAR(100) NOT NULL,
 expires_at TIMESTAMP NULL, status INT NOT NULL DEFAULT 1,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX idx_ops_grant_employee(employee_id)
);
CREATE TABLE ops_delegation (
 id VARCHAR(36) PRIMARY KEY, employee_id VARCHAR(36) NOT NULL,
 role_code VARCHAR(64) NOT NULL, scope_type VARCHAR(32) NOT NULL, scope_id VARCHAR(100) NOT NULL,
 expires_at TIMESTAMP NULL, status INT NOT NULL DEFAULT 1,
 UNIQUE KEY uk_ops_delegation(employee_id,role_code,scope_type,scope_id)
);
CREATE TABLE ops_grant_request (
 id VARCHAR(36) PRIMARY KEY, target_employee_id VARCHAR(36) NOT NULL,
 requester_id VARCHAR(36) NOT NULL, approver_id VARCHAR(36) NULL,
 role_code VARCHAR(64) NOT NULL, scope_type VARCHAR(32) NOT NULL, scope_id VARCHAR(100) NOT NULL,
 grant_expires_at TIMESTAMP NOT NULL, expires_at TIMESTAMP NOT NULL,
 status VARCHAR(20) NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE ops_audit_event (
 id VARCHAR(36) PRIMARY KEY, actor_id VARCHAR(36) NULL, action VARCHAR(80) NOT NULL,
 target_id VARCHAR(100) NOT NULL, outcome VARCHAR(32) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE ops_login_limit (
 key_hash CHAR(64) PRIMARY KEY, window_start TIMESTAMP NOT NULL, attempts INT NOT NULL
);
CREATE TABLE ops_bootstrap_state (
 id INT PRIMARY KEY, consumed INT NOT NULL DEFAULT 0, manifest_hash CHAR(64) NULL,
 consumed_at TIMESTAMP NULL
);
INSERT INTO ops_bootstrap_state(id,consumed) VALUES(1,0);
CREATE TABLE ops_identity_change (
 id VARCHAR(36) PRIMARY KEY, employee_id VARCHAR(36) NOT NULL, kind VARCHAR(16) NOT NULL,
 password_hash VARCHAR(255) NULL, mfa_cipher TEXT NULL, mfa_last_step BIGINT NOT NULL,
 recovery_hash CHAR(64) NULL, requester_id VARCHAR(36) NULL, approver_id VARCHAR(36) NULL,
 status VARCHAR(20) NOT NULL, expires_at TIMESTAMP NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE ops_operation_approval (
 id VARCHAR(36) PRIMARY KEY, requester_id VARCHAR(36) NOT NULL, approver_id VARCHAR(36) NULL,
 action VARCHAR(80) NOT NULL, target_id VARCHAR(100) NOT NULL, target_version VARCHAR(100) NOT NULL,
 payload_hash CHAR(64) NOT NULL, payload_json LONGTEXT NOT NULL,
 status VARCHAR(20) NOT NULL, expires_at TIMESTAMP NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, consumed_at TIMESTAMP NULL
);
CREATE TABLE ops_ota_channel (
 hardware_type VARCHAR(100) PRIMARY KEY, ota_id VARCHAR(36) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- Preserve historical duplicate versions; uniqueness applies to new approved publications only.
CREATE TABLE ops_ota_release (
 hardware_type VARCHAR(100) NOT NULL, version VARCHAR(100) NOT NULL,
 ota_id VARCHAR(36) NOT NULL, PRIMARY KEY(hardware_type,version)
);
ALTER TABLE ai_rag_dataset ADD COLUMN publication_version BIGINT NOT NULL DEFAULT 1;

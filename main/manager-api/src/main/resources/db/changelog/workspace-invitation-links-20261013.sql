-- 工作空间邀请令牌：LINK 邀请不绑定既有账号；所有原始 token 只在创建响应中返回。
-- MySQL DDL 会隐式提交，因此每一步都必须可重入，避免 Liquibase 尚未登记 changeSet 时留下半迁移状态。
ALTER TABLE `sys_workspace_invitation`
  MODIFY COLUMN `invitee_username` varchar(50) DEFAULT NULL COMMENT '定向邀请用户名；链接邀请领取前为空',
  MODIFY COLUMN `code` varchar(64) DEFAULT NULL COMMENT '历史兼容列；迁移后不保存原始 token';

SET @column_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'sys_workspace_invitation'
    AND COLUMN_NAME = 'invitation_type'
);
SET @ddl = IF(
  @column_exists = 0,
  'ALTER TABLE `sys_workspace_invitation` ADD COLUMN `invitation_type` varchar(16) NOT NULL DEFAULT ''TARGETED'' COMMENT ''邀请类型 TARGETED/LINK'' AFTER `invitee_user_id`',
  'SELECT ''sys_workspace_invitation.invitation_type exists'''
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'sys_workspace_invitation'
    AND COLUMN_NAME = 'token_hash'
);
SET @ddl = IF(
  @column_exists = 0,
  'ALTER TABLE `sys_workspace_invitation` ADD COLUMN `token_hash` char(64) DEFAULT NULL COMMENT ''邀请 token 的 SHA-256 摘要'' AFTER `code`',
  'SELECT ''sys_workspace_invitation.token_hash exists'''
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @column_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'sys_workspace_invitation'
    AND COLUMN_NAME = 'accepted_by_user_id'
);
SET @ddl = IF(
  @column_exists = 0,
  'ALTER TABLE `sys_workspace_invitation` ADD COLUMN `accepted_by_user_id` bigint DEFAULT NULL COMMENT ''实际领取用户ID'' AFTER `accepted_at`',
  'SELECT ''sys_workspace_invitation.accepted_by_user_id exists'''
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE `sys_workspace_invitation`
SET `invitation_type` = 'TARGETED',
    `token_hash` = LOWER(SHA2(`code`, 256))
WHERE `token_hash` IS NULL
  AND `code` IS NOT NULL;

UPDATE `sys_workspace_invitation`
SET `code` = NULL
WHERE `code` IS NOT NULL
  AND `token_hash` IS NOT NULL;

ALTER TABLE `sys_workspace_invitation`
  MODIFY COLUMN `token_hash` char(64) NOT NULL
    COMMENT '邀请 token 的 SHA-256 摘要';

SET @index_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'sys_workspace_invitation'
    AND INDEX_NAME = 'uk_workspace_invitation_token_hash'
);
SET @ddl = IF(
  @index_exists = 0,
  'CREATE UNIQUE INDEX `uk_workspace_invitation_token_hash` ON `sys_workspace_invitation` (`token_hash`)',
  'SELECT ''uk_workspace_invitation_token_hash exists'''
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'sys_workspace_invitation'
    AND INDEX_NAME = 'idx_workspace_invitation_status_expiry'
);
SET @ddl = IF(
  @index_exists = 0,
  'CREATE INDEX `idx_workspace_invitation_status_expiry` ON `sys_workspace_invitation` (`workspace_id`, `status`, `expires_at`)',
  'SELECT ''idx_workspace_invitation_status_expiry exists'''
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

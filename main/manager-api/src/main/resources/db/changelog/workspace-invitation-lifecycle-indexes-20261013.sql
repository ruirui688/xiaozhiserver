-- 账号停用/注销会在持有用户行锁时发现并撤销相关邀请。
-- 独立 changeSet 避免改动可能已在本地试跑的邀请链接基础迁移。
SET @index_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'sys_workspace_invitation'
    AND INDEX_NAME = 'idx_workspace_invitation_creator_status_workspace'
);
SET @ddl = IF(
  @index_exists = 0,
  'CREATE INDEX `idx_workspace_invitation_creator_status_workspace` ON `sys_workspace_invitation` (`creator`, `status`, `workspace_id`)',
  'SELECT ''idx_workspace_invitation_creator_status_workspace exists'''
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @index_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'sys_workspace_invitation'
    AND INDEX_NAME = 'idx_workspace_invitation_invitee_type_status_workspace'
);
SET @ddl = IF(
  @index_exists = 0,
  'CREATE INDEX `idx_workspace_invitation_invitee_type_status_workspace` ON `sys_workspace_invitation` (`invitee_user_id`, `invitation_type`, `status`, `workspace_id`)',
  'SELECT ''idx_workspace_invitation_invitee_type_status_workspace exists'''
);
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS `sys_workspace` (
  `id` varchar(32) NOT NULL COMMENT '工作空间ID',
  `name` varchar(64) NOT NULL COMMENT '工作空间名称',
  `owner_user_id` bigint NOT NULL COMMENT '所有者用户ID',
  `default_workspace` tinyint unsigned NOT NULL DEFAULT 0 COMMENT '是否默认工作空间',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态 0停用 1正常',
  `creator` bigint DEFAULT NULL COMMENT '创建者',
  `created_at` datetime DEFAULT NULL COMMENT '创建时间',
  `updater` bigint DEFAULT NULL COMMENT '更新者',
  `updated_at` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_sys_workspace_owner` (`owner_user_id`),
  KEY `idx_sys_workspace_default` (`owner_user_id`, `default_workspace`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织工作空间';

CREATE TABLE IF NOT EXISTS `sys_workspace_member` (
  `id` varchar(32) NOT NULL COMMENT '成员关系ID',
  `workspace_id` varchar(32) NOT NULL COMMENT '工作空间ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `role` varchar(16) NOT NULL COMMENT '角色 OWNER/ADMIN/MEMBER',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态 0禁用 1正常',
  `creator` bigint DEFAULT NULL COMMENT '创建者',
  `created_at` datetime DEFAULT NULL COMMENT '创建时间',
  `updater` bigint DEFAULT NULL COMMENT '更新者',
  `updated_at` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workspace_member_user` (`workspace_id`, `user_id`),
  KEY `idx_workspace_member_user` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作空间成员';

CREATE TABLE IF NOT EXISTS `sys_workspace_invitation` (
  `id` varchar(32) NOT NULL COMMENT '邀请ID',
  `workspace_id` varchar(32) NOT NULL COMMENT '工作空间ID',
  `invitee_username` varchar(50) NOT NULL COMMENT '被邀请用户名',
  `invitee_user_id` bigint DEFAULT NULL COMMENT '被邀请用户ID',
  `role` varchar(16) NOT NULL COMMENT '邀请角色 ADMIN/MEMBER',
  `code` varchar(64) NOT NULL COMMENT '邀请码',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态 PENDING/ACCEPTED/REVOKED/EXPIRED',
  `expires_at` datetime NOT NULL COMMENT '过期时间',
  `accepted_at` datetime DEFAULT NULL COMMENT '接受时间',
  `creator` bigint DEFAULT NULL COMMENT '创建者',
  `created_at` datetime DEFAULT NULL COMMENT '创建时间',
  `updater` bigint DEFAULT NULL COMMENT '更新者',
  `updated_at` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_workspace_invitation_code` (`code`),
  KEY `idx_workspace_invitation_workspace` (`workspace_id`, `status`),
  KEY `idx_workspace_invitation_user` (`invitee_username`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作空间邀请';

SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent' AND COLUMN_NAME = 'workspace_id');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE `ai_agent` ADD COLUMN `workspace_id` varchar(32) DEFAULT NULL COMMENT ''所属工作空间ID'', ADD KEY `idx_ai_agent_workspace_id` (`workspace_id`)', 'SELECT ''ai_agent.workspace_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_device' AND COLUMN_NAME = 'workspace_id');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE `ai_device` ADD COLUMN `workspace_id` varchar(32) DEFAULT NULL COMMENT ''所属工作空间ID'', ADD KEY `idx_ai_device_workspace_agent` (`workspace_id`, `agent_id`)', 'SELECT ''ai_device.workspace_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_rag_dataset' AND COLUMN_NAME = 'workspace_id');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE `ai_rag_dataset` ADD COLUMN `workspace_id` varchar(32) DEFAULT NULL COMMENT ''所属工作空间ID'', ADD KEY `idx_ai_rag_dataset_workspace` (`workspace_id`)', 'SELECT ''ai_rag_dataset.workspace_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_rag_knowledge_document' AND COLUMN_NAME = 'workspace_id');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE `ai_rag_knowledge_document` ADD COLUMN `workspace_id` varchar(32) DEFAULT NULL COMMENT ''所属工作空间ID'', ADD KEY `idx_ai_rag_document_workspace` (`workspace_id`, `dataset_id`)', 'SELECT ''ai_rag_knowledge_document.workspace_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_rag_knowledge_chunk' AND COLUMN_NAME = 'workspace_id');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE `ai_rag_knowledge_chunk` ADD COLUMN `workspace_id` varchar(32) DEFAULT NULL COMMENT ''所属工作空间ID'', ADD KEY `idx_ai_rag_chunk_workspace` (`workspace_id`, `dataset_id`)', 'SELECT ''ai_rag_knowledge_chunk.workspace_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_chat_history' AND COLUMN_NAME = 'workspace_id');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE `ai_agent_chat_history` ADD COLUMN `workspace_id` varchar(32) DEFAULT NULL COMMENT ''所属工作空间ID'', ADD KEY `idx_ai_agent_chat_history_workspace` (`workspace_id`, `agent_id`)', 'SELECT ''ai_agent_chat_history.workspace_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_memory_fact' AND COLUMN_NAME = 'workspace_id');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE `ai_memory_fact` ADD COLUMN `workspace_id` varchar(32) DEFAULT NULL COMMENT ''所属工作空间ID'', ADD KEY `idx_ai_memory_fact_workspace` (`workspace_id`, `agent_id`)', 'SELECT ''ai_memory_fact.workspace_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_memory_candidate' AND COLUMN_NAME = 'workspace_id');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE `ai_memory_candidate` ADD COLUMN `workspace_id` varchar(32) DEFAULT NULL COMMENT ''所属工作空间ID'', ADD KEY `idx_ai_memory_candidate_workspace` (`workspace_id`, `agent_id`)', 'SELECT ''ai_memory_candidate.workspace_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_memory_source_event' AND COLUMN_NAME = 'workspace_id');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE `ai_memory_source_event` ADD COLUMN `workspace_id` varchar(32) DEFAULT NULL COMMENT ''所属工作空间ID'', ADD KEY `idx_ai_memory_source_workspace` (`workspace_id`, `agent_id`)', 'SELECT ''ai_memory_source_event.workspace_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO `sys_workspace` (`id`, `name`, `owner_user_id`, `default_workspace`, `status`, `creator`, `created_at`, `updater`, `updated_at`)
SELECT MD5(CONCAT('default-workspace-', u.id)), CONCAT(u.username, '的工作空间'), u.id, 1, 1, u.id, NOW(), u.id, NOW()
FROM `sys_user` u
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_workspace` w WHERE w.owner_user_id = u.id AND w.default_workspace = 1
);

INSERT INTO `sys_workspace_member` (`id`, `workspace_id`, `user_id`, `role`, `status`, `creator`, `created_at`, `updater`, `updated_at`)
SELECT MD5(CONCAT('default-workspace-member-', u.id)), w.id, u.id, 'OWNER', 1, u.id, NOW(), u.id, NOW()
FROM `sys_user` u
JOIN `sys_workspace` w ON w.owner_user_id = u.id AND w.default_workspace = 1
WHERE NOT EXISTS (
  SELECT 1 FROM `sys_workspace_member` m WHERE m.workspace_id = w.id AND m.user_id = u.id
);

UPDATE `ai_agent` a
JOIN `sys_workspace` w ON w.owner_user_id = a.user_id AND w.default_workspace = 1
SET a.workspace_id = w.id
WHERE a.workspace_id IS NULL;

UPDATE `ai_device` d
JOIN `sys_workspace` w ON w.owner_user_id = d.user_id AND w.default_workspace = 1
SET d.workspace_id = w.id
WHERE d.workspace_id IS NULL;

UPDATE `ai_rag_dataset` k
JOIN `sys_workspace` w ON w.owner_user_id = k.creator AND w.default_workspace = 1
SET k.workspace_id = w.id
WHERE k.workspace_id IS NULL;

UPDATE `ai_rag_knowledge_document` doc
JOIN `ai_rag_dataset` k ON k.dataset_id = doc.dataset_id
SET doc.workspace_id = k.workspace_id
WHERE doc.workspace_id IS NULL AND k.workspace_id IS NOT NULL;

UPDATE `ai_rag_knowledge_chunk` c
JOIN `ai_rag_dataset` k ON k.dataset_id = c.dataset_id
SET c.workspace_id = k.workspace_id
WHERE c.workspace_id IS NULL AND k.workspace_id IS NOT NULL;

UPDATE `ai_agent_chat_history` h
JOIN `ai_agent` a ON a.id = h.agent_id
SET h.workspace_id = a.workspace_id
WHERE h.workspace_id IS NULL AND a.workspace_id IS NOT NULL;

UPDATE `ai_memory_fact` m JOIN `ai_agent` a ON a.id = m.agent_id SET m.workspace_id = a.workspace_id WHERE m.workspace_id IS NULL AND a.workspace_id IS NOT NULL;
UPDATE `ai_memory_candidate` m JOIN `ai_agent` a ON a.id = m.agent_id SET m.workspace_id = a.workspace_id WHERE m.workspace_id IS NULL AND a.workspace_id IS NOT NULL;
UPDATE `ai_memory_source_event` m JOIN `ai_agent` a ON a.id = m.agent_id SET m.workspace_id = a.workspace_id WHERE m.workspace_id IS NULL AND a.workspace_id IS NOT NULL;

SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_memory_profile' AND COLUMN_NAME = 'workspace_id');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE `ai_memory_profile` ADD COLUMN `workspace_id` varchar(32) DEFAULT NULL COMMENT ''所属工作空间ID'', ADD KEY `idx_ai_memory_profile_workspace` (`workspace_id`, `agent_id`)', 'SELECT ''ai_memory_profile.workspace_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_memory_tool_behavior' AND COLUMN_NAME = 'workspace_id');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE `ai_memory_tool_behavior` ADD COLUMN `workspace_id` varchar(32) DEFAULT NULL COMMENT ''所属工作空间ID'', ADD KEY `idx_ai_memory_tool_workspace` (`workspace_id`, `agent_id`)', 'SELECT ''ai_memory_tool_behavior.workspace_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_memory_forget_tombstone' AND COLUMN_NAME = 'workspace_id');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE `ai_memory_forget_tombstone` ADD COLUMN `workspace_id` varchar(32) DEFAULT NULL COMMENT ''所属工作空间ID'', ADD KEY `idx_ai_memory_tombstone_workspace` (`workspace_id`, `agent_id`)', 'SELECT ''ai_memory_forget_tombstone.workspace_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_memory_revision' AND COLUMN_NAME = 'workspace_id');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE `ai_memory_revision` ADD COLUMN `workspace_id` varchar(32) DEFAULT NULL COMMENT ''所属工作空间ID'', ADD KEY `idx_ai_memory_revision_workspace` (`workspace_id`, `agent_id`)', 'SELECT ''ai_memory_revision.workspace_id exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE `ai_memory_profile` m JOIN `ai_agent` a ON a.id = m.agent_id SET m.workspace_id = a.workspace_id WHERE m.workspace_id IS NULL AND a.workspace_id IS NOT NULL;
UPDATE `ai_memory_tool_behavior` m JOIN `ai_agent` a ON a.id = m.agent_id SET m.workspace_id = a.workspace_id WHERE m.workspace_id IS NULL AND a.workspace_id IS NOT NULL;
UPDATE `ai_memory_forget_tombstone` m JOIN `ai_agent` a ON a.id = m.agent_id SET m.workspace_id = a.workspace_id WHERE m.workspace_id IS NULL AND a.workspace_id IS NOT NULL;
UPDATE `ai_memory_revision` m JOIN `ai_agent` a ON a.id = m.agent_id SET m.workspace_id = a.workspace_id WHERE m.workspace_id IS NULL AND a.workspace_id IS NOT NULL;

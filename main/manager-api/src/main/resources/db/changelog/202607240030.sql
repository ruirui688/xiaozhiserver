SET @workspace_agent_limit_param_id = (
    SELECT COALESCE(MAX(`id`), 0) + 1 FROM `sys_params`
);

INSERT INTO `sys_params` (`id`, `param_code`, `param_value`, `value_type`, `param_type`, `remark`)
SELECT @workspace_agent_limit_param_id,
       'workspace.agent_max_count',
       '10',
       'number',
       1,
       '每个工作空间允许创建的智能体数量上限，必须为正整数'
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_params` WHERE `param_code` = 'workspace.agent_max_count'
);

SET @agent_storage_limit_param_id = (
    SELECT COALESCE(MAX(`id`), 0) + 1 FROM `sys_params`
);

INSERT INTO `sys_params` (`id`, `param_code`, `param_value`, `value_type`, `param_type`, `remark`)
SELECT @agent_storage_limit_param_id,
       'workspace.agent_storage_limit_mb',
       '200',
       'number',
       1,
       '工作空间内每个智能体的聊天记录存储上限，单位MB；达到上限后自动删除最旧记录，必须为正整数'
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_params` WHERE `param_code` = 'workspace.agent_storage_limit_mb'
);

SET @storage_bytes_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_agent_chat_history'
      AND COLUMN_NAME = 'storage_bytes'
);
SET @storage_bytes_sql = IF(
    @storage_bytes_exists = 0,
    'ALTER TABLE `ai_agent_chat_history` ADD COLUMN `storage_bytes` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''本条聊天记录占用字节数（文本、指标和音频）'' AFTER `metrics_json`',
    'SELECT ''ai_agent_chat_history.storage_bytes exists'''
);
PREPARE stmt FROM @storage_bytes_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE `ai_agent_chat_history` history
LEFT JOIN `ai_agent_chat_audio` audio ON audio.id = history.audio_id
SET history.storage_bytes =
      COALESCE(OCTET_LENGTH(history.content), 0)
    + COALESCE(OCTET_LENGTH(history.metrics_json), 0)
    + COALESCE(OCTET_LENGTH(audio.audio), 0)
WHERE history.storage_bytes = 0;

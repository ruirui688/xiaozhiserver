SET @col_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ai_agent_chat_history'
    AND COLUMN_NAME = 'metrics_json'
);
SET @sql = IF(
  @col_exists = 0,
  'ALTER TABLE `ai_agent_chat_history` ADD COLUMN `metrics_json` MEDIUMTEXT DEFAULT NULL COMMENT ''链路延时、token 和运行指标 JSON'' AFTER `audio_id`',
  'SELECT ''ai_agent_chat_history.metrics_json exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

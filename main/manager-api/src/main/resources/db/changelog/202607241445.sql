SET @column_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_agent'
      AND COLUMN_NAME = 'asr_hotword_id'
);

SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE `ai_agent` ADD COLUMN `asr_hotword_id` VARCHAR(128) NULL COMMENT ''火山引擎 ASR 热词表 ID'' AFTER `asr_model_id`',
    'SELECT ''ai_agent.asr_hotword_id exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

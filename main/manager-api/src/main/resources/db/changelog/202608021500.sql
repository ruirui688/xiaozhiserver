SET @column_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_agent'
      AND COLUMN_NAME = 'asr_hotword_ids'
);

SET @sql = IF(
    @column_exists = 0,
    'ALTER TABLE `ai_agent` ADD COLUMN `asr_hotword_ids` JSON NULL COMMENT ''火山引擎 ASR 热词表候选 ID 列表'' AFTER `asr_hotword_id`',
    'SELECT ''ai_agent.asr_hotword_ids exists'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE `ai_agent`
SET `asr_hotword_ids` = JSON_ARRAY(TRIM(`asr_hotword_id`))
WHERE `asr_hotword_ids` IS NULL
  AND `asr_hotword_id` IS NOT NULL
  AND TRIM(`asr_hotword_id`) <> '';

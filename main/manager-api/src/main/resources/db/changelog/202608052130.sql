SET @realtime_voice_exists = (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ai_agent'
    AND COLUMN_NAME = 'realtime_voice_id'
);

SET @realtime_voice_sql = IF(
  @realtime_voice_exists = 0,
  'ALTER TABLE `ai_agent` ADD COLUMN `realtime_voice_id` VARCHAR(128) NULL COMMENT ''端到端实时会话音色覆盖'' AFTER `tts_voice_id`',
  'SELECT ''ai_agent.realtime_voice_id exists'''
);

PREPARE realtime_voice_stmt FROM @realtime_voice_sql;
EXECUTE realtime_voice_stmt;
DEALLOCATE PREPARE realtime_voice_stmt;

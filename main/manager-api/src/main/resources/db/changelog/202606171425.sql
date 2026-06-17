-- 智能体模板表补齐小模型ID字段，保证后续清空默认模型绑定的迁移兼容旧库
SET @col_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_agent_template'
      AND COLUMN_NAME = 'slm_model_id'
);
SET @sql = IF(
    @col_exists = 0,
    'ALTER TABLE `ai_agent_template` ADD COLUMN `slm_model_id` VARCHAR(255) NULL COMMENT ''小模型ID'' AFTER `llm_model_id`',
    'SELECT ''Column slm_model_id already exists'' AS msg'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

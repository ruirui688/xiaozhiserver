-- 模型模板字段补充运行时映射和前端显示元数据。
SET @col_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_model_template_field'
      AND COLUMN_NAME = 'runtime_key'
);
SET @sql = IF(
    @col_exists = 0,
    'ALTER TABLE `ai_model_template_field` ADD COLUMN `runtime_key` varchar(96) NULL COMMENT ''运行时字段名'' AFTER `input_type`',
    'SELECT ''Column runtime_key already exists'' AS msg'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_model_template_field'
      AND COLUMN_NAME = 'advanced'
);
SET @sql = IF(
    @col_exists = 0,
    'ALTER TABLE `ai_model_template_field` ADD COLUMN `advanced` tinyint(1) NOT NULL DEFAULT 0 COMMENT ''是否高级字段'' AFTER `runtime_key`',
    'SELECT ''Column advanced already exists'' AS msg'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'ai_model_template_field'
      AND COLUMN_NAME = 'visible_when'
);
SET @sql = IF(
    @col_exists = 0,
    'ALTER TABLE `ai_model_template_field` ADD COLUMN `visible_when` varchar(255) NULL COMMENT ''显示条件表达式'' AFTER `advanced`',
    'SELECT ''Column visible_when already exists'' AS msg'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE `ai_model_template_field`
SET `runtime_key` = `field_key`
WHERE `runtime_key` IS NULL OR `runtime_key` = '';

UPDATE `ai_model_template_field`
SET `advanced` = CASE
    WHEN `field_group` IN ('advanced', 'generation') AND `required` = 0 THEN 1
    ELSE `advanced`
END;

UPDATE `ai_model_template_field`
SET `input_type` = 'password'
WHERE `secret` = 1 AND (`input_type` IS NULL OR `input_type` = '' OR `input_type` = 'text');

UPDATE `ai_model_template_field`
SET `input_type` = 'select',
    `options_json` = JSON_ARRAY('enabled', 'disabled')
WHERE `field_key` = 'thinking';

UPDATE `ai_model_template_field`
SET `input_type` = 'select',
    `options_json` = JSON_ARRAY('high', 'max'),
    `visible_when` = 'thinking=enabled'
WHERE `field_key` = 'reasoning_effort';

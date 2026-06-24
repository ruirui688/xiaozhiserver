SET @col_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ai_model_template'
    AND COLUMN_NAME = 'supported_status'
);
SET @sql = IF(@col_exists = 0,
  'ALTER TABLE `ai_model_template` ADD COLUMN `supported_status` varchar(32) NOT NULL DEFAULT ''SUPPORTED'' COMMENT ''适配状态'' AFTER `sort`',
  'SELECT ''Column supported_status already exists'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ai_model_template'
    AND COLUMN_NAME = 'selectable'
);
SET @sql = IF(@col_exists = 0,
  'ALTER TABLE `ai_model_template` ADD COLUMN `selectable` tinyint(1) NOT NULL DEFAULT 1 COMMENT ''是否可选择配置'' AFTER `supported_status`',
  'SELECT ''Column selectable already exists'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ai_model_template'
    AND COLUMN_NAME = 'hidden'
);
SET @sql = IF(@col_exists = 0,
  'ALTER TABLE `ai_model_template` ADD COLUMN `hidden` tinyint(1) NOT NULL DEFAULT 0 COMMENT ''是否隐藏'' AFTER `selectable`',
  'SELECT ''Column hidden already exists'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ai_model_template'
    AND COLUMN_NAME = 'deprecated'
);
SET @sql = IF(@col_exists = 0,
  'ALTER TABLE `ai_model_template` ADD COLUMN `deprecated` tinyint(1) NOT NULL DEFAULT 0 COMMENT ''是否旧版兼容/废弃'' AFTER `hidden`',
  'SELECT ''Column deprecated already exists'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ai_model_template'
    AND COLUMN_NAME = 'recommended_for'
);
SET @sql = IF(@col_exists = 0,
  'ALTER TABLE `ai_model_template` ADD COLUMN `recommended_for` varchar(255) NULL COMMENT ''推荐场景'' AFTER `deprecated`',
  'SELECT ''Column recommended_for already exists'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ai_model_template'
    AND COLUMN_NAME = 'avoid_when'
);
SET @sql = IF(@col_exists = 0,
  'ALTER TABLE `ai_model_template` ADD COLUMN `avoid_when` varchar(255) NULL COMMENT ''不适用场景'' AFTER `recommended_for`',
  'SELECT ''Column avoid_when already exists'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ai_model_template'
    AND COLUMN_NAME = 'test_contract'
);
SET @sql = IF(@col_exists = 0,
  'ALTER TABLE `ai_model_template` ADD COLUMN `test_contract` varchar(255) NULL COMMENT ''真实测试契约'' AFTER `avoid_when`',
  'SELECT ''Column test_contract already exists'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ai_model_template'
    AND COLUMN_NAME = 'source_checked_at'
);
SET @sql = IF(@col_exists = 0,
  'ALTER TABLE `ai_model_template` ADD COLUMN `source_checked_at` varchar(32) NULL COMMENT ''来源最后核验日期'' AFTER `test_contract`',
  'SELECT ''Column source_checked_at already exists'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ai_model_template'
    AND COLUMN_NAME = 'setup_hints_json'
);
SET @sql = IF(@col_exists = 0,
  'ALTER TABLE `ai_model_template` ADD COLUMN `setup_hints_json` json NULL COMMENT ''配置提示JSON'' AFTER `source_checked_at`',
  'SELECT ''Column setup_hints_json already exists'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ai_model_template'
    AND COLUMN_NAME = 'known_limitations_json'
);
SET @sql = IF(@col_exists = 0,
  'ALTER TABLE `ai_model_template` ADD COLUMN `known_limitations_json` json NULL COMMENT ''已知限制JSON'' AFTER `setup_hints_json`',
  'SELECT ''Column known_limitations_json already exists'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'ai_model_template'
    AND COLUMN_NAME = 'aliases_json'
);
SET @sql = IF(@col_exists = 0,
  'ALTER TABLE `ai_model_template` ADD COLUMN `aliases_json` json NULL COMMENT ''别名JSON'' AFTER `known_limitations_json`',
  'SELECT ''Column aliases_json already exists'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE `ai_model_template`
SET
  `supported_status` = COALESCE(NULLIF(`supported_status`, ''), 'SUPPORTED'),
  `selectable` = IFNULL(`selectable`, 1),
  `hidden` = IFNULL(`hidden`, IF(`enabled` = 0, 1, 0)),
  `deprecated` = IFNULL(`deprecated`, 0);

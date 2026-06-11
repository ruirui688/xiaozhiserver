SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_model_config' AND COLUMN_NAME = 'test_status');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE `ai_model_config` ADD COLUMN `test_status` TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''测试状态(0未测试 1通过 2失败)'' AFTER `is_enabled`', 'SELECT ''Column test_status already exists'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_model_config' AND COLUMN_NAME = 'test_message');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE `ai_model_config` ADD COLUMN `test_message` VARCHAR(500) NULL COMMENT ''测试结果说明'' AFTER `test_status`', 'SELECT ''Column test_message already exists'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_model_config' AND COLUMN_NAME = 'test_time');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE `ai_model_config` ADD COLUMN `test_time` DATETIME NULL COMMENT ''测试时间'' AFTER `test_message`', 'SELECT ''Column test_time already exists'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_model_config' AND INDEX_NAME = 'idx_ai_model_config_available');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX `idx_ai_model_config_available` ON `ai_model_config` (`model_type`, `is_enabled`, `test_status`, `sort`)', 'SELECT ''Index idx_ai_model_config_available already exists'' AS msg');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

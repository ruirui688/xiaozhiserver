SET @col_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_device' AND COLUMN_NAME = 'config_version'
);
SET @sql = IF(
  @col_exists = 0,
  'ALTER TABLE `ai_device` ADD COLUMN `config_version` BIGINT NOT NULL DEFAULT 0 COMMENT ''设备运行配置版本'' AFTER `agent_id`',
  'SELECT ''ai_device.config_version exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_template' AND COLUMN_NAME = 'version'
);
SET @sql = IF(
  @col_exists = 0,
  'ALTER TABLE `ai_agent_template` ADD COLUMN `version` INT NOT NULL DEFAULT 1 COMMENT ''模板版本'' AFTER `id`',
  'SELECT ''ai_agent_template.version exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_template' AND COLUMN_NAME = 'asr_hotword_id'
);
SET @sql = IF(
  @col_exists = 0,
  'ALTER TABLE `ai_agent_template` ADD COLUMN `asr_hotword_id` VARCHAR(255) NULL COMMENT ''当前ASR热词表ID'' AFTER `asr_model_id`, ADD COLUMN `asr_hotword_ids` JSON NULL COMMENT ''ASR热词表候选ID列表'' AFTER `asr_hotword_id`',
  'SELECT ''ai_agent_template.asr_hotword_id exists'''
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE `ai_model_config`
SET
  `test_status` = 1,
  `test_message` = '内置意图模式，无需外部连通性测试。',
  `test_time` = COALESCE(`test_time`, NOW()),
  `update_date` = NOW()
WHERE UPPER(`model_type`) = 'INTENT'
  AND LOWER(`model_code`) IN ('nointent', 'function_call')
  AND `is_enabled` = 1;

CREATE TABLE IF NOT EXISTS `ai_device_scene_binding` (
  `id` VARCHAR(32) NOT NULL COMMENT '场景绑定ID',
  `workspace_id` VARCHAR(32) NOT NULL COMMENT '所属工作空间ID',
  `device_id` VARCHAR(32) NOT NULL COMMENT '设备ID',
  `agent_id` VARCHAR(32) NOT NULL COMMENT '场景智能体ID',
  `scene_name` VARCHAR(64) NOT NULL COMMENT '设备侧场景名称',
  `switch_aliases` JSON NULL COMMENT '语音切换别名列表',
  `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否允许切换 0否 1是',
  `sort` INT NOT NULL DEFAULT 0 COMMENT '排序',
  `template_id` VARCHAR(32) DEFAULT NULL COMMENT '创建来源模板ID',
  `template_version` INT DEFAULT NULL COMMENT '创建来源模板版本',
  `creator` BIGINT DEFAULT NULL COMMENT '创建者',
  `create_date` DATETIME DEFAULT NULL COMMENT '创建时间',
  `updater` BIGINT DEFAULT NULL COMMENT '更新者',
  `update_date` DATETIME DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_scene_agent` (`device_id`, `agent_id`),
  KEY `idx_device_scene_workspace` (`workspace_id`, `device_id`, `enabled`),
  KEY `idx_device_scene_agent` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备多场景绑定';

CREATE TABLE IF NOT EXISTS `ai_device_scene_switch` (
  `id` VARCHAR(32) NOT NULL COMMENT '切换审计ID',
  `workspace_id` VARCHAR(32) NOT NULL COMMENT '所属工作空间ID',
  `device_id` VARCHAR(32) NOT NULL COMMENT '设备ID',
  `binding_id` VARCHAR(32) NOT NULL COMMENT '目标场景绑定ID',
  `from_agent_id` VARCHAR(32) DEFAULT NULL COMMENT '原智能体ID',
  `to_agent_id` VARCHAR(32) NOT NULL COMMENT '目标智能体ID',
  `switch_request_id` VARCHAR(64) NOT NULL COMMENT '幂等切换请求ID',
  `config_version` BIGINT NOT NULL COMMENT '目标设备配置版本',
  `status` VARCHAR(16) NOT NULL COMMENT 'REQUESTED/SCHEDULED/APPLIED/FAILED/ROLLED_BACK',
  `failure_code` VARCHAR(64) DEFAULT NULL COMMENT '失败类型，不保存上游敏感原文',
  `requested_by` BIGINT DEFAULT NULL COMMENT '请求用户ID',
  `requested_at` DATETIME NOT NULL COMMENT '请求时间',
  `applied_at` DATETIME DEFAULT NULL COMMENT '生效时间',
  `updated_at` DATETIME NOT NULL COMMENT '状态更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_scene_switch_request` (`switch_request_id`),
  KEY `idx_device_scene_switch_device` (`device_id`, `requested_at`),
  KEY `idx_device_scene_switch_status` (`status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备场景切换审计';

INSERT INTO `ai_device_scene_binding` (
  `id`, `workspace_id`, `device_id`, `agent_id`, `scene_name`, `switch_aliases`,
  `enabled`, `sort`, `creator`, `create_date`, `updater`, `update_date`
)
SELECT
  MD5(CONCAT('device-scene-', d.`id`, '-', d.`agent_id`)),
  COALESCE(d.`workspace_id`, a.`workspace_id`),
  d.`id`,
  d.`agent_id`,
  COALESCE(NULLIF(a.`agent_name`, ''), NULLIF(d.`alias`, ''), '默认场景'),
  JSON_ARRAY(),
  1,
  0,
  COALESCE(d.`creator`, d.`user_id`),
  COALESCE(d.`create_date`, NOW()),
  COALESCE(d.`updater`, d.`user_id`),
  COALESCE(d.`update_date`, NOW())
FROM `ai_device` d
JOIN `ai_agent` a ON a.`id` = d.`agent_id`
WHERE d.`agent_id` IS NOT NULL
  AND COALESCE(d.`workspace_id`, a.`workspace_id`) IS NOT NULL
ON DUPLICATE KEY UPDATE
  `workspace_id` = VALUES(`workspace_id`),
  `enabled` = 1,
  `update_date` = VALUES(`update_date`);

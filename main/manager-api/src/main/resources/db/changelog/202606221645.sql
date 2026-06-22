ALTER TABLE `ai_model_config`
  ADD COLUMN `adapter_code` varchar(64) NULL COMMENT '运行时适配器编码' AFTER `model_name`,
  ADD COLUMN `template_code` varchar(96) NULL COMMENT '模型模板编码' AFTER `adapter_code`,
  ADD COLUMN `vendor_name` varchar(64) NULL COMMENT '厂商名称' AFTER `template_code`,
  ADD COLUMN `product_name` varchar(96) NULL COMMENT '产品/API名称' AFTER `vendor_name`,
  ADD COLUMN `protocol` varchar(64) NULL COMMENT '协议编码' AFTER `product_name`,
  ADD COLUMN `upstream_model_id` varchar(255) NULL COMMENT '上游真实模型或资源ID' AFTER `protocol`,
  ADD COLUMN `endpoint_url` varchar(512) NULL COMMENT '接口端点地址' AFTER `upstream_model_id`;

UPDATE `ai_model_config`
SET
  `adapter_code` = JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.type')),
  `upstream_model_id` = COALESCE(
    JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.model_name')),
    JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.model')),
    JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.resource_id')),
    JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.model_dir'))
  ),
  `endpoint_url` = COALESCE(
    JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.base_url')),
    JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.api_url')),
    JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.url')),
    JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.ws_url')),
    JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.host'))
  ),
  `protocol` = CASE
    WHEN JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.type')) = 'openai' THEN 'openai_compatible'
    WHEN JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.type')) LIKE '%stream%' THEN 'websocket_stream'
    WHEN JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.type')) IN ('edge', 'sherpa_onnx_local', 'fun_local', 'nomem') THEN 'local'
    ELSE 'provider_native'
  END
WHERE `config_json` IS NOT NULL;

CREATE TABLE IF NOT EXISTS `ai_model_template` (
  `id` varchar(96) NOT NULL COMMENT '主键',
  `template_code` varchar(96) NOT NULL COMMENT '模板编码',
  `model_type` varchar(32) NOT NULL COMMENT '模型类型',
  `adapter_code` varchar(64) NOT NULL COMMENT '适配器编码',
  `vendor_name` varchar(64) NULL COMMENT '厂商名称',
  `product_name` varchar(96) NULL COMMENT '产品/API名称',
  `capability` varchar(64) NULL COMMENT '能力编码',
  `access_mode` varchar(64) NULL COMMENT '接入方式',
  `deployment` varchar(64) NULL COMMENT '部署形态',
  `protocol` varchar(64) NULL COMMENT '协议编码',
  `upstream_model_field` varchar(64) NULL COMMENT '上游模型字段名',
  `doc_link` varchar(255) NULL COMMENT '文档链接',
  `enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
  `sort` int unsigned NOT NULL DEFAULT 0 COMMENT '排序',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_model_template_code` (`template_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型模板表';

CREATE TABLE IF NOT EXISTS `ai_model_template_field` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `template_id` varchar(96) NOT NULL COMMENT '模板ID',
  `field_key` varchar(96) NOT NULL COMMENT '字段键',
  `field_label` varchar(96) NULL COMMENT '字段标签',
  `field_group` varchar(32) NOT NULL DEFAULT 'provider' COMMENT '字段分组',
  `value_type` varchar(32) NOT NULL DEFAULT 'string' COMMENT '值类型',
  `input_type` varchar(32) NOT NULL DEFAULT 'text' COMMENT '输入类型',
  `required` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否必填',
  `secret` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否密钥',
  `default_value` text NULL COMMENT '默认值',
  `options_json` json NULL COMMENT '选项JSON',
  `help_text` varchar(255) NULL COMMENT '提示文本',
  `sort` int unsigned NOT NULL DEFAULT 0 COMMENT '排序',
  PRIMARY KEY (`id`),
  KEY `idx_ai_model_template_field_template` (`template_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型模板字段表';

CREATE TABLE IF NOT EXISTS `ai_model_config_param` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `model_id` varchar(32) NOT NULL COMMENT '模型配置ID',
  `param_key` varchar(96) NOT NULL COMMENT '参数键',
  `param_value` text NULL COMMENT '参数值',
  `value_type` varchar(32) NOT NULL DEFAULT 'string' COMMENT '值类型',
  `param_group` varchar(32) NOT NULL DEFAULT 'provider' COMMENT '参数分组',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_model_config_param` (`model_id`, `param_key`),
  KEY `idx_ai_model_config_param_model` (`model_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型标准参数表';

CREATE TABLE IF NOT EXISTS `ai_model_config_secret` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `model_id` varchar(32) NOT NULL COMMENT '模型配置ID',
  `secret_key` varchar(96) NOT NULL COMMENT '密钥字段',
  `secret_value` text NULL COMMENT '密钥值',
  `masked_value` varchar(128) NULL COMMENT '脱敏展示值',
  `value_type` varchar(32) NOT NULL DEFAULT 'secret' COMMENT '值类型',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_model_config_secret` (`model_id`, `secret_key`),
  KEY `idx_ai_model_config_secret_model` (`model_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型密钥表';

INSERT INTO `ai_model_config_secret` (`model_id`, `secret_key`, `secret_value`, `masked_value`)
SELECT `id`, 'api_key', JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.api_key')), '******'
FROM `ai_model_config`
WHERE JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.api_key')) IS NOT NULL
  AND JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.api_key')) <> ''
ON DUPLICATE KEY UPDATE `secret_value` = VALUES(`secret_value`), `masked_value` = VALUES(`masked_value`);

INSERT INTO `ai_model_config_secret` (`model_id`, `secret_key`, `secret_value`, `masked_value`)
SELECT `id`, 'access_token', JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.access_token')), '******'
FROM `ai_model_config`
WHERE JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.access_token')) IS NOT NULL
  AND JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.access_token')) <> ''
ON DUPLICATE KEY UPDATE `secret_value` = VALUES(`secret_value`), `masked_value` = VALUES(`masked_value`);

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT `id`, 'thinking', JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.thinking')), 'string', 'generation'
FROM `ai_model_config`
WHERE JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.thinking')) IS NOT NULL
ON DUPLICATE KEY UPDATE `param_value` = VALUES(`param_value`);

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT `id`, 'reasoning_effort', JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.reasoning_effort')), 'string', 'generation'
FROM `ai_model_config`
WHERE JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.reasoning_effort')) IS NOT NULL
ON DUPLICATE KEY UPDATE `param_value` = VALUES(`param_value`);

INSERT INTO `ai_model_config_secret` (`model_id`, `secret_key`, `secret_value`, `masked_value`, `value_type`)
SELECT
    m.`id`,
    jt.`json_key`,
    JSON_UNQUOTE(JSON_EXTRACT(m.`config_json`, CONCAT('$."', REPLACE(jt.`json_key`, '"', '\\"'), '"'))),
    CASE
        WHEN JSON_UNQUOTE(JSON_EXTRACT(m.`config_json`, CONCAT('$."', REPLACE(jt.`json_key`, '"', '\\"'), '"'))) IS NULL
          OR CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(m.`config_json`, CONCAT('$."', REPLACE(jt.`json_key`, '"', '\\"'), '"')))) = 0 THEN ''
        WHEN CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(m.`config_json`, CONCAT('$."', REPLACE(jt.`json_key`, '"', '\\"'), '"')))) <= 8 THEN '****'
        ELSE CONCAT(
            LEFT(JSON_UNQUOTE(JSON_EXTRACT(m.`config_json`, CONCAT('$."', REPLACE(jt.`json_key`, '"', '\\"'), '"'))), 4),
            '****',
            RIGHT(JSON_UNQUOTE(JSON_EXTRACT(m.`config_json`, CONCAT('$."', REPLACE(jt.`json_key`, '"', '\\"'), '"'))), 4)
        )
    END,
    'secret'
FROM `ai_model_config` m
JOIN JSON_TABLE(
    JSON_KEYS(m.`config_json`),
    '$[*]' COLUMNS (`json_key` VARCHAR(100) PATH '$')
) jt
WHERE m.`config_json` IS NOT NULL
  AND JSON_VALID(m.`config_json`)
  AND jt.`json_key` IN ('api_key', 'api_password', 'access_token', 'token', 'secret_key', 'api_secret', 'access_key_secret', 'personal_access_token')
ON DUPLICATE KEY UPDATE
    `secret_value` = VALUES(`secret_value`),
    `masked_value` = VALUES(`masked_value`),
    `value_type` = VALUES(`value_type`);

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT
    m.`id`,
    jt.`json_key`,
    JSON_UNQUOTE(JSON_EXTRACT(m.`config_json`, CONCAT('$."', REPLACE(jt.`json_key`, '"', '\\"'), '"'))),
    CASE JSON_TYPE(JSON_EXTRACT(m.`config_json`, CONCAT('$."', REPLACE(jt.`json_key`, '"', '\\"'), '"')))
        WHEN 'INTEGER' THEN 'number'
        WHEN 'DOUBLE' THEN 'number'
        WHEN 'BOOLEAN' THEN 'boolean'
        WHEN 'OBJECT' THEN 'json'
        WHEN 'ARRAY' THEN 'json'
        ELSE 'string'
    END,
    CASE
        WHEN jt.`json_key` REGEXP 'temperature|top_|max_|penalty|thinking|reasoning' THEN 'generation'
        WHEN jt.`json_key` REGEXP 'voice|audio|sample|speed|rate|pitch|volume' THEN 'audio'
        ELSE 'provider'
    END
FROM `ai_model_config` m
JOIN JSON_TABLE(
    JSON_KEYS(m.`config_json`),
    '$[*]' COLUMNS (`json_key` VARCHAR(100) PATH '$')
) jt
WHERE m.`config_json` IS NOT NULL
  AND JSON_VALID(m.`config_json`)
  AND jt.`json_key` NOT IN (
      'type', 'base_url', 'api_url', 'url', 'ws_url', 'server_url', 'host', 'model_name', 'model', 'resource_id',
      'api_key', 'api_password', 'access_token', 'token', 'secret_key', 'api_secret',
      'access_key_secret', 'personal_access_token'
  )
ON DUPLICATE KEY UPDATE
    `param_value` = VALUES(`param_value`),
    `value_type` = VALUES(`value_type`),
    `param_group` = VALUES(`param_group`);

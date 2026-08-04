DROP TEMPORARY TABLE IF EXISTS `tmp_qwen_omni_realtime_repair_ids`;

CREATE TEMPORARY TABLE `tmp_qwen_omni_realtime_repair_ids` (
  `model_id` varchar(32) NOT NULL,
  PRIMARY KEY (`model_id`)
) ENGINE=MEMORY;

INSERT INTO `tmp_qwen_omni_realtime_repair_ids` (`model_id`)
SELECT `c`.`id`
FROM `ai_model_config` `c`
WHERE (
    `c`.`id` = 'LLM_QwenOmniRealtime'
    OR `c`.`model_code` = 'QwenOmniRealtime'
    OR `c`.`template_code` = 'LLM_ALI_QWEN_OMNI_REALTIME'
  )
  AND (
    UPPER(COALESCE(`c`.`model_type`, '')) <> 'LLM'
    OR LOWER(COALESCE(`c`.`adapter_code`, '')) <> 'qwen_omni_realtime'
    OR UPPER(COALESCE(`c`.`template_code`, '')) <> 'LLM_ALI_QWEN_OMNI_REALTIME'
    OR COALESCE(`c`.`protocol`, '') <> 'DashScope Realtime WebSocket'
    OR NULLIF(TRIM(`c`.`upstream_model_id`), '') IS NULL
    OR LOWER(`c`.`upstream_model_id`) NOT LIKE '%realtime%'
    OR (
      NULLIF(TRIM(`c`.`endpoint_url`), '') IS NOT NULL
      AND NOT (
        LOWER(TRIM(`c`.`endpoint_url`)) REGEXP '^wss://dashscope\\.aliyuncs\\.com(:443)?/api-ws/v1/realtime/?$'
        OR LOWER(TRIM(`c`.`endpoint_url`)) REGEXP '^wss://[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.(cn-beijing|ap-southeast-1)\\.maas\\.aliyuncs\\.com(:443)?/api-ws/v1/realtime/?$'
      )
    )
    OR `c`.`config_json` IS NOT NULL
    OR NOT EXISTS (
      SELECT 1
      FROM `ai_model_config_param` `p`
      WHERE `p`.`model_id` = `c`.`id`
        AND `p`.`param_key` = 'conversation_mode'
        AND LOWER(TRIM(COALESCE(`p`.`param_value`, ''))) = 'realtime'
    )
    OR EXISTS (
      SELECT 1
      FROM `ai_model_config_param` `p`
      WHERE `p`.`model_id` = `c`.`id`
        AND `p`.`param_key` NOT IN (
          'voice', 'conversation_mode', 'input_sample_rate', 'output_sample_rate',
          'turn_detection_type', 'turn_detection_threshold', 'prefix_padding_ms',
          'silence_duration_ms', 'enable_input_audio_transcription',
          'input_audio_transcription_model', 'connect_timeout_seconds',
          'response_timeout_seconds', 'max_event_bytes'
        )
    )
    OR EXISTS (
      SELECT 1
      FROM `ai_model_config_secret` `s`
      WHERE `s`.`model_id` = `c`.`id`
        AND `s`.`secret_key` <> 'api_key'
    )
  );

-- Preserve a legacy API key in the secret table before removing config_json.
INSERT INTO `ai_model_config_secret` (`model_id`, `secret_key`, `secret_value`, `masked_value`, `value_type`)
SELECT
  `r`.`model_id`,
  'api_key',
  JSON_UNQUOTE(JSON_EXTRACT(`c`.`config_json`, '$.api_key')),
  CASE
    WHEN CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(`c`.`config_json`, '$.api_key'))) <= 8 THEN '****'
    ELSE CONCAT(
      LEFT(JSON_UNQUOTE(JSON_EXTRACT(`c`.`config_json`, '$.api_key')), 4),
      '****',
      RIGHT(JSON_UNQUOTE(JSON_EXTRACT(`c`.`config_json`, '$.api_key')), 4)
    )
  END,
  'secret'
FROM `tmp_qwen_omni_realtime_repair_ids` `r`
JOIN `ai_model_config` `c` ON `c`.`id` = `r`.`model_id`
WHERE `c`.`config_json` IS NOT NULL
  AND JSON_VALID(`c`.`config_json`)
  AND NULLIF(TRIM(JSON_UNQUOTE(JSON_EXTRACT(`c`.`config_json`, '$.api_key'))), '') IS NOT NULL
  AND JSON_UNQUOTE(JSON_EXTRACT(`c`.`config_json`, '$.api_key')) NOT LIKE '你的%'
  AND JSON_UNQUOTE(JSON_EXTRACT(`c`.`config_json`, '$.api_key')) NOT LIKE '%placeholder%'
ON DUPLICATE KEY UPDATE
  `masked_value` = CASE
    WHEN NULLIF(TRIM(`secret_value`), '') IS NULL THEN VALUES(`masked_value`)
    ELSE `masked_value`
  END,
  `secret_value` = CASE
    WHEN NULLIF(TRIM(`secret_value`), '') IS NULL THEN VALUES(`secret_value`)
    ELSE `secret_value`
  END,
  `value_type` = 'secret';

UPDATE `ai_model_config` `c`
JOIN `tmp_qwen_omni_realtime_repair_ids` `r` ON `r`.`model_id` = `c`.`id`
SET
  `c`.`model_type` = 'LLM',
  `c`.`model_code` = 'QwenOmniRealtime',
  `c`.`adapter_code` = 'qwen_omni_realtime',
  `c`.`template_code` = 'LLM_ALI_QWEN_OMNI_REALTIME',
  `c`.`vendor_name` = '阿里云百炼',
  `c`.`product_name` = 'Qwen Omni Realtime',
  `c`.`protocol` = 'DashScope Realtime WebSocket',
  `c`.`upstream_model_id` = CASE
    WHEN NULLIF(TRIM(`c`.`upstream_model_id`), '') IS NULL
      OR LOWER(`c`.`upstream_model_id`) NOT LIKE '%realtime%'
      THEN 'qwen3.5-omni-flash-realtime'
    ELSE `c`.`upstream_model_id`
  END,
  `c`.`endpoint_url` = CASE
    WHEN NULLIF(TRIM(`c`.`endpoint_url`), '') IS NULL THEN NULL
    WHEN LOWER(TRIM(`c`.`endpoint_url`)) REGEXP '^wss://dashscope\\.aliyuncs\\.com(:443)?/api-ws/v1/realtime/?$'
      OR LOWER(TRIM(`c`.`endpoint_url`)) REGEXP '^wss://[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.(cn-beijing|ap-southeast-1)\\.maas\\.aliyuncs\\.com(:443)?/api-ws/v1/realtime/?$'
      THEN TRIM(`c`.`endpoint_url`)
    ELSE NULL
  END,
  `c`.`is_enabled` = 0,
  `c`.`test_status` = 0,
  `c`.`test_message` = NULL,
  `c`.`test_time` = NULL,
  `c`.`config_json` = NULL,
  `c`.`update_date` = NOW();

DELETE `s`
FROM `ai_model_config_secret` `s`
JOIN `tmp_qwen_omni_realtime_repair_ids` `r` ON `r`.`model_id` = `s`.`model_id`
WHERE `s`.`secret_key` <> 'api_key';

DELETE `p`
FROM `ai_model_config_param` `p`
JOIN `tmp_qwen_omni_realtime_repair_ids` `r` ON `r`.`model_id` = `p`.`model_id`
WHERE `p`.`param_key` NOT IN (
  'voice', 'conversation_mode', 'input_sample_rate', 'output_sample_rate',
  'turn_detection_type', 'turn_detection_threshold', 'prefix_padding_ms',
  'silence_duration_ms', 'enable_input_audio_transcription',
  'input_audio_transcription_model', 'connect_timeout_seconds',
  'response_timeout_seconds', 'max_event_bytes'
);

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT `r`.`model_id`, `d`.`param_key`, `d`.`param_value`, `d`.`value_type`, `d`.`param_group`
FROM `tmp_qwen_omni_realtime_repair_ids` `r`
CROSS JOIN (
  SELECT 'voice' AS `param_key`, 'Ethan' AS `param_value`, 'string' AS `value_type`, 'audio' AS `param_group`
  UNION ALL SELECT 'conversation_mode', 'realtime', 'string', 'provider'
  UNION ALL SELECT 'input_sample_rate', '16000', 'number', 'audio'
  UNION ALL SELECT 'output_sample_rate', '24000', 'number', 'audio'
  UNION ALL SELECT 'turn_detection_type', 'server_vad', 'string', 'audio'
  UNION ALL SELECT 'turn_detection_threshold', '0.2', 'number', 'audio'
  UNION ALL SELECT 'prefix_padding_ms', '300', 'number', 'audio'
  UNION ALL SELECT 'silence_duration_ms', '800', 'number', 'audio'
  UNION ALL SELECT 'enable_input_audio_transcription', 'true', 'boolean', 'audio'
  UNION ALL SELECT 'input_audio_transcription_model', '', 'string', 'audio'
  UNION ALL SELECT 'connect_timeout_seconds', '10', 'number', 'advanced'
  UNION ALL SELECT 'response_timeout_seconds', '120', 'number', 'advanced'
  UNION ALL SELECT 'max_event_bytes', '10485760', 'number', 'advanced'
) `d`
ON DUPLICATE KEY UPDATE
  `param_value` = CASE
    WHEN `param_key` = 'conversation_mode' THEN 'realtime'
    WHEN NULLIF(TRIM(`param_value`), '') IS NULL THEN VALUES(`param_value`)
    ELSE `param_value`
  END,
  `value_type` = VALUES(`value_type`),
  `param_group` = VALUES(`param_group`);

DROP TEMPORARY TABLE `tmp_qwen_omni_realtime_repair_ids`;

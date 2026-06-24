SET @huoshan_double_stream_ws_url = 'wss://openspeech.bytedance.com/api/v3/tts/bidirection';

UPDATE `ai_model_template_field`
SET
  `runtime_key` = 'ws_url',
  `default_value` = @huoshan_double_stream_ws_url
WHERE `template_id` IN ('TTS_DOUBAO_BIGTTS_STREAM', 'TTS_DOUBAO_SEED_TTS_2_STREAM')
  AND `field_key` = 'ws_url';

UPDATE `ai_model_config`
SET `endpoint_url` = @huoshan_double_stream_ws_url
WHERE UPPER(`model_type`) = 'TTS'
  AND (
    `adapter_code` = 'huoshan_double_stream'
    OR `template_code` IN ('TTS_DOUBAO_BIGTTS_STREAM', 'TTS_DOUBAO_SEED_TTS_2_STREAM')
    OR JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.type')) = 'huoshan_double_stream'
  )
  AND (
    `endpoint_url` IS NULL
    OR TRIM(`endpoint_url`) = ''
    OR LOWER(`endpoint_url`) LIKE '%openspeech.bytedance.com/api/v3/tts/%'
  );

UPDATE `ai_model_config`
SET `config_json` = JSON_SET(`config_json`, '$.ws_url', @huoshan_double_stream_ws_url)
WHERE UPPER(`model_type`) = 'TTS'
  AND `config_json` IS NOT NULL
  AND (
    `adapter_code` = 'huoshan_double_stream'
    OR `template_code` IN ('TTS_DOUBAO_BIGTTS_STREAM', 'TTS_DOUBAO_SEED_TTS_2_STREAM')
    OR JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.type')) = 'huoshan_double_stream'
  );

UPDATE `ai_model_config`
SET `config_json` = JSON_SET(`config_json`, '$.base_url', @huoshan_double_stream_ws_url)
WHERE UPPER(`model_type`) = 'TTS'
  AND `config_json` IS NOT NULL
  AND (
    `adapter_code` = 'huoshan_double_stream'
    OR `template_code` IN ('TTS_DOUBAO_BIGTTS_STREAM', 'TTS_DOUBAO_SEED_TTS_2_STREAM')
    OR JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.type')) = 'huoshan_double_stream'
  )
  AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.base_url'))) LIKE '%openspeech.bytedance.com/api/v3/tts/%';

UPDATE `ai_model_config`
SET `config_json` = JSON_SET(`config_json`, '$.api_url', @huoshan_double_stream_ws_url)
WHERE UPPER(`model_type`) = 'TTS'
  AND `config_json` IS NOT NULL
  AND (
    `adapter_code` = 'huoshan_double_stream'
    OR `template_code` IN ('TTS_DOUBAO_BIGTTS_STREAM', 'TTS_DOUBAO_SEED_TTS_2_STREAM')
    OR JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.type')) = 'huoshan_double_stream'
  )
  AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.api_url'))) LIKE '%openspeech.bytedance.com/api/v3/tts/%';

UPDATE `ai_model_config`
SET `config_json` = JSON_SET(`config_json`, '$.url', @huoshan_double_stream_ws_url)
WHERE UPPER(`model_type`) = 'TTS'
  AND `config_json` IS NOT NULL
  AND (
    `adapter_code` = 'huoshan_double_stream'
    OR `template_code` IN ('TTS_DOUBAO_BIGTTS_STREAM', 'TTS_DOUBAO_SEED_TTS_2_STREAM')
    OR JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.type')) = 'huoshan_double_stream'
  )
  AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.url'))) LIKE '%openspeech.bytedance.com/api/v3/tts/%';

UPDATE `ai_model_config`
SET `config_json` = JSON_SET(`config_json`, '$.endpoint_url', @huoshan_double_stream_ws_url)
WHERE UPPER(`model_type`) = 'TTS'
  AND `config_json` IS NOT NULL
  AND (
    `adapter_code` = 'huoshan_double_stream'
    OR `template_code` IN ('TTS_DOUBAO_BIGTTS_STREAM', 'TTS_DOUBAO_SEED_TTS_2_STREAM')
    OR JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.type')) = 'huoshan_double_stream'
  )
  AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.endpoint_url'))) LIKE '%openspeech.bytedance.com/api/v3/tts/%';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT `id`, 'ws_url', @huoshan_double_stream_ws_url, 'string', 'endpoint'
FROM `ai_model_config`
WHERE UPPER(`model_type`) = 'TTS'
  AND (
    `adapter_code` = 'huoshan_double_stream'
    OR `template_code` IN ('TTS_DOUBAO_BIGTTS_STREAM', 'TTS_DOUBAO_SEED_TTS_2_STREAM')
    OR JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.type')) = 'huoshan_double_stream'
  )
ON DUPLICATE KEY UPDATE
  `param_value` = VALUES(`param_value`),
  `value_type` = 'string',
  `param_group` = 'endpoint';

UPDATE `ai_model_config_param` p
JOIN `ai_model_config` m ON m.`id` = p.`model_id`
SET
  p.`param_value` = @huoshan_double_stream_ws_url,
  p.`value_type` = 'string',
  p.`param_group` = 'endpoint'
WHERE UPPER(m.`model_type`) = 'TTS'
  AND (
    m.`adapter_code` = 'huoshan_double_stream'
    OR m.`template_code` IN ('TTS_DOUBAO_BIGTTS_STREAM', 'TTS_DOUBAO_SEED_TTS_2_STREAM')
    OR JSON_UNQUOTE(JSON_EXTRACT(m.`config_json`, '$.type')) = 'huoshan_double_stream'
  )
  AND p.`param_key` IN ('base_url', 'api_url', 'url', 'server_url', 'ws_url', 'host', 'endpoint_url')
  AND (
    p.`param_key` = 'ws_url'
    OR LOWER(p.`param_value`) LIKE '%openspeech.bytedance.com/api/v3/tts/%'
  );

SET @huoshan_double_stream_ws_url = 'wss://openspeech.bytedance.com/api/v3/tts/bidirection';
SET @doubao_bigtts_resource_id = 'volc.service_type.10029';
SET @doubao_bigtts_speaker = 'zh_male_shaonianzixin_moon_bigtts';
SET @doubao_seed_tts_2_resource_id = 'seed-tts-2.0';
SET @doubao_seed_tts_2_speaker = 'zh_female_xiaohe_uranus_bigtts';

UPDATE `ai_model_config`
SET
  `adapter_code` = 'huoshan_double_stream',
  `endpoint_url` = @huoshan_double_stream_ws_url,
  `upstream_model_id` = CASE
    WHEN `template_code` = 'TTS_DOUBAO_SEED_TTS_2_STREAM' THEN @doubao_seed_tts_2_resource_id
    WHEN `upstream_model_id` IS NULL OR TRIM(`upstream_model_id`) = '' OR `upstream_model_id` IN ('BV001_streaming', 'BV700_streaming')
      THEN @doubao_bigtts_resource_id
    ELSE `upstream_model_id`
  END,
  `config_json` = JSON_SET(
    COALESCE(`config_json`, JSON_OBJECT()),
    '$.type', 'huoshan_double_stream',
    '$.ws_url', @huoshan_double_stream_ws_url,
    '$.resource_id', CASE
      WHEN `template_code` = 'TTS_DOUBAO_SEED_TTS_2_STREAM' THEN @doubao_seed_tts_2_resource_id
      WHEN JSON_UNQUOTE(JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.resource_id')) IS NULL
        OR TRIM(JSON_UNQUOTE(JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.resource_id'))) = ''
        OR JSON_UNQUOTE(JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.resource_id')) IN ('BV001_streaming', 'BV700_streaming')
        THEN @doubao_bigtts_resource_id
      ELSE JSON_UNQUOTE(JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.resource_id'))
    END,
    '$.speaker', CASE
      WHEN `template_code` = 'TTS_DOUBAO_SEED_TTS_2_STREAM' THEN @doubao_seed_tts_2_speaker
      WHEN JSON_UNQUOTE(JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.speaker')) IS NULL
        OR TRIM(JSON_UNQUOTE(JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.speaker'))) = ''
        OR JSON_UNQUOTE(JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.speaker')) IN ('BV001_streaming', 'BV700_streaming')
        THEN @doubao_bigtts_speaker
      ELSE JSON_UNQUOTE(JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.speaker'))
    END
  )
WHERE UPPER(`model_type`) = 'TTS'
  AND (
    `adapter_code` = 'huoshan_double_stream'
    OR `template_code` IN ('TTS_DOUBAO_BIGTTS_STREAM', 'TTS_DOUBAO_SEED_TTS_2_STREAM')
    OR `id` = 'TTS_DoubaoTTS'
  );

UPDATE `ai_model_config`
SET `config_json` = JSON_REMOVE(`config_json`, '$.api_url')
WHERE UPPER(`model_type`) = 'TTS'
  AND (
    `adapter_code` = 'huoshan_double_stream'
    OR `template_code` IN ('TTS_DOUBAO_BIGTTS_STREAM', 'TTS_DOUBAO_SEED_TTS_2_STREAM')
  )
  AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.api_url'))) LIKE '%openspeech.bytedance.com/api/v1/tts%';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT `id`, 'resource_id',
  CASE WHEN `template_code` = 'TTS_DOUBAO_SEED_TTS_2_STREAM' THEN @doubao_seed_tts_2_resource_id ELSE @doubao_bigtts_resource_id END,
  'string', 'provider'
FROM `ai_model_config`
WHERE UPPER(`model_type`) = 'TTS'
  AND (
    `adapter_code` = 'huoshan_double_stream'
    OR `template_code` IN ('TTS_DOUBAO_BIGTTS_STREAM', 'TTS_DOUBAO_SEED_TTS_2_STREAM')
    OR `id` = 'TTS_DoubaoTTS'
  )
ON DUPLICATE KEY UPDATE
  `param_value` = CASE
    WHEN VALUES(`param_value`) = @doubao_seed_tts_2_resource_id THEN @doubao_seed_tts_2_resource_id
    WHEN `param_value` IS NULL OR TRIM(`param_value`) = '' OR `param_value` IN ('BV001_streaming', 'BV700_streaming')
      THEN VALUES(`param_value`)
    ELSE `param_value`
  END,
  `value_type` = 'string',
  `param_group` = 'provider';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT `id`, 'speaker',
  CASE WHEN `template_code` = 'TTS_DOUBAO_SEED_TTS_2_STREAM' THEN @doubao_seed_tts_2_speaker ELSE @doubao_bigtts_speaker END,
  'string', 'audio'
FROM `ai_model_config`
WHERE UPPER(`model_type`) = 'TTS'
  AND (
    `adapter_code` = 'huoshan_double_stream'
    OR `template_code` IN ('TTS_DOUBAO_BIGTTS_STREAM', 'TTS_DOUBAO_SEED_TTS_2_STREAM')
    OR `id` = 'TTS_DoubaoTTS'
  )
ON DUPLICATE KEY UPDATE
  `param_value` = CASE
    WHEN VALUES(`param_value`) = @doubao_seed_tts_2_speaker THEN @doubao_seed_tts_2_speaker
    WHEN `param_value` IS NULL OR TRIM(`param_value`) = '' OR `param_value` IN ('BV001_streaming', 'BV700_streaming')
      THEN VALUES(`param_value`)
    ELSE `param_value`
  END,
  `value_type` = 'string',
  `param_group` = 'audio';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT `id`, 'ws_url', @huoshan_double_stream_ws_url, 'string', 'endpoint'
FROM `ai_model_config`
WHERE UPPER(`model_type`) = 'TTS'
  AND (
    `adapter_code` = 'huoshan_double_stream'
    OR `template_code` IN ('TTS_DOUBAO_BIGTTS_STREAM', 'TTS_DOUBAO_SEED_TTS_2_STREAM')
    OR `id` = 'TTS_DoubaoTTS'
  )
ON DUPLICATE KEY UPDATE
  `param_value` = VALUES(`param_value`),
  `value_type` = 'string',
  `param_group` = 'endpoint';

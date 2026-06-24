SET @doubao_seed_model_id = 'TTS_HSDSTTS_V2';
SET @doubao_seed_template = 'TTS_DOUBAO_SEED_TTS_2_STREAM';
SET @doubao_seed_resource_id = 'seed-tts-2.0';
SET @doubao_seed_ws_url = 'wss://openspeech.bytedance.com/api/v3/tts/bidirection';
SET @doubao_seed_default_speaker = 'zh_female_xiaohe_uranus_bigtts';

UPDATE `ai_model_config`
SET
  `model_code` = 'DoubaoSeedTTS2Stream',
  `model_name` = '豆包语音合成模型 2.0',
  `adapter_code` = 'huoshan_double_stream',
  `template_code` = @doubao_seed_template,
  `vendor_name` = '火山引擎',
  `product_name` = '豆包语音合成模型 2.0',
  `protocol` = 'WebSocket',
  `upstream_model_id` = @doubao_seed_resource_id,
  `endpoint_url` = @doubao_seed_ws_url,
  `doc_link` = 'https://www.volcengine.com/docs/6561/1329505',
  `remark` = '火山服务 10035，豆包语音合成模型 2.0。默认音色使用已真实测试通过的 zh_female_xiaohe_uranus_bigtts。',
  `config_json` = JSON_SET(
    COALESCE(`config_json`, JSON_OBJECT()),
    '$.type', 'huoshan_double_stream',
    '$.ws_url', @doubao_seed_ws_url,
    '$.resource_id', @doubao_seed_resource_id,
    '$.speaker', CASE
      WHEN JSON_UNQUOTE(JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.speaker')) IS NULL
        OR TRIM(JSON_UNQUOTE(JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.speaker'))) = ''
        OR JSON_UNQUOTE(JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.speaker')) LIKE '%moon_bigtts'
        THEN @doubao_seed_default_speaker
      ELSE JSON_UNQUOTE(JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.speaker'))
    END,
    '$.format', COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.format')), ''), 'pcm'),
    '$.speech_rate', CASE
      WHEN JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.speech_rate') IS NULL THEN 0
      ELSE JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.speech_rate')
    END,
    '$.loudness_rate', CASE
      WHEN JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.loudness_rate') IS NULL THEN 0
      ELSE JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.loudness_rate')
    END,
    '$.pitch', CASE
      WHEN JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.pitch') IS NULL THEN 0
      ELSE JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.pitch')
    END
  )
WHERE `id` = @doubao_seed_model_id
  AND UPPER(`model_type`) = 'TTS';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT @doubao_seed_model_id, 'ws_url', @doubao_seed_ws_url, 'string', 'endpoint'
WHERE EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @doubao_seed_model_id)
ON DUPLICATE KEY UPDATE `param_value` = VALUES(`param_value`), `value_type` = 'string', `param_group` = 'endpoint';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT @doubao_seed_model_id, 'resource_id', @doubao_seed_resource_id, 'string', 'provider'
WHERE EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @doubao_seed_model_id)
ON DUPLICATE KEY UPDATE `param_value` = VALUES(`param_value`), `value_type` = 'string', `param_group` = 'provider';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT @doubao_seed_model_id, 'speaker', @doubao_seed_default_speaker, 'string', 'audio'
WHERE EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @doubao_seed_model_id)
ON DUPLICATE KEY UPDATE
  `param_value` = CASE
    WHEN `param_value` IS NULL OR TRIM(`param_value`) = '' OR `param_value` LIKE '%moon_bigtts'
      THEN VALUES(`param_value`)
    ELSE `param_value`
  END,
  `value_type` = 'string',
  `param_group` = 'audio';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT @doubao_seed_model_id, 'format', 'pcm', 'string', 'audio'
WHERE EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @doubao_seed_model_id)
ON DUPLICATE KEY UPDATE `value_type` = 'string', `param_group` = 'audio';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT @doubao_seed_model_id, 'speech_rate', '0', 'number', 'audio'
WHERE EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @doubao_seed_model_id)
ON DUPLICATE KEY UPDATE `value_type` = 'number', `param_group` = 'audio';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT @doubao_seed_model_id, 'loudness_rate', '0', 'number', 'audio'
WHERE EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @doubao_seed_model_id)
ON DUPLICATE KEY UPDATE `value_type` = 'number', `param_group` = 'audio';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT @doubao_seed_model_id, 'pitch', '0', 'number', 'audio'
WHERE EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @doubao_seed_model_id)
ON DUPLICATE KEY UPDATE `value_type` = 'number', `param_group` = 'audio';

SET @doubao_bigtts_model_id = 'TTS_DoubaoTTS';
SET @doubao_bigtts_template = 'TTS_DOUBAO_BIGTTS_STREAM';
SET @doubao_bigtts_resource_id = 'volc.service_type.10029';
SET @doubao_bigtts_ws_url = 'wss://openspeech.bytedance.com/api/v3/tts/bidirection';

UPDATE `ai_model_config`
SET
  `model_code` = 'DoubaoBigTTSStream',
  `model_name` = '豆包语音合成大模型',
  `adapter_code` = 'huoshan_double_stream',
  `template_code` = @doubao_bigtts_template,
  `vendor_name` = '火山引擎',
  `product_name` = '豆包语音合成大模型',
  `protocol` = 'WebSocket',
  `upstream_model_id` = @doubao_bigtts_resource_id,
  `endpoint_url` = @doubao_bigtts_ws_url,
  `is_enabled` = CASE WHEN COALESCE(`test_status`, 0) = 1 THEN 1 ELSE `is_enabled` END,
  `is_default` = 0,
  `doc_link` = 'https://www.volcengine.com/docs/6561/1329505',
  `remark` = '火山服务 10007，豆包语音合成大模型。该记录由历史 DoubaoTTS 归并而来；仅在真实测试通过时重新启用。',
  `sort` = 2,
  `config_json` = JSON_SET(
    COALESCE(`config_json`, JSON_OBJECT()),
    '$.type', 'huoshan_double_stream',
    '$.ws_url', @doubao_bigtts_ws_url,
    '$.resource_id', @doubao_bigtts_resource_id,
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
    END,
    '$.enable_ws_reuse', CASE
      WHEN JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.enable_ws_reuse') IS NULL THEN true
      ELSE JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.enable_ws_reuse')
    END
  )
WHERE `id` = @doubao_bigtts_model_id
  AND UPPER(`model_type`) = 'TTS';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT @doubao_bigtts_model_id, 'ws_url', @doubao_bigtts_ws_url, 'string', 'endpoint'
WHERE EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @doubao_bigtts_model_id)
ON DUPLICATE KEY UPDATE `param_value` = VALUES(`param_value`), `value_type` = 'string', `param_group` = 'endpoint';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT @doubao_bigtts_model_id, 'resource_id', @doubao_bigtts_resource_id, 'string', 'provider'
WHERE EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @doubao_bigtts_model_id)
ON DUPLICATE KEY UPDATE `param_value` = VALUES(`param_value`), `value_type` = 'string', `param_group` = 'provider';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT @doubao_bigtts_model_id, 'format', 'pcm', 'string', 'audio'
WHERE EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @doubao_bigtts_model_id)
ON DUPLICATE KEY UPDATE `value_type` = 'string', `param_group` = 'audio';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT @doubao_bigtts_model_id, 'speech_rate', '0', 'number', 'audio'
WHERE EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @doubao_bigtts_model_id)
ON DUPLICATE KEY UPDATE `value_type` = 'number', `param_group` = 'audio';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT @doubao_bigtts_model_id, 'loudness_rate', '0', 'number', 'audio'
WHERE EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @doubao_bigtts_model_id)
ON DUPLICATE KEY UPDATE `value_type` = 'number', `param_group` = 'audio';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT @doubao_bigtts_model_id, 'pitch', '0', 'number', 'audio'
WHERE EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @doubao_bigtts_model_id)
ON DUPLICATE KEY UPDATE `value_type` = 'number', `param_group` = 'audio';

UPDATE `ai_agent`
SET
  `tts_model_id` = @doubao_bigtts_model_id,
  `tts_voice_id` = NULL,
  `updated_at` = NOW()
WHERE `tts_model_id` = 'TTS_EdgeTTS'
  AND EXISTS (
    SELECT 1
    FROM `ai_model_config`
    WHERE `id` = @doubao_bigtts_model_id
      AND `is_enabled` = 1
      AND `test_status` = 1
  );

UPDATE `ai_agent_template`
SET
  `tts_model_id` = @doubao_bigtts_model_id,
  `tts_voice_id` = NULL,
  `updated_at` = NOW()
WHERE `tts_model_id` = 'TTS_EdgeTTS'
  AND EXISTS (
    SELECT 1
    FROM `ai_model_config`
    WHERE `id` = @doubao_bigtts_model_id
      AND `is_enabled` = 1
      AND `test_status` = 1
  );

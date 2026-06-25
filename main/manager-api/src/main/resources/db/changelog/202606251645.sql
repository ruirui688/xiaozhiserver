SET @doubao_seed_model_id = 'TTS_HSDSTTS_V2';
SET @doubao_seed_template = 'TTS_DOUBAO_SEED_TTS_2_STREAM';
SET @doubao_seed_resource_id = 'seed-tts-2.0';
SET @doubao_seed_ws_url = 'wss://openspeech.bytedance.com/api/v3/tts/bidirection';
SET @doubao_seed_default_speaker = 'zh_female_xiaohe_uranus_bigtts';

-- These are historical Doubao/Huoshan TTS entrances. They are intentionally removed
-- instead of being renamed or disabled, so they cannot be selected or tested again.
SET @legacy_tts_doubao = 'TTS_DoubaoTTS';
SET @legacy_tts_huoshan_stream = 'TTS_HuoshanDoubleStreamTTS';
SET @legacy_tts_302ai = 'TTS_TTS302AI';
SET @legacy_tts_gizwits = 'TTS_GizwitsTTS';
SET @legacy_tts_volces_gateway = 'TTS_VolcesAiGatewayTTS';

DELETE FROM `ai_model_template_field`
WHERE `template_id` IN ('TTS_DOUBAO_HTTP', 'TTS_VOLCES_AI_GATEWAY_OPENAI');

DELETE FROM `ai_model_template`
WHERE `template_code` IN ('TTS_DOUBAO_HTTP', 'TTS_VOLCES_AI_GATEWAY_OPENAI');

DELETE FROM `ai_model_provider`
WHERE `id` IN ('SYSTEM_TTS_doubao', 'SYSTEM_TTS_302ai', 'SYSTEM_TTS_gizwits');

UPDATE `ai_model_config`
SET
  `model_code` = 'DoubaoSeedTTS2Stream',
  `model_name` = '豆包语音合成模型 2.0',
  `adapter_code` = 'huoshan_double_stream',
  `template_code` = @doubao_seed_template,
  `vendor_name` = '火山引擎',
  `product_name` = '语音合成模型 2.0（服务 10035）',
  `protocol` = 'WebSocket',
  `upstream_model_id` = @doubao_seed_resource_id,
  `endpoint_url` = @doubao_seed_ws_url,
  `is_default` = 0,
  `is_enabled` = 0,
  `test_status` = 0,
  `test_message` = NULL,
  `test_time` = NULL,
  `doc_link` = 'https://console.volcengine.com/speech/service/10035',
  `remark` = '火山服务 10035，resource_id 固定为 seed-tts-2.0。填写该服务下的 App ID / Access Token 或 API Key，并选择 10035 可用音色后再测试启用。',
  `config_json` = NULL
WHERE `id` = @doubao_seed_model_id
  AND UPPER(`model_type`) = 'TTS';

DELETE FROM `ai_model_config_param`
WHERE `model_id` = @doubao_seed_model_id
  AND `param_key` IN (
    'base_url', 'api_url', 'url', 'host', 'server_url', 'endpoint_url',
    'model_name', 'model', 'resource_id', 'voice', 'cluster', 'authorization',
    'ws_url', 'speaker', 'format', 'audio_params', 'additions', 'mix_speaker',
    'enable_ws_reuse', 'speech_rate', 'loudness_rate', 'pitch', 'emotion',
    'emotion_scale'
  );

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
ON DUPLICATE KEY UPDATE `param_value` = VALUES(`param_value`), `value_type` = 'string', `param_group` = 'audio';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT @doubao_seed_model_id, 'format', 'pcm', 'string', 'audio'
WHERE EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @doubao_seed_model_id)
ON DUPLICATE KEY UPDATE `param_value` = VALUES(`param_value`), `value_type` = 'string', `param_group` = 'audio';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT @doubao_seed_model_id, 'audio_params', '{"speech_rate":0,"loudness_rate":0}', 'json', 'advanced'
WHERE EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @doubao_seed_model_id)
ON DUPLICATE KEY UPDATE `param_value` = VALUES(`param_value`), `value_type` = 'json', `param_group` = 'advanced';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT @doubao_seed_model_id, 'enable_ws_reuse', 'true', 'boolean', 'advanced'
WHERE EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @doubao_seed_model_id)
ON DUPLICATE KEY UPDATE `param_value` = VALUES(`param_value`), `value_type` = 'boolean', `param_group` = 'advanced';

UPDATE `ai_agent`
SET `tts_model_id` = NULL,
    `tts_voice_id` = NULL,
    `updated_at` = NOW()
WHERE `tts_model_id` IN (
  @legacy_tts_doubao,
  @legacy_tts_huoshan_stream,
  @legacy_tts_302ai,
  @legacy_tts_gizwits,
  @legacy_tts_volces_gateway
);

UPDATE `ai_agent_template`
SET `tts_model_id` = NULL,
    `tts_voice_id` = NULL,
    `updated_at` = NOW()
WHERE `tts_model_id` IN (
  @legacy_tts_doubao,
  @legacy_tts_huoshan_stream,
  @legacy_tts_302ai,
  @legacy_tts_gizwits,
  @legacy_tts_volces_gateway
);

DELETE FROM `ai_model_config_param`
WHERE `model_id` IN (
  @legacy_tts_doubao,
  @legacy_tts_huoshan_stream,
  @legacy_tts_302ai,
  @legacy_tts_gizwits,
  @legacy_tts_volces_gateway
);

DELETE FROM `ai_model_config_secret`
WHERE `model_id` IN (
  @legacy_tts_doubao,
  @legacy_tts_huoshan_stream,
  @legacy_tts_302ai,
  @legacy_tts_gizwits,
  @legacy_tts_volces_gateway
);

DELETE FROM `ai_tts_voice`
WHERE `tts_model_id` IN (
  @legacy_tts_doubao,
  @legacy_tts_huoshan_stream,
  @legacy_tts_302ai,
  @legacy_tts_gizwits,
  @legacy_tts_volces_gateway
);

DELETE FROM `ai_model_config`
WHERE `id` IN (
  @legacy_tts_doubao,
  @legacy_tts_huoshan_stream,
  @legacy_tts_302ai,
  @legacy_tts_gizwits,
  @legacy_tts_volces_gateway
)
  AND UPPER(`model_type`) = 'TTS';

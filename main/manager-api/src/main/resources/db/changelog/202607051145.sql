SET @seed_tts_model_id = 'TTS_HSDSTTS_V2';
SET @seed_tts_template = 'TTS_DOUBAO_SEED_TTS_2_STREAM';
SET @seed_tts_resource_id = 'seed-tts-2.0';
SET @seed_tts_ws_url = 'wss://openspeech.bytedance.com/api/v3/tts/bidirection';
SET @seed_tts_default_speaker = 'zh_female_sophie_uranus_bigtts';
SET @seed_tts_doc = 'https://www.volcengine.com/docs/6561/2532486';

UPDATE `ai_model_provider`
SET
  `name` = '豆包语音合成模型 2.0',
  `fields` = JSON_ARRAY(
    JSON_OBJECT('key', 'api_key', 'type', 'string', 'label', 'API Key'),
    JSON_OBJECT('key', 'speaker', 'type', 'string', 'label', '音色 Voice_type', 'default', @seed_tts_default_speaker),
    JSON_OBJECT('key', 'speech_rate', 'type', 'number', 'label', '语速(-50~100)', 'default', 0),
    JSON_OBJECT('key', 'loudness_rate', 'type', 'number', 'label', '音量(-50~100)', 'default', 0),
    JSON_OBJECT('key', 'pitch', 'type', 'number', 'label', '音高(-12~12)', 'default', 0)
  ),
  `updater` = 1,
  `update_date` = NOW()
WHERE `id` = 'SYSTEM_TTS_HSDSTTS_V2';

UPDATE `ai_model_config`
SET
  `model_code` = 'DoubaoSeedTTS2Stream',
  `model_name` = '豆包语音合成模型 2.0',
  `adapter_code` = 'huoshan_double_stream',
  `template_code` = @seed_tts_template,
  `vendor_name` = '火山引擎',
  `product_name` = '豆包语音合成模型 2.0',
  `protocol` = 'WebSocket',
  `upstream_model_id` = @seed_tts_resource_id,
  `endpoint_url` = @seed_tts_ws_url,
  `doc_link` = @seed_tts_doc,
  `remark` = '豆包语音合成模型 2.0。运行时固定使用 seed-tts-2.0、官方双向流式 WebSocket、PCM 输出和连接复用；配置界面只保留 API Key、音色、语速、音量和音高。',
  `config_json` = NULL,
  `updater` = 1,
  `update_date` = NOW()
WHERE `id` = @seed_tts_model_id
  AND UPPER(`model_type`) = 'TTS';

DELETE FROM `ai_model_config_param`
WHERE `model_id` = @seed_tts_model_id
  AND `param_key` IN ('ws_url', 'resource_id', 'format', 'audio_params', 'additions', 'enable_ws_reuse');

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT @seed_tts_model_id, 'speaker', @seed_tts_default_speaker, 'string', 'common'
WHERE EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @seed_tts_model_id)
  AND NOT EXISTS (
    SELECT 1 FROM `ai_model_config_param`
    WHERE `model_id` = @seed_tts_model_id
      AND `param_key` = 'speaker'
      AND NULLIF(TRIM(`param_value`), '') IS NOT NULL
  )
ON DUPLICATE KEY UPDATE `param_value` = VALUES(`param_value`), `value_type` = 'string', `param_group` = 'common';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
VALUES
(@seed_tts_model_id, 'speech_rate', '0', 'number', 'common'),
(@seed_tts_model_id, 'loudness_rate', '0', 'number', 'common'),
(@seed_tts_model_id, 'pitch', '0', 'number', 'common')
ON DUPLICATE KEY UPDATE
  `param_value` = VALUES(`param_value`),
  `value_type` = VALUES(`value_type`),
  `param_group` = VALUES(`param_group`);

UPDATE `ai_model_config_param`
SET `param_group` = 'common',
    `value_type` = CASE WHEN `param_key` IN ('speech_rate', 'loudness_rate', 'pitch') THEN 'number' ELSE `value_type` END
WHERE `model_id` = @seed_tts_model_id
  AND `param_key` IN ('speaker', 'speech_rate', 'loudness_rate', 'pitch');

DELETE FROM `ai_model_template_field`
WHERE `template_id` = @seed_tts_template;

INSERT INTO `ai_model_template_field`
(`template_id`, `field_key`, `field_label`, `field_group`, `value_type`, `input_type`, `runtime_key`, `advanced`, `visible_when`, `required`, `secret`, `default_value`, `options_json`, `help_text`, `sort`)
VALUES
(@seed_tts_template, 'api_key', 'API Key', 'credentials', 'string', 'password', 'api_key', 0, NULL, 1, 1, NULL, NULL, '火山控制台 API Key。运行时作为 X-Api-Key 请求头发送。', 1),
(@seed_tts_template, 'speaker', '音色 Voice_type', 'common', 'string', 'text', 'speaker', 0, NULL, 0, 0, @seed_tts_default_speaker, NULL, '填写服务 10035 已开通音色的 Voice_type，例如 zh_female_sophie_uranus_bigtts。留空时使用默认音色。', 2),
(@seed_tts_template, 'speech_rate', '语速', 'common', 'number', 'number', 'speech_rate', 0, NULL, 0, 0, '0', NULL, '范围 -50 到 100。', 3),
(@seed_tts_template, 'loudness_rate', '音量', 'common', 'number', 'number', 'loudness_rate', 0, NULL, 0, 0, '0', NULL, '范围 -50 到 100。', 4),
(@seed_tts_template, 'pitch', '音高', 'common', 'number', 'number', 'pitch', 0, NULL, 0, 0, '0', NULL, '范围 -12 到 12。', 5);

UPDATE `ai_model_template`
SET
  `adapter_code` = 'huoshan_double_stream',
  `vendor_name` = '火山引擎',
  `product_name` = '豆包语音合成模型 2.0',
  `capability` = 'tts',
  `access_mode` = 'websocket-bidirectional',
  `deployment` = 'cloud',
  `protocol` = 'WebSocket',
  `upstream_model_field` = 'resource_id',
  `doc_link` = @seed_tts_doc,
  `enabled` = 1,
  `supported_status` = 'SUPPORTED',
  `selectable` = 1,
  `hidden` = 0,
  `deprecated` = 0,
  `recommended_for` = '唯一保留的豆包语音合成模型。',
  `avoid_when` = NULL,
  `test_contract` = '必须真实生成可播放音频并记录耗时',
  `source_checked_at` = '2026-07-05',
  `setup_hints_json` = JSON_ARRAY(
    '按火山文档 https://www.volcengine.com/docs/6561/2532486 接入豆包语音合成模型 2.0',
    '只填写 API Key 和服务 10035 可用音色',
    'resource_id 固定为 seed-tts-2.0，WebSocket 地址、PCM 输出和连接复用由运行时固定管理'
  ),
  `known_limitations_json` = JSON_ARRAY('音色必须来自已开通服务 10035 的可用 Voice_type。'),
  `aliases_json` = JSON_ARRAY('seed-tts-2.0', 'TTS_HSDSTTS_V2')
WHERE `template_code` = @seed_tts_template;

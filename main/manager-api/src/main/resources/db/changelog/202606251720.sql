SET @seed_tts_model_id = 'TTS_HSDSTTS_V2';
SET @seed_tts_template = 'TTS_DOUBAO_SEED_TTS_2_STREAM';
SET @seed_tts_resource_id = 'seed-tts-2.0';
SET @seed_tts_ws_url = 'wss://openspeech.bytedance.com/api/v3/tts/bidirection';
SET @seed_tts_default_speaker = 'zh_female_sophie_uranus_bigtts';
SET @seed_tts_doc = 'https://www.volcengine.com/docs/6561/2532486';

INSERT INTO `ai_model_provider` (`id`, `model_type`, `provider_code`, `name`, `fields`, `sort`, `creator`, `create_date`, `updater`, `update_date`)
VALUES (
  'SYSTEM_TTS_HSDSTTS_V2',
  'TTS',
  'huoshan_double_stream',
  '豆包语音合成模型 2.0',
  JSON_ARRAY(
    JSON_OBJECT('key', 'ws_url', 'type', 'string', 'label', 'WebSocket地址', 'default', @seed_tts_ws_url),
    JSON_OBJECT('key', 'api_key', 'type', 'string', 'label', 'API Key'),
    JSON_OBJECT('key', 'resource_id', 'type', 'string', 'label', 'Resource ID', 'default', @seed_tts_resource_id),
    JSON_OBJECT('key', 'speaker', 'type', 'string', 'label', '音色 Voice_type', 'default', @seed_tts_default_speaker),
    JSON_OBJECT('key', 'format', 'type', 'string', 'label', '音频格式', 'default', 'pcm'),
    JSON_OBJECT('key', 'audio_params', 'type', 'dict', 'label', '音频输出配置'),
    JSON_OBJECT('key', 'additions', 'type', 'dict', 'label', '高级文本处理配置'),
    JSON_OBJECT('key', 'enable_ws_reuse', 'type', 'boolean', 'label', '是否开启连接复用', 'default', TRUE)
  ),
  14,
  1,
  NOW(),
  1,
  NOW()
)
ON DUPLICATE KEY UPDATE
  `model_type` = VALUES(`model_type`),
  `provider_code` = VALUES(`provider_code`),
  `name` = VALUES(`name`),
  `fields` = VALUES(`fields`),
  `sort` = VALUES(`sort`),
  `updater` = VALUES(`updater`),
  `update_date` = VALUES(`update_date`);

INSERT INTO `ai_model_config` (
  `id`, `model_type`, `model_code`, `model_name`, `adapter_code`, `template_code`,
  `vendor_name`, `product_name`, `protocol`, `upstream_model_id`, `endpoint_url`,
  `is_default`, `is_enabled`, `test_status`, `config_json`, `doc_link`, `remark`,
  `sort`, `creator`, `create_date`, `updater`, `update_date`
)
VALUES (
  @seed_tts_model_id, 'TTS', 'DoubaoSeedTTS2Stream', '豆包语音合成模型 2.0',
  'huoshan_double_stream', @seed_tts_template, '火山引擎', '豆包语音合成模型 2.0',
  'WebSocket', @seed_tts_resource_id, @seed_tts_ws_url, 0, 0, 0, NULL, @seed_tts_doc,
  '唯一保留的 TTS 配置。按火山文档接入 seed-tts-2.0，填写 API Key，并选择 10035 可用音色后测试启用。',
  10, 1, NOW(), 1, NOW()
)
ON DUPLICATE KEY UPDATE `id` = VALUES(`id`);

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
  `remark` = '唯一保留的 TTS 配置。按火山文档接入 seed-tts-2.0，只填写 API Key；音色必须是 10035 可用 Voice_type。',
  `config_json` = NULL,
  `is_default` = 0,
  `is_enabled` = 0,
  `test_status` = 0,
  `test_message` = NULL,
  `test_time` = NULL
WHERE `id` = @seed_tts_model_id
  AND UPPER(`model_type`) = 'TTS';

DELETE FROM `ai_model_config_param`
WHERE `model_id` = @seed_tts_model_id;

DELETE FROM `ai_model_config_secret`
WHERE `model_id` = @seed_tts_model_id
  AND `secret_key` <> 'api_key';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT @seed_tts_model_id, 'ws_url', @seed_tts_ws_url, 'string', 'endpoint'
WHERE EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @seed_tts_model_id)
ON DUPLICATE KEY UPDATE `param_value` = VALUES(`param_value`), `value_type` = 'string', `param_group` = 'endpoint';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT @seed_tts_model_id, 'resource_id', @seed_tts_resource_id, 'string', 'provider'
WHERE EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @seed_tts_model_id)
ON DUPLICATE KEY UPDATE `param_value` = VALUES(`param_value`), `value_type` = 'string', `param_group` = 'provider';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT @seed_tts_model_id, 'speaker', @seed_tts_default_speaker, 'string', 'audio'
WHERE EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @seed_tts_model_id)
ON DUPLICATE KEY UPDATE `param_value` = VALUES(`param_value`), `value_type` = 'string', `param_group` = 'audio';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT @seed_tts_model_id, 'format', 'pcm', 'string', 'audio'
WHERE EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @seed_tts_model_id)
ON DUPLICATE KEY UPDATE `param_value` = VALUES(`param_value`), `value_type` = 'string', `param_group` = 'audio';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT @seed_tts_model_id, 'audio_params', '{"speech_rate":0,"loudness_rate":0}', 'json', 'advanced'
WHERE EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @seed_tts_model_id)
ON DUPLICATE KEY UPDATE `param_value` = VALUES(`param_value`), `value_type` = 'json', `param_group` = 'advanced';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT @seed_tts_model_id, 'enable_ws_reuse', 'true', 'boolean', 'advanced'
WHERE EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @seed_tts_model_id)
ON DUPLICATE KEY UPDATE `param_value` = VALUES(`param_value`), `value_type` = 'boolean', `param_group` = 'advanced';

UPDATE `ai_agent`
SET `tts_model_id` = @seed_tts_model_id,
    `tts_voice_id` = NULL,
    `updated_at` = NOW()
WHERE `tts_model_id` IS NOT NULL
  AND `tts_model_id` <> @seed_tts_model_id;

UPDATE `ai_agent_template`
SET `tts_model_id` = @seed_tts_model_id,
    `tts_voice_id` = NULL,
    `updated_at` = NOW()
WHERE `tts_model_id` IS NOT NULL
  AND `tts_model_id` <> @seed_tts_model_id;

DELETE p
FROM `ai_model_config_param` p
JOIN `ai_model_config` mc ON mc.`id` = p.`model_id`
WHERE UPPER(mc.`model_type`) = 'TTS'
  AND mc.`id` <> @seed_tts_model_id;

DELETE s
FROM `ai_model_config_secret` s
JOIN `ai_model_config` mc ON mc.`id` = s.`model_id`
WHERE UPPER(mc.`model_type`) = 'TTS'
  AND mc.`id` <> @seed_tts_model_id;

DELETE v
FROM `ai_tts_voice` v
JOIN `ai_model_config` mc ON mc.`id` = v.`tts_model_id`
WHERE UPPER(mc.`model_type`) = 'TTS'
  AND mc.`id` <> @seed_tts_model_id;

DELETE FROM `ai_model_config`
WHERE UPPER(`model_type`) = 'TTS'
  AND `id` <> @seed_tts_model_id;

DELETE FROM `ai_model_template_field`
WHERE `template_id` IN (
  SELECT `template_code`
  FROM `ai_model_template`
  WHERE UPPER(`model_type`) = 'TTS'
    AND `template_code` <> @seed_tts_template
);

DELETE FROM `ai_model_template`
WHERE UPPER(`model_type`) = 'TTS'
  AND `template_code` <> @seed_tts_template;

DELETE FROM `ai_model_provider`
WHERE UPPER(`model_type`) = 'TTS'
  AND `id` <> 'SYSTEM_TTS_HSDSTTS_V2';

DELETE FROM `ai_model_template_field`
WHERE `template_id` = @seed_tts_template;

INSERT INTO `ai_model_template_field`
(`template_id`, `field_key`, `field_label`, `field_group`, `value_type`, `input_type`, `runtime_key`, `advanced`, `visible_when`, `required`, `secret`, `default_value`, `options_json`, `help_text`, `sort`)
VALUES
(@seed_tts_template, 'api_key', 'API Key', 'credentials', 'string', 'password', 'api_key', 0, NULL, 1, 1, NULL, NULL, '火山控制台 API Key。作为 X-Api-Key 请求头发送。', 1),
(@seed_tts_template, 'ws_url', 'WebSocket 地址', 'endpoint', 'string', 'text', 'ws_url', 1, NULL, 1, 0, @seed_tts_ws_url, NULL, '固定双向流式地址，无需修改。', 2),
(@seed_tts_template, 'resource_id', 'Resource ID', 'upstream', 'string', 'text', 'resource_id', 0, NULL, 1, 0, @seed_tts_resource_id, NULL, '固定填写 seed-tts-2.0。', 3),
(@seed_tts_template, 'speaker', '音色 Voice_type', 'audio', 'string', 'text', 'speaker', 0, NULL, 1, 0, @seed_tts_default_speaker, NULL, '填写服务 10035 已开通音色的 Voice_type，例如 zh_female_sophie_uranus_bigtts。', 4),
(@seed_tts_template, 'format', '音频格式', 'advanced', 'string', 'select', 'format', 1, NULL, 0, 0, 'pcm', JSON_ARRAY('pcm', 'wav', 'mp3', 'ogg_opus'), '设备实时播报推荐 pcm；单独探测可用 mp3。', 10),
(@seed_tts_template, 'audio_params', '音频参数 JSON', 'advanced', 'json', 'textarea', 'audio_params', 1, NULL, 0, 0, '{"speech_rate":0,"loudness_rate":0}', NULL, '语速、音量、采样率等音频参数。', 11),
(@seed_tts_template, 'additions', '高级处理 JSON', 'advanced', 'json', 'textarea', 'additions', 1, NULL, 0, 0, '{"post_process":{"pitch":0}}', NULL, '火山 additions 透传；普通用户无需修改。', 12),
(@seed_tts_template, 'enable_ws_reuse', '复用 WebSocket', 'advanced', 'boolean', 'switch', 'enable_ws_reuse', 1, NULL, 0, 0, 'true', NULL, '开启后降低首包延迟，空闲连接会占用并发。', 13),
(@seed_tts_template, 'speech_rate', '语速', 'advanced', 'number', 'number', 'speech_rate', 1, NULL, 0, 0, '0', NULL, '范围 -50 到 100。', 14),
(@seed_tts_template, 'loudness_rate', '音量', 'advanced', 'number', 'number', 'loudness_rate', 1, NULL, 0, 0, '0', NULL, '范围 -50 到 100。', 15),
(@seed_tts_template, 'pitch', '音高', 'advanced', 'number', 'number', 'pitch', 1, NULL, 0, 0, '0', NULL, '范围 -12 到 12。', 16);

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
  `recommended_for` = '唯一保留的语音合成模型。',
  `avoid_when` = NULL,
  `test_contract` = '必须真实生成可播放音频并记录耗时',
  `source_checked_at` = '2026-06-25',
  `setup_hints_json` = JSON_ARRAY(
    '按火山文档 https://www.volcengine.com/docs/6561/2532486 接入豆包语音合成模型 2.0',
    '只填写 API Key，对应 WebSocket 请求头 X-Api-Key',
    'resource_id 固定为 seed-tts-2.0'
  ),
  `known_limitations_json` = JSON_ARRAY('音色必须来自已开通服务的可用音色列表。'),
  `aliases_json` = JSON_ARRAY('seed-tts-2.0', 'TTS_HSDSTTS_V2')
WHERE `template_code` = @seed_tts_template;

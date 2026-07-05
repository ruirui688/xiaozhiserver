SET @doubao_big_model_id = 'TTS_HuoshanDoubleStreamTTS';
SET @doubao_big_template = 'TTS_DOUBAO_BIGTTS_STREAM';
SET @doubao_big_resource_id = 'volc.service_type.10029';
SET @doubao_big_default_speaker = 'zh_female_wanwanxiaohe_moon_bigtts';
SET @doubao_big_doc = 'https://www.volcengine.com/docs/6561/1329505';
SET @doubao_seed_model_id = 'TTS_HSDSTTS_V2';
SET @doubao_seed_template = 'TTS_DOUBAO_SEED_TTS_2_STREAM';
SET @doubao_seed_resource_id = 'seed-tts-2.0';
SET @doubao_seed_default_speaker = 'zh_female_sophie_uranus_bigtts';
SET @doubao_seed_doc = 'https://www.volcengine.com/docs/6561/2532486';
SET @doubao_ws_url = 'wss://openspeech.bytedance.com/api/v3/tts/bidirection';

UPDATE `ai_model_provider`
SET
  `name` = '豆包语音合成',
  `fields` = JSON_ARRAY(
    JSON_OBJECT('key', 'api_key', 'type', 'string', 'label', 'API Key'),
    JSON_OBJECT('key', 'speaker', 'type', 'string', 'label', '音色 Voice_type'),
    JSON_OBJECT('key', 'speech_rate', 'type', 'number', 'label', '语速(-50~100)', 'default', 0),
    JSON_OBJECT('key', 'loudness_rate', 'type', 'number', 'label', '音量(-50~100)', 'default', 0),
    JSON_OBJECT('key', 'pitch', 'type', 'number', 'label', '音高(-12~12)', 'default', 0)
  ),
  `updater` = 1,
  `update_date` = NOW()
WHERE `id` = 'SYSTEM_TTS_HSDSTTS_V2';

UPDATE `ai_model_config`
SET
  `model_code` = 'DoubaoBigTTSStream',
  `model_name` = '语音合成大模型',
  `adapter_code` = 'huoshan_double_stream',
  `template_code` = @doubao_big_template,
  `vendor_name` = '火山引擎',
  `product_name` = '语音合成大模型',
  `protocol` = 'WebSocket',
  `upstream_model_id` = @doubao_big_resource_id,
  `endpoint_url` = @doubao_ws_url,
  `doc_link` = @doubao_big_doc,
  `remark` = '语音合成大模型。运行时固定使用 volc.service_type.10029、官方双向流式 WebSocket、PCM 输出和连接复用；配置界面只保留 API Key、音色、语速、音量和音高。',
  `config_json` = NULL,
  `updater` = 1,
  `update_date` = NOW()
WHERE `id` = @doubao_big_model_id
  AND UPPER(`model_type`) = 'TTS';

INSERT INTO `ai_model_config` (
  `id`, `model_type`, `model_code`, `model_name`, `adapter_code`, `template_code`,
  `vendor_name`, `product_name`, `protocol`, `upstream_model_id`, `endpoint_url`,
  `is_default`, `is_enabled`, `test_status`, `config_json`, `doc_link`, `remark`,
  `sort`, `creator`, `create_date`, `updater`, `update_date`
)
SELECT
  @doubao_big_model_id, 'TTS', 'DoubaoBigTTSStream', '语音合成大模型',
  'huoshan_double_stream', @doubao_big_template, '火山引擎', '语音合成大模型',
  'WebSocket', @doubao_big_resource_id, @doubao_ws_url, 0, 0, 0, NULL, @doubao_big_doc,
  '语音合成大模型。运行时固定使用 volc.service_type.10029，配置界面只保留 API Key、音色、语速、音量和音高。',
  11, 1, NOW(), 1, NOW()
WHERE NOT EXISTS (SELECT 1 FROM `ai_model_config` WHERE `id` = @doubao_big_model_id);

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
  `endpoint_url` = @doubao_ws_url,
  `doc_link` = @doubao_seed_doc,
  `remark` = '豆包语音合成模型 2.0。运行时固定使用 seed-tts-2.0、官方双向流式 WebSocket、PCM 输出和连接复用；配置界面只保留 API Key、音色、语速、音量和音高。',
  `config_json` = NULL,
  `updater` = 1,
  `update_date` = NOW()
WHERE `id` = @doubao_seed_model_id
  AND UPPER(`model_type`) = 'TTS';

DELETE FROM `ai_model_config_param`
WHERE `model_id` IN (@doubao_big_model_id, @doubao_seed_model_id)
  AND `param_key` IN ('ws_url', 'api_url', 'url', 'resource_id', 'format', 'audio_params', 'additions', 'enable_ws_reuse', 'speaker', 'voice', 'cluster', 'speech_rate', 'loudness_rate', 'pitch');

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
VALUES
(@doubao_big_model_id, 'resource_id', @doubao_big_resource_id, 'string', 'provider'),
(@doubao_big_model_id, 'speaker', @doubao_big_default_speaker, 'string', 'common'),
(@doubao_big_model_id, 'speech_rate', '0', 'number', 'common'),
(@doubao_big_model_id, 'loudness_rate', '0', 'number', 'common'),
(@doubao_big_model_id, 'pitch', '0', 'number', 'common'),
(@doubao_seed_model_id, 'resource_id', @doubao_seed_resource_id, 'string', 'provider'),
(@doubao_seed_model_id, 'speaker', @doubao_seed_default_speaker, 'string', 'common'),
(@doubao_seed_model_id, 'speech_rate', '0', 'number', 'common'),
(@doubao_seed_model_id, 'loudness_rate', '0', 'number', 'common'),
(@doubao_seed_model_id, 'pitch', '0', 'number', 'common')
ON DUPLICATE KEY UPDATE
  `param_value` = VALUES(`param_value`),
  `value_type` = VALUES(`value_type`),
  `param_group` = VALUES(`param_group`);

DELETE FROM `ai_model_template_field`
WHERE `template_id` IN (@doubao_big_template, @doubao_seed_template);

INSERT INTO `ai_model_template_field`
(`template_id`, `field_key`, `field_label`, `field_group`, `value_type`, `input_type`, `runtime_key`, `advanced`, `visible_when`, `required`, `secret`, `default_value`, `options_json`, `help_text`, `sort`)
VALUES
(@doubao_big_template, 'api_key', 'API Key', 'credentials', 'string', 'password', 'api_key', 0, NULL, 1, 1, NULL, NULL, '火山控制台 API Key。运行时作为 X-Api-Key 请求头发送。', 1),
(@doubao_big_template, 'speaker', '音色 Voice_type', 'common', 'string', 'text', 'speaker', 0, NULL, 0, 0, @doubao_big_default_speaker, NULL, '填写服务 10029 已开通音色的 Voice_type。留空时使用默认音色。', 2),
(@doubao_big_template, 'speech_rate', '语速', 'common', 'number', 'number', 'speech_rate', 0, NULL, 0, 0, '0', NULL, '范围 -50 到 100。', 3),
(@doubao_big_template, 'loudness_rate', '音量', 'common', 'number', 'number', 'loudness_rate', 0, NULL, 0, 0, '0', NULL, '范围 -50 到 100。', 4),
(@doubao_big_template, 'pitch', '音高', 'common', 'number', 'number', 'pitch', 0, NULL, 0, 0, '0', NULL, '范围 -12 到 12。', 5),
(@doubao_seed_template, 'api_key', 'API Key', 'credentials', 'string', 'password', 'api_key', 0, NULL, 1, 1, NULL, NULL, '火山控制台 API Key。运行时作为 X-Api-Key 请求头发送。', 1),
(@doubao_seed_template, 'speaker', '音色 Voice_type', 'common', 'string', 'text', 'speaker', 0, NULL, 0, 0, @doubao_seed_default_speaker, NULL, '填写服务 10035 已开通音色的 Voice_type。留空时使用默认音色。', 2),
(@doubao_seed_template, 'speech_rate', '语速', 'common', 'number', 'number', 'speech_rate', 0, NULL, 0, 0, '0', NULL, '范围 -50 到 100。', 3),
(@doubao_seed_template, 'loudness_rate', '音量', 'common', 'number', 'number', 'loudness_rate', 0, NULL, 0, 0, '0', NULL, '范围 -50 到 100。', 4),
(@doubao_seed_template, 'pitch', '音高', 'common', 'number', 'number', 'pitch', 0, NULL, 0, 0, '0', NULL, '范围 -12 到 12。', 5);

UPDATE `ai_model_template`
SET
  `adapter_code` = 'huoshan_double_stream',
  `vendor_name` = '火山引擎',
  `product_name` = CASE WHEN `template_code` = @doubao_big_template THEN '语音合成大模型' ELSE '豆包语音合成模型 2.0' END,
  `capability` = 'tts',
  `access_mode` = 'websocket-bidirectional',
  `deployment` = 'cloud',
  `protocol` = 'WebSocket',
  `upstream_model_field` = 'resource_id',
  `doc_link` = CASE WHEN `template_code` = @doubao_big_template THEN @doubao_big_doc ELSE @doubao_seed_doc END,
  `enabled` = 1,
  `supported_status` = 'SUPPORTED',
  `selectable` = 1,
  `hidden` = 0,
  `deprecated` = 0,
  `recommended_for` = '实时设备播报、低首包延迟语音合成。',
  `avoid_when` = NULL,
  `test_contract` = '必须真实生成可播放音频并记录耗时',
  `source_checked_at` = '2026-07-05',
  `setup_hints_json` = CASE
    WHEN `template_code` = @doubao_big_template THEN JSON_ARRAY('按火山文档 https://www.volcengine.com/docs/6561/1329505 接入语音合成大模型', '只填写 API Key 和服务 10029 可用音色', 'resource_id 固定为 volc.service_type.10029，WebSocket 地址、PCM 输出和连接复用由运行时固定管理')
    ELSE JSON_ARRAY('按火山文档 https://www.volcengine.com/docs/6561/2532486 接入豆包语音合成模型 2.0', '只填写 API Key 和服务 10035 可用音色', 'resource_id 固定为 seed-tts-2.0，WebSocket 地址、PCM 输出和连接复用由运行时固定管理')
  END,
  `known_limitations_json` = JSON_ARRAY('音色必须来自已开通服务的可用 Voice_type。'),
  `aliases_json` = CASE
    WHEN `template_code` = @doubao_big_template THEN JSON_ARRAY('volc.service_type.10029', 'TTS_HSDSTTS')
    ELSE JSON_ARRAY('seed-tts-2.0', 'TTS_HSDSTTS_V2')
  END
WHERE `template_code` IN (@doubao_big_template, @doubao_seed_template);

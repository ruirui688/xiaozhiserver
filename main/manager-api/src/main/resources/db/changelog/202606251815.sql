SET @seed_tts_model_id = 'TTS_HSDSTTS_V2';
SET @seed_tts_template = 'TTS_DOUBAO_SEED_TTS_2_STREAM';
SET @seed_tts_resource_id = 'seed-tts-2.0';
SET @seed_tts_ws_url = 'wss://openspeech.bytedance.com/api/v3/tts/bidirection';
SET @seed_tts_speaker = 'zh_female_sophie_uranus_bigtts';

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
  `config_json` = NULL,
  `test_status` = 0,
  `test_message` = NULL,
  `test_time` = NULL,
  `update_date` = NOW()
WHERE `id` = @seed_tts_model_id
  AND UPPER(`model_type`) = 'TTS';

DELETE FROM `ai_model_config_param`
WHERE `model_id` = @seed_tts_model_id
  AND `param_key` IN ('voice', 'voice_id', 'model', 'model_name', 'appid', 'access_token', 'mix_speaker');

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
VALUES
(@seed_tts_model_id, 'ws_url', @seed_tts_ws_url, 'string', 'endpoint'),
(@seed_tts_model_id, 'resource_id', @seed_tts_resource_id, 'string', 'provider'),
(@seed_tts_model_id, 'speaker', @seed_tts_speaker, 'string', 'audio'),
(@seed_tts_model_id, 'sample_rate', '24000', 'number', 'audio'),
(@seed_tts_model_id, 'format', 'pcm', 'string', 'audio')
ON DUPLICATE KEY UPDATE
  `param_value` = VALUES(`param_value`),
  `value_type` = VALUES(`value_type`),
  `param_group` = VALUES(`param_group`);

DELETE FROM `ai_model_config_secret`
WHERE `model_id` = @seed_tts_model_id
  AND `secret_key` <> 'api_key';

DELETE FROM `ai_tts_voice`
WHERE `tts_model_id` = @seed_tts_model_id;

INSERT INTO `ai_tts_voice`
(`id`, `tts_model_id`, `name`, `tts_voice`, `languages`, `voice_demo`, `remark`, `reference_audio`, `reference_text`, `sort`, `creator`, `create_date`, `updater`, `update_date`)
VALUES
('TTS_HSDSTTS_V2_SOPHIE', @seed_tts_model_id, '魅力苏菲', @seed_tts_speaker, '普通话、英语', NULL, '唯一保留音色，和 Seed TTS 2.0 探测脚本一致。', NULL, NULL, 1, 1, NOW(), 1, NOW())
ON DUPLICATE KEY UPDATE
  `name` = VALUES(`name`),
  `tts_voice` = VALUES(`tts_voice`),
  `languages` = VALUES(`languages`),
  `remark` = VALUES(`remark`),
  `sort` = VALUES(`sort`),
  `updater` = VALUES(`updater`),
  `update_date` = VALUES(`update_date`);

UPDATE `ai_model_template_field`
SET `default_value` = @seed_tts_speaker,
    `help_text` = '固定使用已验证通过的 Voice_type：zh_female_sophie_uranus_bigtts。'
WHERE `template_id` = @seed_tts_template
  AND `field_key` = 'speaker';

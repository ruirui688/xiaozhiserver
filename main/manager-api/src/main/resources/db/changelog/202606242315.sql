DELETE FROM `ai_model_template_field`
WHERE `template_id` IN ('TTS_DOUBAO_BIGTTS_STREAM', 'TTS_DOUBAO_SEED_TTS_2_STREAM')
  AND `field_key` = 'api_key';

INSERT INTO `ai_model_template_field`
(`template_id`, `field_key`, `field_label`, `field_group`, `value_type`, `input_type`, `runtime_key`, `advanced`, `visible_when`, `required`, `secret`, `default_value`, `options_json`, `help_text`, `sort`)
VALUES
('TTS_DOUBAO_BIGTTS_STREAM', 'api_key', 'API Key', 'credentials', 'string', 'password', 'api_key', 0, NULL, 1, 1, NULL, NULL, '火山新版控制台“快捷 API 接入”中的 API Key；填写后运行时使用 X-Api-Key 鉴权。', 2),
('TTS_DOUBAO_SEED_TTS_2_STREAM', 'api_key', 'API Key', 'credentials', 'string', 'password', 'api_key', 0, NULL, 1, 1, NULL, NULL, '火山新版控制台“快捷 API 接入”中的 API Key；填写后运行时使用 X-Api-Key 鉴权。', 2);

UPDATE `ai_model_template_field`
SET `sort` = 6
WHERE `template_id` IN ('TTS_DOUBAO_BIGTTS_STREAM', 'TTS_DOUBAO_SEED_TTS_2_STREAM')
  AND `field_key` = 'appid';

UPDATE `ai_model_template_field`
SET `sort` = 7,
    `required` = 0,
    `advanced` = 1,
    `field_group` = 'advanced',
    `help_text` = '旧版 App ID 接入使用；新版控制台请优先填写 API Key。'
WHERE `template_id` IN ('TTS_DOUBAO_BIGTTS_STREAM', 'TTS_DOUBAO_SEED_TTS_2_STREAM')
  AND `field_key` = 'access_token';


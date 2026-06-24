UPDATE `ai_model_template`
SET `adapter_code` = 'doubao_stream',
    `upstream_model_field` = 'resource_id',
    `protocol` = 'WebSocket',
    `doc_link` = 'https://www.volcengine.com/docs/6561/1354869',
    `setup_hints_json` = JSON_ARRAY('新版豆包语音识别控制台使用 API Key 接入，请在 API Key 管理页复制 API Key。', '旧版 App ID + Access Token 仍作为兼容字段保留，但新版服务优先填写 API Key。')
WHERE `template_code` IN ('ASR_DOUBAO_BIGASR_STREAM', 'ASR_DOUBAO_SEED_ASR_2_STREAM');

DELETE FROM `ai_model_template_field`
WHERE `template_id` IN ('ASR_DOUBAO_BIGASR_STREAM', 'ASR_DOUBAO_SEED_ASR_2_STREAM');

INSERT INTO `ai_model_template_field`
(`template_id`, `field_key`, `field_label`, `field_group`, `value_type`, `input_type`, `runtime_key`, `advanced`, `visible_when`, `required`, `secret`, `default_value`, `options_json`, `help_text`, `sort`)
VALUES
('ASR_DOUBAO_BIGASR_STREAM', 'api_key', 'API Key', 'credentials', 'string', 'password', 'api_key', 0, NULL, 1, 1, NULL, NULL, '新版控制台 API Key 管理页复制的 API Key。', 1),
('ASR_DOUBAO_BIGASR_STREAM', 'resource_id', 'Resource ID', 'upstream', 'string', 'text', 'resource_id', 0, NULL, 1, 0, 'volc.bigasr.sauc.duration', NULL, '豆包大模型流式 ASR 小时版资源 ID。', 2),
('ASR_DOUBAO_BIGASR_STREAM', 'auth_method', '鉴权方式', 'advanced', 'string', 'select', 'auth_method', 1, NULL, 0, 0, 'api_key', JSON_ARRAY('api_key', 'token'), 'api_key 为新版 X-Api-Key；token 为旧版 App ID + Access Token。', 3),
('ASR_DOUBAO_BIGASR_STREAM', 'appid', 'App ID', 'advanced', 'string', 'text', 'appid', 1, 'auth_method=token', 0, 0, NULL, NULL, '旧版控制台应用 ID，仅旧鉴权需要。', 4),
('ASR_DOUBAO_BIGASR_STREAM', 'access_token', 'Access Token', 'advanced', 'string', 'password', 'access_token', 1, 'auth_method=token', 0, 1, NULL, NULL, '旧版控制台 Access Token，仅旧鉴权需要。', 5),
('ASR_DOUBAO_BIGASR_STREAM', 'end_window_size', '静音判定时长(ms)', 'advanced', 'number', 'number', 'end_window_size', 1, NULL, 0, 0, '200', NULL, '', 6),
('ASR_DOUBAO_BIGASR_STREAM', 'enable_multilingual', '多语种识别', 'advanced', 'boolean', 'switch', 'enable_multilingual', 1, NULL, 0, 0, 'false', NULL, '', 7),
('ASR_DOUBAO_BIGASR_STREAM', 'language', '指定语言编码', 'advanced', 'string', 'text', 'language', 1, 'enable_multilingual=true', 0, 0, NULL, NULL, '仅多语种模式下有效。', 8),
('ASR_DOUBAO_SEED_ASR_2_STREAM', 'api_key', 'API Key', 'credentials', 'string', 'password', 'api_key', 0, NULL, 1, 1, NULL, NULL, '新版控制台 API Key 管理页复制的 API Key。', 1),
('ASR_DOUBAO_SEED_ASR_2_STREAM', 'resource_id', 'Resource ID', 'upstream', 'string', 'text', 'resource_id', 0, NULL, 1, 0, 'volc.seedasr.sauc.duration', NULL, 'Seed ASR 2.0 小时版资源 ID。', 2),
('ASR_DOUBAO_SEED_ASR_2_STREAM', 'auth_method', '鉴权方式', 'advanced', 'string', 'select', 'auth_method', 1, NULL, 0, 0, 'api_key', JSON_ARRAY('api_key', 'token'), 'api_key 为新版 X-Api-Key；token 为旧版 App ID + Access Token。', 3),
('ASR_DOUBAO_SEED_ASR_2_STREAM', 'appid', 'App ID', 'advanced', 'string', 'text', 'appid', 1, 'auth_method=token', 0, 0, NULL, NULL, '旧版控制台应用 ID，仅旧鉴权需要。', 4),
('ASR_DOUBAO_SEED_ASR_2_STREAM', 'access_token', 'Access Token', 'advanced', 'string', 'password', 'access_token', 1, 'auth_method=token', 0, 1, NULL, NULL, '旧版控制台 Access Token，仅旧鉴权需要。', 5),
('ASR_DOUBAO_SEED_ASR_2_STREAM', 'end_window_size', '静音判定时长(ms)', 'advanced', 'number', 'number', 'end_window_size', 1, NULL, 0, 0, '200', NULL, '', 6),
('ASR_DOUBAO_SEED_ASR_2_STREAM', 'enable_multilingual', '多语种识别', 'advanced', 'boolean', 'switch', 'enable_multilingual', 1, NULL, 0, 0, 'false', NULL, '', 7),
('ASR_DOUBAO_SEED_ASR_2_STREAM', 'language', '指定语言编码', 'advanced', 'string', 'text', 'language', 1, 'enable_multilingual=true', 0, 0, NULL, NULL, '仅多语种模式下有效。', 8);

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT m.`id`, 'auth_method', 'api_key', 'string', 'advanced'
FROM `ai_model_config` m
LEFT JOIN `ai_model_config_param` p
  ON p.`model_id` = m.`id`
 AND p.`param_key` = 'auth_method'
WHERE m.`model_type` = 'ASR'
  AND m.`adapter_code` = 'doubao_stream'
  AND p.`model_id` IS NULL;

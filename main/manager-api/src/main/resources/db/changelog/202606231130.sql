UPDATE `ai_model_template`
SET `adapter_code` = 'openai',
    `vendor_name` = 'DeepSeek',
    `product_name` = 'DeepSeek API',
    `capability` = 'llm',
    `access_mode` = 'openai-compatible',
    `deployment` = 'cloud',
    `protocol` = 'OpenAI Chat',
    `upstream_model_field` = 'model_name',
    `doc_link` = 'https://api-docs.deepseek.com/',
    `enabled` = 1
WHERE `template_code` = 'LLM_DEEPSEEK_OPENAI';

DELETE FROM `ai_model_template_field`
WHERE `template_id` = 'LLM_DEEPSEEK_OPENAI'
  AND `field_key` IN ('base_url', 'model_name', 'api_key', 'temperature', 'top_p', 'top_k', 'frequency_penalty', 'max_tokens', 'thinking', 'reasoning_effort');

INSERT INTO `ai_model_template_field`
(`template_id`, `field_key`, `field_label`, `field_group`, `value_type`, `input_type`, `runtime_key`, `advanced`, `visible_when`, `required`, `secret`, `default_value`, `options_json`, `help_text`, `sort`)
VALUES
('LLM_DEEPSEEK_OPENAI', 'base_url', 'Base URL', 'endpoint', 'string', 'text', 'base_url', 0, NULL, 1, 0, 'https://api.deepseek.com', NULL, '接口地址，注意是否需要包含具体 path。', 1),
('LLM_DEEPSEEK_OPENAI', 'model_name', '模型', 'upstream', 'string', 'text', 'model_name', 0, NULL, 1, 0, 'deepseek-v4-flash', NULL, '上游平台实际调用的模型 ID。', 2),
('LLM_DEEPSEEK_OPENAI', 'api_key', 'API Key', 'credentials', 'string', 'password', 'api_key', 0, NULL, 1, 1, NULL, NULL, '调用厂商接口的 API Key。', 3),
('LLM_DEEPSEEK_OPENAI', 'max_tokens', 'max_tokens', 'advanced', 'number', 'number', 'max_tokens', 1, NULL, 0, 0, '500', NULL, '', 4),
('LLM_DEEPSEEK_OPENAI', 'thinking', 'thinking', 'advanced', 'string', 'select', 'thinking', 1, NULL, 0, 0, 'disabled', JSON_ARRAY('disabled', 'enabled'), '是否开启模型思考模式，语音场景通常关闭以降低延迟。', 5),
('LLM_DEEPSEEK_OPENAI', 'reasoning_effort', 'reasoning_effort', 'advanced', 'string', 'select', 'reasoning_effort', 1, 'thinking=enabled', 0, 0, 'high', JSON_ARRAY('high', 'max'), '思考强度，仅在 thinking 启用时生效。', 6);

DELETE p
FROM `ai_model_config_param` p
JOIN `ai_model_config` m ON m.`id` = p.`model_id`
WHERE p.`param_key` IN ('temperature', 'top_p', 'top_k', 'frequency_penalty')
  AND (
    m.`template_code` = 'LLM_DEEPSEEK_OPENAI'
    OR m.`id` = 'LLM_DeepSeekLLM'
    OR m.`endpoint_url` LIKE '%api.deepseek.com%'
    OR (m.`config_json` IS NOT NULL
      AND JSON_VALID(m.`config_json`)
      AND JSON_UNQUOTE(JSON_EXTRACT(m.`config_json`, '$.base_url')) LIKE '%api.deepseek.com%')
  );

UPDATE `ai_model_config`
SET `config_json` = JSON_REMOVE(`config_json`, '$.temperature', '$.top_p', '$.top_k', '$.frequency_penalty')
WHERE `config_json` IS NOT NULL
  AND JSON_VALID(`config_json`)
  AND (
    `template_code` = 'LLM_DEEPSEEK_OPENAI'
    OR `id` = 'LLM_DeepSeekLLM'
    OR `endpoint_url` LIKE '%api.deepseek.com%'
    OR JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.base_url')) LIKE '%api.deepseek.com%'
  );

UPDATE `ai_model_template`
SET `adapter_code` = 'doubao',
    `upstream_model_field` = 'voice',
    `protocol` = 'HTTP',
    `enabled` = 1
WHERE `template_code` = 'TTS_DOUBAO_HTTP';

UPDATE `ai_model_template_field`
SET `default_value` = 'https://openspeech.bytedance.com/api/v1/tts',
    `runtime_key` = 'api_url',
    `field_group` = 'endpoint',
    `required` = 1
WHERE `template_id` = 'TTS_DOUBAO_HTTP'
  AND `field_key` = 'api_url';

UPDATE `ai_model_template`
SET `adapter_code` = 'huoshan_double_stream',
    `upstream_model_field` = 'resource_id',
    `protocol` = 'WebSocket',
    `enabled` = 1
WHERE `template_code` IN ('TTS_DOUBAO_BIGTTS_STREAM', 'TTS_DOUBAO_SEED_TTS_2_STREAM');

DELETE FROM `ai_model_template_field`
WHERE `template_id` IN ('TTS_DOUBAO_BIGTTS_STREAM', 'TTS_DOUBAO_SEED_TTS_2_STREAM');

INSERT INTO `ai_model_template_field`
(`template_id`, `field_key`, `field_label`, `field_group`, `value_type`, `input_type`, `runtime_key`, `advanced`, `visible_when`, `required`, `secret`, `default_value`, `options_json`, `help_text`, `sort`)
VALUES
('TTS_DOUBAO_BIGTTS_STREAM', 'ws_url', 'WebSocket URL', 'endpoint', 'string', 'text', 'ws_url', 0, NULL, 1, 0, 'wss://openspeech.bytedance.com/api/v3/tts/bidirection', NULL, '接口地址，注意是否需要包含具体 path。', 1),
('TTS_DOUBAO_BIGTTS_STREAM', 'appid', 'App ID', 'credentials', 'string', 'text', 'appid', 0, NULL, 1, 0, NULL, NULL, '厂商控制台应用 ID，本身不是密钥。', 2),
('TTS_DOUBAO_BIGTTS_STREAM', 'access_token', 'Access Token', 'credentials', 'string', 'password', 'access_token', 0, NULL, 1, 1, NULL, NULL, '调用厂商接口的访问令牌。', 3),
('TTS_DOUBAO_BIGTTS_STREAM', 'resource_id', 'Resource ID', 'upstream', 'string', 'text', 'resource_id', 0, NULL, 1, 0, 'volc.service_type.10029', NULL, '云平台开通具体语音服务后得到的资源标识，不等同于配置名称。', 4),
('TTS_DOUBAO_BIGTTS_STREAM', 'speaker', '音色', 'upstream', 'string', 'text', 'speaker', 0, NULL, 1, 0, NULL, NULL, '上游平台音色 ID。', 5),
('TTS_DOUBAO_BIGTTS_STREAM', 'enable_ws_reuse', 'enable_ws_reuse', 'advanced', 'boolean', 'switch', 'enable_ws_reuse', 1, NULL, 0, 0, 'true', NULL, '', 6),
('TTS_DOUBAO_BIGTTS_STREAM', 'audio_params', 'audio_params', 'advanced', 'json', 'textarea', 'audio_params', 1, NULL, 0, 0, '{\"speech_rate\":0,\"loudness_rate\":0}', NULL, '', 7),
('TTS_DOUBAO_BIGTTS_STREAM', 'additions', 'additions', 'advanced', 'json', 'textarea', 'additions', 1, NULL, 0, 0, '{\"post_process\":{\"pitch\":0}}', NULL, '', 8),
('TTS_DOUBAO_SEED_TTS_2_STREAM', 'ws_url', 'WebSocket URL', 'endpoint', 'string', 'text', 'ws_url', 0, NULL, 1, 0, 'wss://openspeech.bytedance.com/api/v3/tts/bidirection', NULL, '接口地址，注意是否需要包含具体 path。', 1),
('TTS_DOUBAO_SEED_TTS_2_STREAM', 'appid', 'App ID', 'credentials', 'string', 'text', 'appid', 0, NULL, 1, 0, NULL, NULL, '厂商控制台应用 ID，本身不是密钥。', 2),
('TTS_DOUBAO_SEED_TTS_2_STREAM', 'access_token', 'Access Token', 'credentials', 'string', 'password', 'access_token', 0, NULL, 1, 1, NULL, NULL, '调用厂商接口的访问令牌。', 3),
('TTS_DOUBAO_SEED_TTS_2_STREAM', 'resource_id', 'Resource ID', 'upstream', 'string', 'text', 'resource_id', 0, NULL, 1, 0, 'seed-tts-2.0', NULL, '云平台开通具体语音服务后得到的资源标识，不等同于配置名称。', 4),
('TTS_DOUBAO_SEED_TTS_2_STREAM', 'speaker', '音色', 'upstream', 'string', 'text', 'speaker', 0, NULL, 1, 0, NULL, NULL, '上游平台音色 ID。', 5),
('TTS_DOUBAO_SEED_TTS_2_STREAM', 'enable_ws_reuse', 'enable_ws_reuse', 'advanced', 'boolean', 'switch', 'enable_ws_reuse', 1, NULL, 0, 0, 'true', NULL, '', 6),
('TTS_DOUBAO_SEED_TTS_2_STREAM', 'audio_params', 'audio_params', 'advanced', 'json', 'textarea', 'audio_params', 1, NULL, 0, 0, '{\"speech_rate\":0,\"loudness_rate\":0}', NULL, '', 7);

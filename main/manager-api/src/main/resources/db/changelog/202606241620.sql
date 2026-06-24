UPDATE `ai_model_template`
SET `adapter_code` = 'openai',
    `vendor_name` = '火山引擎',
    `product_name` = '豆包 Seed 系列',
    `capability` = 'llm',
    `access_mode` = 'openai-compatible',
    `deployment` = 'cloud',
    `protocol` = 'OpenAI Chat',
    `upstream_model_field` = 'model_name',
    `doc_link` = 'https://www.volcengine.com/docs/82379/1554709',
    `enabled` = 1,
    `supported_status` = 'SUPPORTED',
    `selectable` = 1,
    `hidden` = 0,
    `deprecated` = 0,
    `recommended_for` = '主对话、工具调用规划或辅助推理，需结合真实设备评测。',
    `avoid_when` = '不要填写 doubao-seed-2-0 这类泛化系列名；方舟 Chat Completions 要求 model 精确等于控制台已开通模型 ID。',
    `test_contract` = '必须执行真实对话调用；主对话模型还应跑真实设备智能体评测。',
    `source_checked_at` = '2026-06-24',
    `setup_hints_json` = JSON_ARRAY('通过火山方舟 OpenAI 兼容接口接入豆包 Seed 系列；模型名称必须填写方舟控制台已开通的具体模型 ID。'),
    `known_limitations_json` = JSON_ARRAY('方舟开通管理中的展示名通常是 Doubao-Seed-2.1-turbo 这类产品名；实际 model 参数使用小写连字符形式，例如 doubao-seed-2-1-turbo。', '账号未开通对应模型时，即使 ID 正确也会返回无权限或 NotFound。'),
    `aliases_json` = JSON_ARRAY('doubao-seed', 'doubao-seed-evolving', 'doubao-seed-2-1', 'doubao-seed-2-0')
WHERE `template_code` = 'LLM_DOUBAO_SEED_OPENAI';

DELETE FROM `ai_model_template_field`
WHERE `template_id` = 'LLM_DOUBAO_SEED_OPENAI'
  AND `field_key` IN ('base_url', 'model_name', 'api_key', 'temperature', 'max_tokens', 'top_p', 'top_k', 'frequency_penalty', 'thinking', 'reasoning_effort');

INSERT INTO `ai_model_template_field`
(`template_id`, `field_key`, `field_label`, `field_group`, `value_type`, `input_type`, `runtime_key`, `advanced`, `visible_when`, `required`, `secret`, `default_value`, `options_json`, `help_text`, `sort`)
VALUES
('LLM_DOUBAO_SEED_OPENAI', 'base_url', 'Base URL', 'endpoint', 'string', 'text', 'base_url', 0, NULL, 1, 0, 'https://ark.cn-beijing.volces.com/api/v3', NULL, '火山方舟 OpenAI 兼容接口地址。', 1),
('LLM_DOUBAO_SEED_OPENAI', 'model_name', '模型', 'upstream', 'string', 'select', 'model_name', 0, NULL, 1, 0, 'doubao-seed-2-1-turbo', JSON_ARRAY('doubao-seed-evolving', 'doubao-seed-2-1-turbo', 'doubao-seed-2-1-pro', 'doubao-seed-2-0-pro', 'doubao-seed-2-0-mini', 'doubao-seed-2-0-lite', 'doubao-seed-2-0-code', 'doubao-seed-1-8'), '上游平台实际调用的模型 ID，必须和方舟控制台已开通模型一致。', 2),
('LLM_DOUBAO_SEED_OPENAI', 'api_key', 'API Key', 'credentials', 'string', 'password', 'api_key', 0, NULL, 1, 1, NULL, NULL, '火山方舟 API Key，只保存到密钥表。', 3),
('LLM_DOUBAO_SEED_OPENAI', 'temperature', 'temperature', 'advanced', 'number', 'number', 'temperature', 1, NULL, 0, 0, '0', NULL, '', 4),
('LLM_DOUBAO_SEED_OPENAI', 'max_tokens', 'max_tokens', 'advanced', 'number', 'number', 'max_tokens', 1, NULL, 0, 0, '500', NULL, '', 5),
('LLM_DOUBAO_SEED_OPENAI', 'top_p', 'top_p', 'advanced', 'number', 'number', 'top_p', 1, NULL, 0, 0, '1', NULL, '', 6),
('LLM_DOUBAO_SEED_OPENAI', 'top_k', 'top_k', 'advanced', 'number', 'number', 'top_k', 1, NULL, 0, 0, '50', NULL, '', 7),
('LLM_DOUBAO_SEED_OPENAI', 'frequency_penalty', 'frequency_penalty', 'advanced', 'string', 'text', 'frequency_penalty', 1, NULL, 0, 0, '0', NULL, '', 8),
('LLM_DOUBAO_SEED_OPENAI', 'thinking', 'thinking', 'advanced', 'string', 'select', 'thinking', 1, NULL, 0, 0, 'disabled', JSON_ARRAY('disabled', 'enabled', 'auto'), '是否开启模型思考模式，语音场景通常关闭以降低延迟。', 9),
('LLM_DOUBAO_SEED_OPENAI', 'reasoning_effort', 'reasoning_effort', 'advanced', 'string', 'select', 'reasoning_effort', 1, 'thinking=enabled', 0, 0, 'high', JSON_ARRAY('high', 'max'), '思考强度，仅在 thinking 启用时生效。', 10);

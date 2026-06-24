UPDATE `ai_model_template`
SET `adapter_code` = 'openai',
    `vendor_name` = '阿里云百炼',
    `product_name` = '通义千问 OpenAI 兼容',
    `capability` = 'llm',
    `access_mode` = 'openai-compatible',
    `deployment` = 'cloud',
    `protocol` = 'OpenAI Chat',
    `upstream_model_field` = 'model_name',
    `doc_link` = 'https://help.aliyun.com/zh/model-studio/models',
    `enabled` = 1,
    `supported_status` = 'SUPPORTED',
    `selectable` = 1,
    `hidden` = 0,
    `deprecated` = 0,
    `recommended_for` = '主对话、工具调用规划或辅助推理，需结合真实设备评测。',
    `avoid_when` = '不要选择带日期的历史快照、Preview 或控制台标注即将下线的版本；Qwen Audio、Omni Realtime 和视觉专用模型需要独立目录或 adapter，不走普通文本 Chat 模板。',
    `test_contract` = '必须执行真实对话调用；主对话模型还应跑真实设备智能体评测。',
    `source_checked_at` = '2026-06-24',
    `setup_hints_json` = JSON_ARRAY('通过 DashScope compatible-mode/v1 接入通义千问；模型下拉按阿里云百炼文本生成官方文档维护，覆盖推荐模型、Qwen3.x、Coder、MT、Long、Character、结构化输出和 Omni Turbo 等文本生成模型，排除历史快照、Preview 和即将下线版本。'),
    `known_limitations_json` = JSON_ARRAY('带日期的历史快照版本只供锁定特定版本使用，不进入标准下拉。', 'Qwen Audio、Omni Realtime 和视觉专用模型需要独立目录或 adapter，不套用普通文本 Chat 模板。'),
    `aliases_json` = JSON_ARRAY('AliLLM', 'Qwen', 'qwen3.7', 'qwen3.6', 'qwen3.5', 'qwen3-coder', 'qwen-mt', 'qwen-long', 'qwq-plus')
WHERE `template_code` = 'LLM_ALI_QWEN_OPENAI';

DELETE FROM `ai_model_template_field`
WHERE `template_id` = 'LLM_ALI_QWEN_OPENAI'
  AND `field_key` IN ('base_url', 'model_name', 'api_key', 'temperature', 'max_tokens', 'top_p', 'top_k', 'frequency_penalty', 'thinking', 'reasoning_effort');

INSERT INTO `ai_model_template_field`
(`template_id`, `field_key`, `field_label`, `field_group`, `value_type`, `input_type`, `runtime_key`, `advanced`, `visible_when`, `required`, `secret`, `default_value`, `options_json`, `help_text`, `sort`)
VALUES
('LLM_ALI_QWEN_OPENAI', 'base_url', 'Base URL', 'endpoint', 'string', 'text', 'base_url', 0, NULL, 1, 0, 'https://dashscope.aliyuncs.com/compatible-mode/v1', NULL, 'DashScope OpenAI 兼容接口地址。', 1),
('LLM_ALI_QWEN_OPENAI', 'model_name', '模型', 'upstream', 'string', 'select', 'model_name', 0, NULL, 1, 0, 'qwen3.7-max', JSON_ARRAY('qwen3.7-max', 'qwen3.7-plus', 'qwen3.6-plus', 'qwen3.6-flash', 'qwen3.5-plus', 'qwen3.5-flash', 'qwen3.5-397b-a17b', 'qwen3.5-122b-a10b', 'qwen3.5-35b-a3b', 'qwen3.5-27b', 'qwen3-max', 'qwen3-235b-a22b', 'qwen3-235b-a22b-instruct-2507', 'qwen3-235b-a22b-thinking-2507', 'qwen3-next-80b-a3b-thinking', 'qwen3-next-80b-a3b-instruct', 'qwen3-32b', 'qwen3-30b-a3b', 'qwen3-30b-a3b-thinking-2507', 'qwen3-30b-a3b-instruct-2507', 'qwen3-14b', 'qwen3-8b', 'qwen3-coder-plus', 'qwen3-coder-flash', 'qwen3-coder-next', 'qwen3-coder-480b-a35b-instruct', 'qwen3-coder-30b-a3b-instruct', 'qwen-plus', 'qwen-max', 'qwen-flash', 'qwen-turbo', 'qwen-long', 'qwen-long-latest', 'qwen-mt-plus', 'qwen-mt-turbo', 'qwen-mt-flash', 'qwen-mt-lite', 'qwen-plus-character', 'qwen-plus-character-ja', 'qwen-flash-character', 'qwen-structured-output', 'qwen-omni-turbo', 'qwen2.5-omni-7b', 'qwq-plus'), '上游平台实际调用的模型 ID。不要填写带日期的历史快照、Preview 或控制台标注即将下线的版本。', 2),
('LLM_ALI_QWEN_OPENAI', 'api_key', 'API Key', 'credentials', 'string', 'password', 'api_key', 0, NULL, 1, 1, NULL, NULL, '阿里云百炼 API Key，只保存到密钥表。', 3),
('LLM_ALI_QWEN_OPENAI', 'temperature', 'temperature', 'advanced', 'number', 'number', 'temperature', 1, NULL, 0, 0, '0.7', NULL, '', 4),
('LLM_ALI_QWEN_OPENAI', 'max_tokens', 'max_tokens', 'advanced', 'number', 'number', 'max_tokens', 1, NULL, 0, 0, '500', NULL, '', 5),
('LLM_ALI_QWEN_OPENAI', 'top_p', 'top_p', 'advanced', 'number', 'number', 'top_p', 1, NULL, 0, 0, '1', NULL, '', 6),
('LLM_ALI_QWEN_OPENAI', 'top_k', 'top_k', 'advanced', 'number', 'number', 'top_k', 1, NULL, 0, 0, '50', NULL, '', 7),
('LLM_ALI_QWEN_OPENAI', 'frequency_penalty', 'frequency_penalty', 'advanced', 'string', 'text', 'frequency_penalty', 1, NULL, 0, 0, '0', NULL, '', 8),
('LLM_ALI_QWEN_OPENAI', 'thinking', 'thinking', 'advanced', 'string', 'select', 'thinking', 1, NULL, 0, 0, 'disabled', JSON_ARRAY('disabled', 'enabled'), '是否开启模型思考模式，语音场景通常关闭以降低延迟。', 9),
('LLM_ALI_QWEN_OPENAI', 'reasoning_effort', 'reasoning_effort', 'advanced', 'string', 'select', 'reasoning_effort', 1, 'thinking=enabled', 0, 0, 'high', JSON_ARRAY('high', 'max'), '思考强度，仅在 thinking 启用时生效。', 10);

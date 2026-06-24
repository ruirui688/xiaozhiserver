UPDATE `ai_model_config`
SET `template_code` = 'LLM_ALI_QWEN_OPENAI',
    `adapter_code` = 'openai',
    `vendor_name` = '阿里云百炼',
    `product_name` = '通义千问 OpenAI 兼容',
    `protocol` = 'OpenAI Chat',
    `endpoint_url` = 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    `upstream_model_id` = COALESCE(NULLIF(`upstream_model_id`, ''), JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.model_name')), 'qwen3.7-max'),
    `doc_link` = 'https://help.aliyun.com/zh/model-studio/text-generation-model/'
WHERE `id` = 'LLM_AliLLM';

UPDATE `ai_model_config`
SET `template_code` = 'LLM_ALI_BAILIAN_APP',
    `adapter_code` = 'AliBL',
    `vendor_name` = '阿里云百炼',
    `product_name` = '百炼应用 API',
    `protocol` = 'DashScope Application',
    `endpoint_url` = 'https://dashscope.aliyuncs.com/api/v1',
    `upstream_model_id` = COALESCE(NULLIF(`upstream_model_id`, ''), JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.app_id'))),
    `doc_link` = 'https://help.aliyun.com/zh/model-studio'
WHERE `id` = 'LLM_AliAppLLM';

UPDATE `ai_model_template`
SET `supported_status` = 'DEPRECATED',
    `selectable` = 0,
    `hidden` = 1,
    `deprecated` = 1,
    `product_name` = '百炼应用 API',
    `recommended_for` = '仅用于已有百炼应用 ID 的旧配置兼容。',
    `avoid_when` = '不要把 qwen-flash、qwen-plus 等模型名填到应用 ID；普通百炼模型请使用通义千问 OpenAI 兼容入口。',
    `setup_hints_json` = JSON_ARRAY('这是百炼应用调用，不是普通 Qwen 模型调用；如果只是填 API Key 并选择 qwen 模型，请使用“通义千问 OpenAI 兼容”。'),
    `known_limitations_json` = JSON_ARRAY('旧版兼容入口，普通模型配置页默认不展示。')
WHERE `template_code` = 'LLM_ALI_BAILIAN_APP';


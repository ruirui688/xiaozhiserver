UPDATE `ai_model_config`
SET `model_type` = 'LLM_HIDDEN',
    `is_enabled` = 0,
    `template_code` = 'LLM_ALI_QWEN_OPENAI',
    `adapter_code` = 'openai',
    `vendor_name` = '阿里云百炼',
    `product_name` = '阿里云百炼',
    `protocol` = 'OpenAI Chat',
    `endpoint_url` = 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    `upstream_model_id` = COALESCE(NULLIF(`upstream_model_id`, ''), 'qwen3.7-max')
WHERE `id` = 'LLM_AliAppLLM';

UPDATE `ai_model_config`
SET `template_code` = 'LLM_ALI_QWEN_OPENAI',
    `adapter_code` = 'openai',
    `vendor_name` = '阿里云百炼',
    `product_name` = '阿里云百炼',
    `protocol` = 'OpenAI Chat',
    `endpoint_url` = 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    `upstream_model_id` = COALESCE(NULLIF(`upstream_model_id`, ''), JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.model_name')), 'qwen3.7-max'),
    `doc_link` = 'https://help.aliyun.com/zh/model-studio/text-generation-model/'
WHERE `id` = 'LLM_AliLLM';

UPDATE `ai_model_template`
SET `vendor_name` = '阿里云百炼',
    `product_name` = '阿里云百炼',
    `supported_status` = 'SUPPORTED',
    `selectable` = 1,
    `hidden` = 0,
    `deprecated` = 0
WHERE `template_code` = 'LLM_ALI_QWEN_OPENAI';

UPDATE `ai_model_template`
SET `hidden` = 1,
    `selectable` = 0,
    `deprecated` = 1
WHERE `template_code` = 'LLM_ALI_BAILIAN_APP';


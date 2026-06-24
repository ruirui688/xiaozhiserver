UPDATE `ai_model_template_field`
SET `default_value` = 'doubao-seed-2-1-turbo-260628',
    `options_json` = JSON_ARRAY(
        'doubao-seed-evolving',
        'doubao-seed-2-1-turbo-260628',
        'doubao-seed-2-1-pro-260628',
        'doubao-seed-2-0-pro-260215',
        'doubao-seed-2-0-mini-260428',
        'doubao-seed-2-0-lite-260428'
    ),
    `help_text` = '上游平台实际调用的模型 ID，必须和方舟控制台或官方模型列表中可调用 ID 一致；每个系列仅保留当前推荐/最新版本。'
WHERE `template_id` = 'LLM_DOUBAO_SEED_OPENAI'
  AND `field_key` = 'model_name';

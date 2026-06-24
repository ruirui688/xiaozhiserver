UPDATE `ai_model_template`
SET `product_name` = '豆包方舟旧版兼容',
    `enabled` = 0,
    `supported_status` = 'DEPRECATED',
    `selectable` = 0,
    `hidden` = 1,
    `deprecated` = 1,
    `avoid_when` = '新增豆包 LLM 配置不要选择旧版豆包方舟入口，请使用“火山引擎 / 豆包 Seed 系列”。',
    `known_limitations_json` = JSON_ARRAY('该入口仅用于识别和兼容历史 DoubaoLLM 配置，新增配置已隐藏。'),
    `source_checked_at` = '2026-06-24'
WHERE `template_code` = 'LLM_DOUBAO_ARK_OPENAI';

UPDATE `ai_model_template_field`
SET `default_value` = 'doubao-seed-2-1-turbo-260628',
    `options_json` = JSON_ARRAY(
        'doubao-seed-evolving',
        'doubao-seed-2-1-turbo-260628',
        'doubao-seed-2-1-pro-260628',
        'doubao-seed-2-0-pro-260215',
        'doubao-seed-2-0-mini-260428',
        'doubao-seed-2-0-mini-260215',
        'doubao-seed-2-0-lite-260428',
        'doubao-seed-2-0-lite-260215',
        'doubao-seed-1-8-251228'
    ),
    `help_text` = '上游平台实际调用的模型 ID，必须和方舟控制台或官方模型列表中可调用 ID 一致；当前下拉仅保留已验证可调用的完整 ID。'
WHERE `template_id` = 'LLM_DOUBAO_SEED_OPENAI'
  AND `field_key` = 'model_name';

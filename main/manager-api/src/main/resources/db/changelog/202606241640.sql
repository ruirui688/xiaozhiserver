UPDATE `ai_model_template`
SET `known_limitations_json` = JSON_ARRAY(
        '方舟开通管理中的展示名通常是 Doubao-Seed-2.1-turbo 这类产品名；实际 model 参数需要使用控制台或官方模型列表中的可调用 ID，例如 doubao-seed-2-1-turbo-260628。短 ID 在部分账号或套餐下可能返回 NotFound。',
        '账号未开通对应模型时，即使 ID 正确也会返回无权限或 NotFound。'
    ),
    `source_checked_at` = '2026-06-24'
WHERE `template_code` = 'LLM_DOUBAO_SEED_OPENAI';

UPDATE `ai_model_template_field`
SET `default_value` = 'doubao-seed-2-1-turbo-260628',
    `options_json` = JSON_ARRAY(
        'doubao-seed-evolving',
        'doubao-seed-2-1-turbo-260628',
        'doubao-seed-2-1-turbo',
        'doubao-seed-2-1-pro-260628',
        'doubao-seed-2-1-pro',
        'doubao-seed-2-0-pro-260215',
        'doubao-seed-2-0-pro',
        'doubao-seed-2-0-mini-260428',
        'doubao-seed-2-0-mini-260215',
        'doubao-seed-2-0-mini',
        'doubao-seed-2-0-lite-260428',
        'doubao-seed-2-0-lite-260215',
        'doubao-seed-2-0-lite',
        'doubao-seed-2-0-code-preview-260215',
        'doubao-seed-2-0-code',
        'doubao-seed-1-8-251228',
        'doubao-seed-1-8'
    ),
    `help_text` = '上游平台实际调用的模型 ID，必须和方舟控制台或官方模型列表中可调用 ID 一致；短 ID 不可用时请改用带日期后缀的完整 ID。'
WHERE `template_id` = 'LLM_DOUBAO_SEED_OPENAI'
  AND `field_key` = 'model_name';

UPDATE `ai_model_config`
SET `upstream_model_id` = CASE `upstream_model_id`
        WHEN 'doubao-seed-2-1-turbo' THEN 'doubao-seed-2-1-turbo-260628'
        WHEN 'doubao-seed-2-1-pro' THEN 'doubao-seed-2-1-pro-260628'
        WHEN 'doubao-seed-2-0-pro' THEN 'doubao-seed-2-0-pro-260215'
        WHEN 'doubao-seed-2-0-mini' THEN 'doubao-seed-2-0-mini-260428'
        WHEN 'doubao-seed-2-0-lite' THEN 'doubao-seed-2-0-lite-260428'
        WHEN 'doubao-seed-2-0-code' THEN 'doubao-seed-2-0-code-preview-260215'
        WHEN 'doubao-seed-1-8' THEN 'doubao-seed-1-8-251228'
        ELSE `upstream_model_id`
    END,
    `config_json` = JSON_SET(
        `config_json`,
        '$.model_name',
        CASE
            WHEN JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.model_name')) = 'doubao-seed-2-1-turbo' THEN 'doubao-seed-2-1-turbo-260628'
            WHEN JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.model_name')) = 'doubao-seed-2-1-pro' THEN 'doubao-seed-2-1-pro-260628'
            WHEN JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.model_name')) = 'doubao-seed-2-0-pro' THEN 'doubao-seed-2-0-pro-260215'
            WHEN JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.model_name')) = 'doubao-seed-2-0-mini' THEN 'doubao-seed-2-0-mini-260428'
            WHEN JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.model_name')) = 'doubao-seed-2-0-lite' THEN 'doubao-seed-2-0-lite-260428'
            WHEN JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.model_name')) = 'doubao-seed-2-0-code' THEN 'doubao-seed-2-0-code-preview-260215'
            WHEN JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.model_name')) = 'doubao-seed-1-8' THEN 'doubao-seed-1-8-251228'
            WHEN `upstream_model_id` = 'doubao-seed-2-1-turbo' THEN 'doubao-seed-2-1-turbo-260628'
            WHEN `upstream_model_id` = 'doubao-seed-2-1-pro' THEN 'doubao-seed-2-1-pro-260628'
            WHEN `upstream_model_id` = 'doubao-seed-2-0-pro' THEN 'doubao-seed-2-0-pro-260215'
            WHEN `upstream_model_id` = 'doubao-seed-2-0-mini' THEN 'doubao-seed-2-0-mini-260428'
            WHEN `upstream_model_id` = 'doubao-seed-2-0-lite' THEN 'doubao-seed-2-0-lite-260428'
            WHEN `upstream_model_id` = 'doubao-seed-2-0-code' THEN 'doubao-seed-2-0-code-preview-260215'
            WHEN `upstream_model_id` = 'doubao-seed-1-8' THEN 'doubao-seed-1-8-251228'
            ELSE JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.model_name'))
        END
    )
WHERE `template_code` = 'LLM_DOUBAO_SEED_OPENAI'
  AND `config_json` IS NOT NULL
  AND (
      `upstream_model_id` IN (
          'doubao-seed-2-1-turbo',
          'doubao-seed-2-1-pro',
          'doubao-seed-2-0-pro',
          'doubao-seed-2-0-mini',
          'doubao-seed-2-0-lite',
          'doubao-seed-2-0-code',
          'doubao-seed-1-8'
      )
      OR JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.model_name')) IN (
          'doubao-seed-2-1-turbo',
          'doubao-seed-2-1-pro',
          'doubao-seed-2-0-pro',
          'doubao-seed-2-0-mini',
          'doubao-seed-2-0-lite',
          'doubao-seed-2-0-code',
          'doubao-seed-1-8'
      )
  );

UPDATE `ai_model_config_param`
SET `param_value` = CASE `param_value`
        WHEN 'doubao-seed-2-1-turbo' THEN 'doubao-seed-2-1-turbo-260628'
        WHEN 'doubao-seed-2-1-pro' THEN 'doubao-seed-2-1-pro-260628'
        WHEN 'doubao-seed-2-0-pro' THEN 'doubao-seed-2-0-pro-260215'
        WHEN 'doubao-seed-2-0-mini' THEN 'doubao-seed-2-0-mini-260428'
        WHEN 'doubao-seed-2-0-lite' THEN 'doubao-seed-2-0-lite-260428'
        WHEN 'doubao-seed-2-0-code' THEN 'doubao-seed-2-0-code-preview-260215'
        WHEN 'doubao-seed-1-8' THEN 'doubao-seed-1-8-251228'
        ELSE `param_value`
    END
WHERE `param_key` = 'model_name'
  AND `model_id` IN (
      SELECT `id`
      FROM `ai_model_config`
      WHERE `template_code` = 'LLM_DOUBAO_SEED_OPENAI'
  )
  AND `param_value` IN (
      'doubao-seed-2-1-turbo',
      'doubao-seed-2-1-pro',
      'doubao-seed-2-0-pro',
      'doubao-seed-2-0-mini',
      'doubao-seed-2-0-lite',
      'doubao-seed-2-0-code',
      'doubao-seed-1-8'
  );

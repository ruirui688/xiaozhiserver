UPDATE `ai_model_template_field`
SET
  `field_label` = '记忆维护模型',
  `field_group` = 'common',
  `input_type` = 'model-select',
  `options_json` = JSON_ARRAY('LLM', 'SLM'),
  `help_text` = '选择已配置并测试通过的 LLM/SLM，用于像 PowerMem 一样辅助抽取、合并和沉淀候选记忆；这里只保存模型配置 ID，不保存 API Key。',
  `advanced` = 0,
  `sort` = 1
WHERE `template_id` = 'MEMORY_MANAGED_GOVERNED'
  AND `field_key` = 'maintenance_llm';

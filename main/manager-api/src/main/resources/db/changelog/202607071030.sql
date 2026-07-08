-- Re-apply the intended model-select seed correction under a new changeset.
-- 202607061000 was already executed in some environments with an older checksum.
UPDATE `ai_model_template_field`
SET
  `input_type` = 'model-select',
  `options_json` = JSON_ARRAY('LLM', 'SLM')
WHERE `field_key` IN ('llm', 'maintenance_llm')
  AND `input_type` <> 'model-select';

UPDATE `ai_model_template_field`
SET
  `field_label` = '外挂大模型',
  `help_text` = '选择模型管理中已配置并测试通过的 LLM/SLM，作为外挂意图识别或短记忆总结模型；密钥仍保存在对应 LLM/SLM 配置中。'
WHERE `field_key` = 'llm';

UPDATE `ai_model_template`
SET
  `product_name` = '外挂大模型意图识别'
WHERE `template_code` = 'INTENT_LLM';

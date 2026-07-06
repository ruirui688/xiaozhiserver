-- 意图识别/记忆总结的关联 LLM 字段改为模型选择下拉，避免手填配置 ID 且看不到可选模型。
-- 覆盖已落库的 ai_model_template_field 记录；内存目录由 ModelCatalogServiceImpl.field() 保证新部署一致。
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

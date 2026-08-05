-- Qwen Omni Realtime 音色由自由文本改为受控下拉，避免保存上游不支持的值。
UPDATE `ai_model_template_field`
SET `input_type` = 'select',
    `options_json` = JSON_ARRAY('Cherry', 'Serena', 'Ethan', 'Chelsie'),
    `help_text` = '选择 Qwen Omni Realtime 官方音色；运行中也可通过会话控制工具临时切换，临时切换不会改写此配置。'
WHERE `template_id` = 'LLM_ALI_QWEN_OMNI_REALTIME'
  AND `field_key` = 'voice';

-- 豆包音色与账号开通资源相关：提供默认可选项，同时允许前端录入控制台实际音色 ID。
UPDATE `ai_model_template_field`
SET `input_type` = 'select',
    `options_json` = JSON_ARRAY('zh_female_vv_jupiter_bigtts'),
    `help_text` = '选择或填写当前火山引擎账号已开通的豆包端到端音色 ID；运行中临时切换不会改写此配置。'
WHERE `template_id` = 'LLM_DOUBAO_REALTIME'
  AND `field_key` = 'voice';

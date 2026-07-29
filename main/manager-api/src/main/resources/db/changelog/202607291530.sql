-- 将本地托管记忆明确调整为官方式的会话结束主动抽取，避免继续误导为主对话工具调用。
UPDATE `ai_model_provider`
SET
  `name` = '大模型主动记忆',
  `fields` = JSON_ARRAY(
    JSON_OBJECT(
      'key', 'maintenance_llm',
      'label', '主动记忆模型',
      'type', 'string',
      'help', '必选。选择模型管理中已配置并测试通过的 LLM/SLM；会话结束后自动读取完整对话并整理长期记忆。'
    )
  ),
  `updater` = 1,
  `update_date` = NOW()
WHERE `id` = 'SYSTEM_Memory_managed_memory';

UPDATE `ai_model_template`
SET
  `product_name` = '大模型主动记忆',
  `recommended_for` = '需要在会话结束后由记忆模型自动提取、合并和更新长期记忆，并在智控台审计管理的智能体。',
  `test_contract` = '配置检查通过后，会话结束时由 xiaozhi-server 调用 manager-api /config/memory/process-session，维护模型根据完整对话生成并应用记忆动作。',
  `setup_hints_json` = JSON_ARRAY(
    '选择一个已配置并测试通过的 LLM/SLM 作为主动记忆模型。',
    '保存方式参考官方 Memory provider：会话结束后自动整理完整对话，不要求主对话模型调用记忆工具。',
    '模型配置只保存 maintenance_llm 的模型 ID，不复制 API Key。'
  )
WHERE `template_code` = 'MEMORY_MANAGED_GOVERNED';

UPDATE `ai_model_template_field`
SET
  `field_label` = '主动记忆模型',
  `field_group` = 'common',
  `input_type` = 'model-select',
  `options_json` = JSON_ARRAY('LLM', 'SLM'),
  `required` = 1,
  `advanced` = 0,
  `help_text` = '必选。会话结束后读取完整对话，自动提取、合并、修正或撤销长期记忆；这里只保存模型配置 ID，不保存 API Key。'
WHERE `template_id` = 'MEMORY_MANAGED_GOVERNED'
  AND `field_key` = 'maintenance_llm';

UPDATE `ai_model_config`
SET
  `model_name` = '大模型主动记忆',
  `product_name` = '大模型主动记忆',
  `remark` = '会话结束后由所选记忆模型自动读取完整对话并整理长期记忆；运行时仍由 manager-api 提供人物隔离、事实审计、忘记记录和查询注入。',
  `updater` = 1,
  `update_date` = NOW()
WHERE `id` = 'Memory_managed_memory';

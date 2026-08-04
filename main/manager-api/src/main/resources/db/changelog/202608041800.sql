-- 历史库可能已通过旧版超级管理员接口删除全部系统模板。
-- 工作空间模板隔离上线后，系统默认模板查询不再允许回退到其他 workspace，
-- 因此只在系统模板为空时补种一条不包含用户快照、声纹或第三方凭据的安全模板。
INSERT INTO `ai_agent_template` (
  `id`, `version`, `workspace_id`, `template_name`, `source_agent_id`, `snapshot_json`,
  `agent_code`, `agent_name`, `asr_model_id`, `vad_model_id`, `llm_model_id`,
  `slm_model_id`, `vllm_model_id`, `tts_model_id`, `mem_model_id`, `intent_model_id`,
  `system_prompt`, `summary_memory`, `chat_history_conf`, `lang_code`, `language`,
  `sort`, `creator`, `created_at`, `updater`, `updated_at`
)
SELECT
  'system_default_template_20260804',
  1,
  NULL,
  '系统默认角色',
  NULL,
  NULL,
  'AGT_SYSTEM_DEFAULT',
  '小智',
  (
    SELECT `id` FROM `ai_model_config`
    WHERE LOWER(`model_type`) = 'asr' AND `is_enabled` = 1 AND `test_status` = 1
    ORDER BY `is_default` DESC, `sort` ASC LIMIT 1
  ),
  (
    SELECT `id` FROM `ai_model_config`
    WHERE LOWER(`model_type`) = 'vad' AND `is_enabled` = 1 AND `test_status` = 1
    ORDER BY `is_default` DESC, `sort` ASC LIMIT 1
  ),
  (
    SELECT `id` FROM `ai_model_config`
    WHERE LOWER(`model_type`) = 'llm' AND `is_enabled` = 1 AND `test_status` = 1
    ORDER BY `is_default` DESC, `sort` ASC LIMIT 1
  ),
  (
    SELECT `id` FROM `ai_model_config`
    WHERE LOWER(`model_type`) = 'slm' AND `is_enabled` = 1 AND `test_status` = 1
    ORDER BY `is_default` DESC, `sort` ASC LIMIT 1
  ),
  (
    SELECT `id` FROM `ai_model_config`
    WHERE LOWER(`model_type`) = 'vllm' AND `is_enabled` = 1 AND `test_status` = 1
    ORDER BY `is_default` DESC, `sort` ASC LIMIT 1
  ),
  (
    SELECT `id` FROM `ai_model_config`
    WHERE LOWER(`model_type`) = 'tts' AND `is_enabled` = 1 AND `test_status` = 1
    ORDER BY `is_default` DESC, `sort` ASC LIMIT 1
  ),
  (
    SELECT `id` FROM `ai_model_config`
    WHERE LOWER(`model_type`) = 'memory' AND `is_enabled` = 1 AND `test_status` = 1
    ORDER BY `is_default` DESC, `sort` ASC LIMIT 1
  ),
  (
    SELECT `id` FROM `ai_model_config`
    WHERE LOWER(`model_type`) = 'intent' AND `is_enabled` = 1 AND `test_status` = 1
    ORDER BY `is_default` DESC, `sort` ASC LIMIT 1
  ),
  '你是小智，一个友好、自然、简洁的语音助手。请优先使用适合语音播报的简短表达。',
  NULL,
  0,
  'zh',
  '中文',
  0,
  1,
  NOW(),
  1,
  NOW()
WHERE NOT EXISTS (
  SELECT 1 FROM `ai_agent_template` WHERE `workspace_id` IS NULL
);

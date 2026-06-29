UPDATE `ai_model_config`
SET
  `is_enabled` = 1,
  `test_status` = 1,
  `test_message` = '内置记忆模式，无需外部连通性测试。',
  `test_time` = COALESCE(`test_time`, NOW()),
  `updater` = 1,
  `update_date` = NOW()
WHERE `id` IN ('Memory_nomem', 'Memory_mem_report_only', 'Memory_managed_memory')
  AND UPPER(`model_type`) = 'MEMORY';

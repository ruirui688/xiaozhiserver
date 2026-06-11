UPDATE `ai_model_config`
SET `is_enabled` = 0
WHERE COALESCE(`test_status`, 0) <> 1;

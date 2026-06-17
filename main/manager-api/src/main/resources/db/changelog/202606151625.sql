SET @enabled_asr_model_id = (
    SELECT id
    FROM ai_model_config
    WHERE LOWER(model_type) = 'asr'
      AND is_enabled = 1
      AND test_status = 1
    ORDER BY is_default DESC, sort ASC
    LIMIT 1
);

UPDATE ai_agent_template t
LEFT JOIN ai_model_config m ON t.asr_model_id = m.id
SET t.asr_model_id = @enabled_asr_model_id
WHERE @enabled_asr_model_id IS NOT NULL
  AND (
      t.asr_model_id IS NULL
      OR m.id IS NULL
      OR LOWER(m.model_type) <> 'asr'
      OR m.is_enabled <> 1
      OR m.test_status <> 1
  );

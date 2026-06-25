SET @legacy_tts_302ai = 'TTS_TTS302AI';
SET @legacy_tts_gizwits = 'TTS_GizwitsTTS';
SET @legacy_tts_volces_gateway = 'TTS_VolcesAiGatewayTTS';

DELETE p
FROM `ai_model_config_param` p
JOIN `ai_model_config` mc ON mc.`id` = p.`model_id`
LEFT JOIN `ai_agent` a ON a.`tts_model_id` = mc.`id`
LEFT JOIN `ai_agent_template` at ON at.`tts_model_id` = mc.`id`
WHERE mc.`id` IN (@legacy_tts_302ai, @legacy_tts_gizwits, @legacy_tts_volces_gateway)
  AND UPPER(mc.`model_type`) = 'TTS'
  AND COALESCE(mc.`is_enabled`, 0) = 0
  AND COALESCE(mc.`is_default`, 0) = 0
  AND a.`id` IS NULL
  AND at.`id` IS NULL;

DELETE s
FROM `ai_model_config_secret` s
JOIN `ai_model_config` mc ON mc.`id` = s.`model_id`
LEFT JOIN `ai_agent` a ON a.`tts_model_id` = mc.`id`
LEFT JOIN `ai_agent_template` at ON at.`tts_model_id` = mc.`id`
WHERE mc.`id` IN (@legacy_tts_302ai, @legacy_tts_gizwits, @legacy_tts_volces_gateway)
  AND UPPER(mc.`model_type`) = 'TTS'
  AND COALESCE(mc.`is_enabled`, 0) = 0
  AND COALESCE(mc.`is_default`, 0) = 0
  AND a.`id` IS NULL
  AND at.`id` IS NULL;

DELETE v
FROM `ai_tts_voice` v
JOIN `ai_model_config` mc ON mc.`id` = v.`tts_model_id`
LEFT JOIN `ai_agent` a ON a.`tts_model_id` = mc.`id`
LEFT JOIN `ai_agent_template` at ON at.`tts_model_id` = mc.`id`
WHERE mc.`id` IN (@legacy_tts_302ai, @legacy_tts_gizwits, @legacy_tts_volces_gateway)
  AND UPPER(mc.`model_type`) = 'TTS'
  AND COALESCE(mc.`is_enabled`, 0) = 0
  AND COALESCE(mc.`is_default`, 0) = 0
  AND a.`id` IS NULL
  AND at.`id` IS NULL;

DELETE mc
FROM `ai_model_config` mc
LEFT JOIN `ai_agent` a ON a.`tts_model_id` = mc.`id`
LEFT JOIN `ai_agent_template` at ON at.`tts_model_id` = mc.`id`
WHERE mc.`id` IN (@legacy_tts_302ai, @legacy_tts_gizwits, @legacy_tts_volces_gateway)
  AND UPPER(mc.`model_type`) = 'TTS'
  AND COALESCE(mc.`is_enabled`, 0) = 0
  AND COALESCE(mc.`is_default`, 0) = 0
  AND a.`id` IS NULL
  AND at.`id` IS NULL;

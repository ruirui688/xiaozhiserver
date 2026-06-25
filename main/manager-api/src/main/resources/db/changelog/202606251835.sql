UPDATE `ai_model_config_param`
SET `value_type` = 'number',
    `param_group` = 'audio'
WHERE `model_id` = 'TTS_HSDSTTS_V2'
  AND `param_key` IN ('speech_rate', 'loudness_rate', 'pitch');

UPDATE `ai_model_template_field`
SET `value_type` = 'number',
    `input_type` = 'number',
    `field_group` = 'advanced'
WHERE `template_id` = 'TTS_DOUBAO_SEED_TTS_2_STREAM'
  AND `field_key` IN ('speech_rate', 'loudness_rate', 'pitch');

-- Keep Doubao seed TTS 2.0 WebSocket reuse enabled by default.
-- This lowers per-turn first-audio latency by avoiding a new TTS connection
-- for every short voice response.

UPDATE `ai_model_template_field`
SET `default_value` = 'true',
    `help_text` = '开启后降低首包延迟，空闲连接会占用并发。'
WHERE `template_id` = 'TTS_DOUBAO_SEED_TTS_2_STREAM'
  AND `field_key` = 'enable_ws_reuse';

INSERT INTO `ai_model_config_param`
(`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT 'TTS_HSDSTTS_V2', 'enable_ws_reuse', 'true', 'boolean', 'advanced'
WHERE EXISTS (
    SELECT 1 FROM `ai_model_config` WHERE `id` = 'TTS_HSDSTTS_V2'
)
  AND NOT EXISTS (
    SELECT 1
    FROM `ai_model_config_param`
    WHERE `model_id` = 'TTS_HSDSTTS_V2'
      AND `param_key` = 'enable_ws_reuse'
);

UPDATE `ai_model_config_param`
SET `param_value` = 'true',
    `value_type` = 'boolean',
    `param_group` = 'advanced'
WHERE `model_id` = 'TTS_HSDSTTS_V2'
  AND `param_key` = 'enable_ws_reuse'
  AND LOWER(COALESCE(`param_value`, '')) <> 'true';

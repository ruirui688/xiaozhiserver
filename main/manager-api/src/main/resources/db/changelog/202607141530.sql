SET @vad_threshold_param_id = (
    SELECT CASE
        WHEN NOT EXISTS (SELECT 1 FROM `sys_params` WHERE `id` = 126) THEN 126
        ELSE COALESCE(MAX(`id`), 126) + 1
    END
    FROM `sys_params`
);

INSERT INTO `sys_params` (`id`, `param_code`, `param_value`, `value_type`, `param_type`, `remark`)
SELECT @vad_threshold_param_id,
       'server.vad_threshold',
       '0.5',
       'number',
       1,
       'Silero VAD语音开始阈值，范围0.0-1.0，数值越高越不容易误判为说话'
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_params` WHERE `param_code` = 'server.vad_threshold'
);

SET @vad_threshold_low_param_id = (
    SELECT CASE
        WHEN NOT EXISTS (SELECT 1 FROM `sys_params` WHERE `id` = 127) THEN 127
        ELSE COALESCE(MAX(`id`), 127) + 1
    END
    FROM `sys_params`
);

INSERT INTO `sys_params` (`id`, `param_code`, `param_value`, `value_type`, `param_type`, `remark`)
SELECT @vad_threshold_low_param_id,
       'server.vad_threshold_low',
       '0.3',
       'number',
       1,
       'Silero VAD语音结束阈值，范围0.0-1.0，低于该值倾向判定为静音'
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_params` WHERE `param_code` = 'server.vad_threshold_low'
);

SET @vad_silence_param_id = (
    SELECT CASE
        WHEN NOT EXISTS (SELECT 1 FROM `sys_params` WHERE `id` = 128) THEN 128
        ELSE COALESCE(MAX(`id`), 128) + 1
    END
    FROM `sys_params`
);

INSERT INTO `sys_params` (`id`, `param_code`, `param_value`, `value_type`, `param_type`, `remark`)
SELECT @vad_silence_param_id,
       'server.vad_min_silence_duration_ms',
       '200',
       'number',
       1,
       'Silero VAD最小静音持续时间，单位毫秒，达到后判定本轮说话结束'
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_params` WHERE `param_code` = 'server.vad_min_silence_duration_ms'
);

UPDATE `ai_model_provider`
SET `fields` = '[{"key":"threshold","label":"语音开始阈值","type":"number"},{"key":"threshold_low","label":"语音结束阈值","type":"number"},{"key":"model_dir","label":"模型目录","type":"string"},{"key":"min_silence_duration_ms","label":"最小静音时长","type":"number"}]'
WHERE `model_type` = 'VAD'
  AND `provider_code` = 'silero';

UPDATE `ai_model_config`
SET `config_json` = JSON_SET(
    COALESCE(NULLIF(`config_json`, ''), JSON_OBJECT()),
    '$.threshold', 0.5,
    '$.threshold_low', 0.3,
    '$.min_silence_duration_ms', 200
)
WHERE `id` = 'VAD_SileroVAD'
  AND JSON_VALID(COALESCE(NULLIF(`config_json`, ''), JSON_OBJECT()));

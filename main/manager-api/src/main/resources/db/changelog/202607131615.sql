SET @voiceprint_sample_seconds_param_id = (
    SELECT CASE
        WHEN NOT EXISTS (SELECT 1 FROM `sys_params` WHERE `id` = 125) THEN 125
        ELSE COALESCE(MAX(`id`), 125) + 1
    END
    FROM `sys_params`
);

INSERT INTO `sys_params` (`id`, `param_code`, `param_value`, `value_type`, `param_type`, `remark`)
SELECT @voiceprint_sample_seconds_param_id,
       'server.voiceprint_sample_seconds',
       '1.0',
       'number',
       1,
       '声纹识别采样时长，单位秒，范围0.5-5.0；每句话有效人声不足该时长时跳过声纹识别'
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_params` WHERE `param_code` = 'server.voiceprint_sample_seconds'
);

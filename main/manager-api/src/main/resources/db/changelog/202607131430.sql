ALTER TABLE `ai_agent_voice_print`
    ADD COLUMN `remote_user_id` VARCHAR(255) NOT NULL DEFAULT 'default'
        COMMENT '远程voiceprint-api的user_id；历史数据为default，新数据为agent_id'
        AFTER `audio_id`,
    ADD INDEX `idx_agent_voice_print_agent_id` (`agent_id`);

SET @voiceprint_token_param_id = (
    SELECT CASE
        WHEN NOT EXISTS (SELECT 1 FROM `sys_params` WHERE `id` = 124) THEN 124
        ELSE COALESCE(MAX(`id`), 124) + 1
    END
    FROM `sys_params`
);

INSERT INTO `sys_params` (`id`, `param_code`, `param_value`, `value_type`, `param_type`, `remark`)
SELECT @voiceprint_token_param_id,
       'server.voiceprint_token',
       'null',
       'string',
       1,
       '远程声纹服务Bearer Token，仅通过Authorization Header传输'
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_params` WHERE `param_code` = 'server.voiceprint_token'
);

UPDATE `sys_params`
SET `remark` = '远程声纹服务地址，例如 https://voiceprint.example.com:8005；禁止在URL中携带Token'
WHERE `param_code` = 'server.voice_print';

SET @memory_capacity_param_id = (
    SELECT CASE
        WHEN NOT EXISTS (SELECT 1 FROM `sys_params` WHERE `id` = 125) THEN 125
        ELSE COALESCE(MAX(`id`), 125) + 1
    END
    FROM `sys_params`
);

INSERT INTO `sys_params` (`id`, `param_code`, `param_value`, `value_type`, `param_type`, `remark`)
SELECT @memory_capacity_param_id,
       'memory.active_context_max_chars',
       '1000',
       'number',
       1,
       '托管记忆active总容量和运行时全量注入上下文上限，单位为汉字/可见字符；降低前如已有记忆超限，请先在记忆管理执行审查/蒸馏并归档或合并低价值记忆'
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_params` WHERE `param_code` = 'memory.active_context_max_chars'
);

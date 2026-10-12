-- Register the conditional activity exit control; never enable a role plugin.
INSERT INTO `ai_model_provider` (
    `id`, `model_type`, `provider_code`, `name`, `fields`, `sort`,
    `creator`, `create_date`, `updater`, `update_date`
)
SELECT 'SYSTEM_TOOL_END_INTERACTION', 'Plugin', 'end_interaction', '结束当前互动',
       JSON_ARRAY(
           JSON_OBJECT('key', 'catalog_only', 'type', 'boolean', 'label', '仅目录展示', 'default', TRUE),
           JSON_OBJECT('key', 'runtime_builtin', 'type', 'boolean', 'label', '条件内置', 'default', TRUE),
           JSON_OBJECT('key', 'tool_source', 'type', 'string', 'label', '工具来源', 'default', 'server_plugin'),
           JSON_OBJECT('key', 'activation_hint', 'type', 'string', 'label', '启用方式', 'default', '仅当当前角色有已启用且本模型通路支持的互动活动时提供；不关闭整个对话。'),
           JSON_OBJECT('key', 'description', 'type', 'textarea', 'label', '工具调用说明',
                       'default', '用户明确结束当前互动或换话题离开互动时调用，随后继续回答用户本轮请求。')
       ), 6, 0, NOW(), 0, NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM `ai_model_provider`
    WHERE `model_type` = 'Plugin' AND `provider_code` = 'end_interaction'
);

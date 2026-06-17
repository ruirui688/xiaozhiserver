-- 删除本地酒类推荐插件，酒类问题统一走智能体已绑定的本地知识库或普通对话链路
DELETE FROM `ai_agent_plugin_mapping`
WHERE `plugin_id` = 'SYSTEM_PLUGIN_RECOMMEND_DRINK';

DELETE FROM `ai_model_provider`
WHERE `id` = 'SYSTEM_PLUGIN_RECOMMEND_DRINK'
   OR (`provider_code` = 'recommend_drink' AND `model_type` = 'Plugin');

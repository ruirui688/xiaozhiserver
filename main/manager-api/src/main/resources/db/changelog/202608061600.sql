-- 将连接级内置工具纳入资源中心目录，并为现有系统插件补齐目录级大模型提示词。
-- catalog_only=true 表示只在资源中心维护，不进入角色插件勾选列表；
-- runtime_builtin=true 表示提示词对所有在线会话生效。

INSERT INTO `ai_model_provider` (
    `id`, `model_type`, `provider_code`, `name`, `fields`, `sort`,
    `creator`, `create_date`, `updater`, `update_date`
)
SELECT 'SYSTEM_TOOL_DIRECT_ANSWER', 'Plugin', 'direct_answer', '直接回答路由',
       JSON_ARRAY(
           JSON_OBJECT('key', 'catalog_only', 'type', 'boolean', 'label', '仅目录展示', 'default', TRUE),
           JSON_OBJECT('key', 'runtime_builtin', 'type', 'boolean', 'label', '始终可用', 'default', TRUE),
           JSON_OBJECT('key', 'tool_source', 'type', 'string', 'label', '工具来源', 'default', 'routing'),
           JSON_OBJECT(
               'key', 'description', 'type', 'textarea', 'label', '大模型工具提示词',
               'default', '仅当用户请求不匹配任何已注册工具时直接回复，并将完整回复写入response参数。存在匹配工具时必须调用对应工具，不得用文字声称已经完成实际未执行的控制。'
           )
       ),
       1, 0, NOW(), 0, NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM `ai_model_provider`
    WHERE `model_type` = 'Plugin' AND `provider_code` = 'direct_answer'
);

INSERT INTO `ai_model_provider` (
    `id`, `model_type`, `provider_code`, `name`, `fields`, `sort`,
    `creator`, `create_date`, `updater`, `update_date`
)
SELECT 'SYSTEM_TOOL_EXIT', 'Plugin', 'handle_exit_intent', '结束当前对话',
       JSON_ARRAY(
           JSON_OBJECT('key', 'catalog_only', 'type', 'boolean', 'label', '仅目录展示', 'default', TRUE),
           JSON_OBJECT('key', 'runtime_builtin', 'type', 'boolean', 'label', '始终可用', 'default', TRUE),
           JSON_OBJECT('key', 'tool_source', 'type', 'string', 'label', '工具来源', 'default', 'server_plugin'),
           JSON_OBJECT(
               'key', 'description', 'type', 'textarea', 'label', '大模型工具提示词',
               'default', '仅当当前用户消息明确要求结束本次对话时调用。'
           )
       ),
       2, 0, NOW(), 0, NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM `ai_model_provider`
    WHERE `model_type` = 'Plugin' AND `provider_code` = 'handle_exit_intent'
);

INSERT INTO `ai_model_provider` (
    `id`, `model_type`, `provider_code`, `name`, `fields`, `sort`,
    `creator`, `create_date`, `updater`, `update_date`
)
SELECT 'SYSTEM_TOOL_SESSION_VOICE', 'Plugin', 'set_session_voice', '调整当前会话声音',
       JSON_ARRAY(
           JSON_OBJECT('key', 'catalog_only', 'type', 'boolean', 'label', '仅目录展示', 'default', TRUE),
           JSON_OBJECT('key', 'runtime_builtin', 'type', 'boolean', 'label', '始终可用', 'default', TRUE),
           JSON_OBJECT('key', 'tool_source', 'type', 'string', 'label', '工具来源', 'default', 'server_plugin'),
           JSON_OBJECT(
               'key', 'description', 'type', 'textarea', 'label', '大模型工具提示词',
               'default', '仅当当前用户消息明确要求调整本次会话的音色、语速、音量或音调时调用。'
           )
       ),
       3, 0, NOW(), 0, NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM `ai_model_provider`
    WHERE `model_type` = 'Plugin' AND `provider_code` = 'set_session_voice'
);

INSERT INTO `ai_model_provider` (
    `id`, `model_type`, `provider_code`, `name`, `fields`, `sort`,
    `creator`, `create_date`, `updater`, `update_date`
)
SELECT 'SYSTEM_TOOL_RESET_CONTEXT', 'Plugin', 'reset_conversation_context', '忘记此前聊天',
       JSON_ARRAY(
           JSON_OBJECT('key', 'catalog_only', 'type', 'boolean', 'label', '仅目录展示', 'default', TRUE),
           JSON_OBJECT('key', 'runtime_builtin', 'type', 'boolean', 'label', '始终可用', 'default', TRUE),
           JSON_OBJECT('key', 'tool_source', 'type', 'string', 'label', '工具来源', 'default', 'server_plugin'),
           JSON_OBJECT(
               'key', 'description', 'type', 'textarea', 'label', '大模型工具提示词',
               'default', '仅当当前用户消息直接要求忘记此前聊天内容时调用；其他任何情况都不要调用。'
           )
       ),
       4, 0, NOW(), 0, NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM `ai_model_provider`
    WHERE `model_type` = 'Plugin' AND `provider_code` = 'reset_conversation_context'
);

INSERT INTO `ai_model_provider` (
    `id`, `model_type`, `provider_code`, `name`, `fields`, `sort`,
    `creator`, `create_date`, `updater`, `update_date`
)
SELECT 'SYSTEM_TOOL_LUNAR', 'Plugin', 'get_lunar', '农历与黄历查询',
       JSON_ARRAY(
           JSON_OBJECT('key', 'catalog_only', 'type', 'boolean', 'label', '仅目录展示', 'default', TRUE),
           JSON_OBJECT('key', 'runtime_builtin', 'type', 'boolean', 'label', '始终可用', 'default', TRUE),
           JSON_OBJECT('key', 'tool_source', 'type', 'string', 'label', '工具来源', 'default', 'server_plugin'),
           JSON_OBJECT(
               'key', 'description', 'type', 'textarea', 'label', '大模型工具提示词',
               'default', '查询指定日期的农历、干支、节气、生肖、八字或宜忌等信息。仅查询当前日期的基础农历信息且系统上下文已经提供时不要调用。'
           )
       ),
       5, 0, NOW(), 0, NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM `ai_model_provider`
    WHERE `model_type` = 'Plugin' AND `provider_code` = 'get_lunar'
);

-- 下列工具由专用配置链路启用，不应出现在角色插件勾选列表，但其模型提示词
-- 仍统一放在资源中心维护。
INSERT INTO `ai_model_provider` (
    `id`, `model_type`, `provider_code`, `name`, `fields`, `sort`,
    `creator`, `create_date`, `updater`, `update_date`
)
SELECT 'SYSTEM_TOOL_FEISHU_ASSISTANT', 'Plugin', 'feishu_assistant', '飞书助手',
       JSON_ARRAY(
           JSON_OBJECT('key', 'catalog_only', 'type', 'boolean', 'label', '仅目录展示', 'default', TRUE),
           JSON_OBJECT('key', 'tool_source', 'type', 'string', 'label', '工具来源', 'default', 'server_plugin'),
           JSON_OBJECT('key', 'activation_hint', 'type', 'string', 'label', '启用方式', 'default', '在智能体飞书配置中完成绑定并开启具体动作后启用。'),
           JSON_OBJECT(
               'key', 'description', 'type', 'textarea', 'label', '大模型工具提示词',
               'default', '操作当前智能体已经绑定并授权的飞书目标。仅在用户明确要求发送群消息、查看群消息、创建任务或安排日程时调用；不得声称能操作未授权的群、任务负责人或日历。'
           )
       ),
       6, 0, NOW(), 0, NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM `ai_model_provider`
    WHERE `model_type` = 'Plugin' AND `provider_code` = 'feishu_assistant'
);

INSERT INTO `ai_model_provider` (
    `id`, `model_type`, `provider_code`, `name`, `fields`, `sort`,
    `creator`, `create_date`, `updater`, `update_date`
)
SELECT 'SYSTEM_TOOL_KNOWLEDGE_LOCAL', 'Plugin', 'search_from_local_knowledge', '本地知识库查询',
       JSON_ARRAY(
           JSON_OBJECT('key', 'catalog_only', 'type', 'boolean', 'label', '仅目录展示', 'default', TRUE),
           JSON_OBJECT('key', 'tool_source', 'type', 'string', 'label', '工具来源', 'default', 'server_plugin'),
           JSON_OBJECT('key', 'activation_hint', 'type', 'string', 'label', '启用方式', 'default', '角色绑定本地知识库后按运行配置启用。'),
           JSON_OBJECT(
               'key', 'description', 'type', 'textarea', 'label', '大模型工具提示词',
               'default', '从本地知识库中查询事实、资料、文档内容或业务知识。不要用于播放音乐、换歌、查询歌单或音乐库、控制设备或执行系统动作。'
           )
       ),
       7, 0, NOW(), 0, NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM `ai_model_provider`
    WHERE `model_type` = 'Plugin' AND `provider_code` = 'search_from_local_knowledge'
);

INSERT INTO `ai_model_provider` (
    `id`, `model_type`, `provider_code`, `name`, `fields`, `sort`,
    `creator`, `create_date`, `updater`, `update_date`
)
SELECT 'SYSTEM_TOOL_KNOWLEDGE_RAGFLOW', 'Plugin', 'search_from_ragflow', 'RAGFlow 知识库查询',
       JSON_ARRAY(
           JSON_OBJECT('key', 'catalog_only', 'type', 'boolean', 'label', '仅目录展示', 'default', TRUE),
           JSON_OBJECT('key', 'tool_source', 'type', 'string', 'label', '工具来源', 'default', 'server_plugin'),
           JSON_OBJECT('key', 'activation_hint', 'type', 'string', 'label', '启用方式', 'default', '角色绑定 RAGFlow 知识库后按运行配置启用。'),
           JSON_OBJECT(
               'key', 'description', 'type', 'textarea', 'label', '大模型工具提示词',
               'default', '从角色已绑定的 RAGFlow 知识库中查询事实、资料或文档内容。仅在回答依赖该知识库时调用。'
           )
       ),
       8, 0, NOW(), 0, NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM `ai_model_provider`
    WHERE `model_type` = 'Plugin' AND `provider_code` = 'search_from_ragflow'
);

INSERT INTO `ai_model_provider` (
    `id`, `model_type`, `provider_code`, `name`, `fields`, `sort`,
    `creator`, `create_date`, `updater`, `update_date`
)
SELECT 'SYSTEM_TOOL_CHANGE_ROLE', 'Plugin', 'change_role', '切换临时角色',
       JSON_ARRAY(
           JSON_OBJECT('key', 'catalog_only', 'type', 'boolean', 'label', '仅目录展示', 'default', TRUE),
           JSON_OBJECT('key', 'tool_source', 'type', 'string', 'label', '工具来源', 'default', 'server_plugin'),
           JSON_OBJECT('key', 'activation_hint', 'type', 'string', 'label', '启用方式', 'default', '由服务端 Intent functions 白名单配置启用。'),
           JSON_OBJECT(
               'key', 'description', 'type', 'textarea', 'label', '大模型工具提示词',
               'default', '仅当用户明确要求切换助手角色、性格或名字时调用；只可选择工具参数 Schema 中支持的角色。'
           )
       ),
       9, 0, NOW(), 0, NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM `ai_model_provider`
    WHERE `model_type` = 'Plugin' AND `provider_code` = 'change_role'
);

-- 现有系统插件没有目录提示词时，补齐与 Python 注册表一致的基础 description。
-- 音乐曲库、新闻源、Home Assistant 设备和会话音色等动态说明仍由 Python 在基础提示词之后追加。
UPDATE `ai_model_provider`
SET `fields` = JSON_ARRAY_APPEND(
        COALESCE(`fields`, JSON_ARRAY()),
        '$',
        JSON_OBJECT(
            'key', 'description',
            'type', 'textarea',
            'label', '大模型工具提示词',
            'default', CASE `provider_code`
                WHEN 'get_weather' THEN '获取某个地点的天气。用户应提供位置；省份默认查询省会。当前地点的未来天气已经在上下文中提供时不要重复调用；天气预警使用warning查询类型。'
                WHEN 'play_music' THEN '仅当用户明确要求播放音乐或歌曲时调用。'
                WHEN 'get_news_from_chinanews' THEN '当用户要求查看或收听新闻时调用。用户可以指定新闻类型；未指定时默认查询社会新闻。'
                WHEN 'get_news_from_newsnow' THEN '当用户要求查看或收听新闻、热榜或热搜时调用。'
                WHEN 'hass_get_state' THEN '查询Home Assistant设备状态，包括灯光、媒体播放器、音量、颜色、色温和播放状态。'
                WHEN 'hass_set_state' THEN '修改Home Assistant设备状态，包括开关、亮度、颜色、色温、音量、暂停、继续和静音。'
                WHEN 'hass_play_music' THEN '仅当用户要求通过房间内的Home Assistant媒体播放器播放音乐或有声书时调用。'
                WHEN 'call_device' THEN '仅当当前用户明确要求呼叫通讯录设备，或明确同意接听当前来电时调用。目标不明确时不要调用。'
                WHEN 'meeting_minutes' THEN '管理真实会议记录状态。用户明确要求现在开始记录时调用start；会议进行中的普通发言调用record；只有用户明确要求现在结束并生成纪要时调用end。'
                ELSE ''
            END
        )
    ),
    `update_date` = NOW()
WHERE `model_type` = 'Plugin'
  AND `provider_code` IN (
      'get_weather', 'play_music', 'get_news_from_chinanews',
      'get_news_from_newsnow', 'hass_get_state', 'hass_set_state',
      'hass_play_music', 'call_device', 'meeting_minutes'
  )
  AND JSON_SEARCH(COALESCE(`fields`, JSON_ARRAY()), 'one', 'description', NULL, '$[*].key') IS NULL;

-- 联网搜索历史记录已经包含 description，只补齐目录级标记，避免产生重复字段。
UPDATE `ai_model_provider`
SET `fields` = JSON_ARRAY_APPEND(
        COALESCE(`fields`, JSON_ARRAY()),
        '$',
        JSON_OBJECT('key', 'tool_source', 'type', 'string', 'label', '工具来源', 'default', 'server_plugin')
    ),
    `update_date` = NOW()
WHERE `model_type` = 'Plugin'
  AND `provider_code` = 'web_search'
  AND JSON_SEARCH(COALESCE(`fields`, JSON_ARRAY()), 'one', 'tool_source', NULL, '$[*].key') IS NULL;

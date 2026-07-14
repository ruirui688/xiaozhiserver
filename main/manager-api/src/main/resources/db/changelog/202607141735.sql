INSERT INTO `ai_model_provider` (
    `id`,
    `model_type`,
    `provider_code`,
    `name`,
    `fields`,
    `sort`,
    `creator`,
    `create_date`,
    `updater`,
    `update_date`
)
SELECT 'SYSTEM_PLUGIN_MEETING_MINUTES',
       'Plugin',
       'meeting_minutes',
       '会议纪要',
       JSON_ARRAY(
           JSON_OBJECT(
               'key', 'entry_prompt',
               'type', 'string',
               'label', '进入会议提醒',
               'default', '已进入会议模式，我会静默记录。需要结束时，直接告诉我即可。'
           ),
           JSON_OBJECT(
               'key', 'control_timeout_seconds',
               'type', 'number',
               'label', '控制语义超时（秒）',
               'default', 1.5
           ),
           JSON_OBJECT(
               'key', 'end_confidence_threshold',
               'type', 'number',
               'label', '结束意图置信度',
               'default', 0.9
           )
       ),
       25,
       0,
       NOW(),
       0,
       NOW()
WHERE NOT EXISTS (
    SELECT 1
    FROM `ai_model_provider`
    WHERE `id` = 'SYSTEM_PLUGIN_MEETING_MINUTES'
       OR (`model_type` = 'Plugin' AND `provider_code` = 'meeting_minutes')
);

UPDATE `ai_model_provider`
SET `fields` = JSON_ARRAY(
        JSON_OBJECT(
            'key', 'entry_prompt',
            'type', 'string',
            'label', '进入会议提醒',
            'default', '已进入会议模式，我会静默记录。需要结束时，直接告诉我即可。'
        )
    ),
    `update_date` = NOW()
WHERE `id` = 'SYSTEM_PLUGIN_MEETING_MINUTES'
   OR (`model_type` = 'Plugin' AND `provider_code` = 'meeting_minutes');

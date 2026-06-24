UPDATE `ai_model_template`
SET `product_name` = '豆包语音合成 HTTP 兼容',
    `enabled` = 0,
    `supported_status` = 'DEPRECATED',
    `selectable` = 0,
    `hidden` = 1,
    `deprecated` = 1,
    `recommended_for` = '仅兼容历史 DoubaoTTS 配置',
    `avoid_when` = '新增豆包语音合成配置不要选择该入口，请使用“豆包语音合成大模型”或“豆包语音合成模型 2.0”。',
    `setup_hints_json` = JSON_ARRAY('该入口仅用于兼容历史 DoubaoTTS HTTP 配置。'),
    `known_limitations_json` = JSON_ARRAY('新增配置入口已隐藏，避免和当前火山控制台模型名称混淆。'),
    `source_checked_at` = '2026-06-24'
WHERE `template_code` = 'TTS_DOUBAO_HTTP';

UPDATE `ai_model_template`
SET `adapter_code` = 'huoshan_double_stream',
    `vendor_name` = '火山引擎',
    `product_name` = '豆包语音合成大模型',
    `capability` = 'tts',
    `access_mode` = 'websocket-bidirectional',
    `deployment` = 'cloud',
    `protocol` = 'WebSocket',
    `upstream_model_field` = 'resource_id',
    `doc_link` = 'https://www.volcengine.com/docs/6561/1329505',
    `enabled` = 1,
    `supported_status` = 'SUPPORTED',
    `selectable` = 1,
    `hidden` = 0,
    `deprecated` = 0,
    `recommended_for` = '低延迟设备语音播报',
    `avoid_when` = '如果火山控制台开通的是“豆包语音合成模型 2.0”，请选择对应 2.0 入口。',
    `test_contract` = '必须真实生成可播放音频并记录耗时',
    `setup_hints_json` = JSON_ARRAY('对应火山控制台“语音合成大模型”。', '模型资源 ID 通常为 volc.service_type.10029。', '音色填写控制台音色列表里的 Voice_type。'),
    `known_limitations_json` = JSON_ARRAY('需要在火山语音控制台开通该模型和音色，否则鉴权成功也可能合成失败。'),
    `aliases_json` = JSON_ARRAY('HuoshanDoubleStreamTTS', 'volc.service_type.10029'),
    `source_checked_at` = '2026-06-24'
WHERE `template_code` = 'TTS_DOUBAO_BIGTTS_STREAM';

UPDATE `ai_model_template`
SET `adapter_code` = 'huoshan_double_stream',
    `vendor_name` = '火山引擎',
    `product_name` = '豆包语音合成模型 2.0',
    `capability` = 'tts',
    `access_mode` = 'websocket-bidirectional',
    `deployment` = 'cloud',
    `protocol` = 'WebSocket',
    `upstream_model_field` = 'resource_id',
    `doc_link` = 'https://www.volcengine.com/docs/6561/1329505',
    `enabled` = 1,
    `supported_status` = 'SUPPORTED',
    `selectable` = 1,
    `hidden` = 0,
    `deprecated` = 0,
    `recommended_for` = '低延迟设备语音播报，优先用于新豆包 TTS 配置',
    `avoid_when` = '如果火山控制台开通的是“语音合成大模型”而不是 2.0，请选择非 2.0 入口。',
    `test_contract` = '必须真实生成可播放音频并记录耗时',
    `setup_hints_json` = JSON_ARRAY('对应火山控制台“豆包语音合成模型 2.0”。', '模型资源 ID 固定为 seed-tts-2.0。', '音色填写控制台音色列表里的 Voice_type。'),
    `known_limitations_json` = JSON_ARRAY('需要在火山语音控制台开通该模型和音色，否则鉴权成功也可能合成失败。'),
    `aliases_json` = JSON_ARRAY('HuoshanDoubleStreamTTSV2', 'seed-tts-2.0', 'TTS_HSDSTTS_V2'),
    `source_checked_at` = '2026-06-24'
WHERE `template_code` = 'TTS_DOUBAO_SEED_TTS_2_STREAM';

UPDATE `ai_model_template_field`
SET `field_label` = '模型资源 ID',
    `default_value` = 'volc.service_type.10029',
    `help_text` = '对应火山控制台“语音合成大模型”的 Resource ID，通常为 volc.service_type.10029。'
WHERE `template_id` = 'TTS_DOUBAO_BIGTTS_STREAM'
  AND `field_key` = 'resource_id';

UPDATE `ai_model_template_field`
SET `field_label` = '模型资源 ID',
    `default_value` = 'seed-tts-2.0',
    `help_text` = '对应火山控制台“豆包语音合成模型 2.0”的 Resource ID，固定为 seed-tts-2.0。'
WHERE `template_id` = 'TTS_DOUBAO_SEED_TTS_2_STREAM'
  AND `field_key` = 'resource_id';

UPDATE `ai_model_template_field`
SET `field_label` = '音色 Voice_type',
    `help_text` = '填写火山控制台音色列表中的 Voice_type，例如 zh_female_xiaohe_uranus_bigtts。'
WHERE `template_id` IN ('TTS_DOUBAO_BIGTTS_STREAM', 'TTS_DOUBAO_SEED_TTS_2_STREAM')
  AND `field_key` = 'speaker';

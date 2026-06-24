SET @doubao_bigtts = 'TTS_DOUBAO_BIGTTS_STREAM';
SET @doubao_seed_tts_2 = 'TTS_DOUBAO_SEED_TTS_2_STREAM';
SET @doubao_ws_url = 'wss://openspeech.bytedance.com/api/v3/tts/bidirection';

UPDATE `ai_model_template`
SET `enabled` = 0,
    `supported_status` = 'DEPRECATED',
    `selectable` = 0,
    `hidden` = 1,
    `deprecated` = 1,
    `recommended_for` = '历史兼容，不作为本项目豆包 TTS 有效入口',
    `avoid_when` = '本项目豆包语音合成只保留火山服务 10007 和 10035 两个双向流式入口。',
    `known_limitations_json` = JSON_ARRAY('旧 HTTP、第三方代理和 AI Gateway TTS 已从新增配置入口隐藏。'),
    `source_checked_at` = '2026-06-25'
WHERE `template_code` IN ('TTS_DOUBAO_HTTP', 'TTS_VOLCES_AI_GATEWAY_OPENAI');

UPDATE `ai_model_template`
SET `enabled` = 1,
    `supported_status` = 'SUPPORTED',
    `selectable` = 1,
    `hidden` = 0,
    `deprecated` = 0,
    `adapter_code` = 'huoshan_double_stream',
    `access_mode` = 'websocket-bidirectional',
    `protocol` = 'WebSocket',
    `upstream_model_field` = 'resource_id',
    `doc_link` = 'https://www.volcengine.com/docs/6561/1329505',
    `test_contract` = '必须真实生成可播放音频并记录耗时',
    `setup_hints_json` = JSON_ARRAY(
      '只支持火山官方双向流式 TTS。',
      '常用音色、语速、音量、音高和情感可直接用字段配置。',
      '高级参数仍可通过 audio_params/additions/mix_speaker JSON 透传。'
    ),
    `known_limitations_json` = JSON_ARRAY('需要在对应火山语音服务中开通模型和音色。'),
    `source_checked_at` = '2026-06-25'
WHERE `template_code` IN (@doubao_bigtts, @doubao_seed_tts_2);

DELETE FROM `ai_model_template_field`
WHERE `template_id` IN (@doubao_bigtts, @doubao_seed_tts_2);

INSERT INTO `ai_model_template_field`
(`template_id`, `field_key`, `field_label`, `field_group`, `value_type`, `input_type`, `runtime_key`, `advanced`, `visible_when`, `required`, `secret`, `default_value`, `options_json`, `help_text`, `sort`)
VALUES
(@doubao_bigtts, 'ws_url', 'WebSocket URL', 'endpoint', 'string', 'text', 'ws_url', 1, NULL, 1, 0, @doubao_ws_url, NULL, '固定为火山官方双向流式 TTS 地址，通常无需修改。', 1),
(@doubao_bigtts, 'api_key', 'API Key', 'credentials', 'string', 'password', 'api_key', 0, NULL, 0, 1, NULL, NULL, '火山控制台“快捷 API 接入”中的 API Key；优先使用。未填写时必须填写旧版 App ID + Access Token。', 2),
(@doubao_bigtts, 'appid', 'App ID', 'credentials', 'string', 'text', 'appid', 1, NULL, 0, 0, NULL, NULL, '旧版 App ID 接入兼容字段；已填写 API Key 时留空。', 3),
(@doubao_bigtts, 'access_token', 'Access Token', 'credentials', 'string', 'password', 'access_token', 1, NULL, 0, 1, NULL, NULL, '旧版 Access Token 接入兼容字段；已填写 API Key 时留空。', 4),
(@doubao_bigtts, 'resource_id', '模型资源 ID', 'upstream', 'string', 'text', 'resource_id', 0, NULL, 1, 0, 'volc.service_type.10029', NULL, '火山服务 10007：豆包语音合成大模型，固定使用 volc.service_type.10029。', 5),
(@doubao_bigtts, 'speaker', '音色 Voice_type', 'audio', 'string', 'text', 'speaker', 0, NULL, 1, 0, 'zh_female_wanwanxiaohe_moon_bigtts', NULL, '填写火山控制台音色列表中的 Voice_type。常用：湾湾小何、少年梓辛、爽快思思、温暖阿虎等。', 6),
(@doubao_bigtts, 'format', '音频格式', 'audio', 'string', 'select', 'format', 0, NULL, 0, 0, 'pcm', JSON_ARRAY('pcm', 'wav', 'mp3'), '设备实时播报推荐 pcm；下载试听可用 wav 或 mp3。', 7),
(@doubao_bigtts, 'speech_rate', '语速', 'audio', 'number', 'number', 'speech_rate', 0, NULL, 0, 0, '0', NULL, '范围 -50 到 100，0 为默认。', 8),
(@doubao_bigtts, 'loudness_rate', '音量', 'audio', 'number', 'number', 'loudness_rate', 0, NULL, 0, 0, '0', NULL, '范围 -50 到 100，0 为默认。', 9),
(@doubao_bigtts, 'pitch', '音高', 'audio', 'number', 'number', 'pitch', 0, NULL, 0, 0, '0', NULL, '范围 -12 到 12，0 为默认。', 10),
(@doubao_bigtts, 'emotion', '情感', 'audio', 'string', 'select', 'emotion', 0, NULL, 0, 0, NULL, JSON_ARRAY('neutral', 'happy', 'sad', 'angry', 'fearful', 'disgusted', 'surprised'), '仅部分音色支持；不确定时留空。', 11),
(@doubao_bigtts, 'emotion_scale', '情感强度', 'audio', 'number', 'number', 'emotion_scale', 0, NULL, 0, 0, NULL, NULL, '范围 1 到 5，仅 emotion 生效时使用。', 12),
(@doubao_bigtts, 'enable_ws_reuse', '复用 WebSocket', 'advanced', 'boolean', 'switch', 'enable_ws_reuse', 1, NULL, 0, 0, 'true', NULL, '开启后降低首包延迟，但空闲连接可能占用并发。', 13),
(@doubao_bigtts, 'audio_params', '高级音频参数 JSON', 'advanced', 'json', 'textarea', 'audio_params', 1, NULL, 0, 0, '{\"speech_rate\":0,\"loudness_rate\":0}', NULL, '火山 audio_params 透传；普通设置优先用上方字段。', 14),
(@doubao_bigtts, 'additions', '高级处理参数 JSON', 'advanced', 'json', 'textarea', 'additions', 1, NULL, 0, 0, '{\"post_process\":{\"pitch\":0}}', NULL, '火山 additions 透传；普通音高优先用上方字段。', 15),
(@doubao_bigtts, 'mix_speaker', '混音参数 JSON', 'advanced', 'json', 'textarea', 'mix_speaker', 1, NULL, 0, 0, NULL, NULL, '仅 TTS 1.0 混音场景使用，普通音色不要填写。', 16),
(@doubao_seed_tts_2, 'ws_url', 'WebSocket URL', 'endpoint', 'string', 'text', 'ws_url', 1, NULL, 1, 0, @doubao_ws_url, NULL, '固定为火山官方双向流式 TTS 地址，通常无需修改。', 1),
(@doubao_seed_tts_2, 'api_key', 'API Key', 'credentials', 'string', 'password', 'api_key', 0, NULL, 0, 1, NULL, NULL, '火山控制台“快捷 API 接入”中的 API Key；优先使用。未填写时必须填写旧版 App ID + Access Token。', 2),
(@doubao_seed_tts_2, 'appid', 'App ID', 'credentials', 'string', 'text', 'appid', 1, NULL, 0, 0, NULL, NULL, '旧版 App ID 接入兼容字段；已填写 API Key 时留空。', 3),
(@doubao_seed_tts_2, 'access_token', 'Access Token', 'credentials', 'string', 'password', 'access_token', 1, NULL, 0, 1, NULL, NULL, '旧版 Access Token 接入兼容字段；已填写 API Key 时留空。', 4),
(@doubao_seed_tts_2, 'resource_id', '模型资源 ID', 'upstream', 'string', 'text', 'resource_id', 0, NULL, 1, 0, 'seed-tts-2.0', NULL, '火山服务 10035：豆包语音合成模型 2.0，固定使用 seed-tts-2.0。', 5),
(@doubao_seed_tts_2, 'speaker', '音色 Voice_type', 'audio', 'string', 'text', 'speaker', 0, NULL, 1, 0, 'zh_female_xiaohe_uranus_bigtts', NULL, '填写火山服务 10035 控制台音色列表中的 Voice_type。', 6),
(@doubao_seed_tts_2, 'format', '音频格式', 'audio', 'string', 'select', 'format', 0, NULL, 0, 0, 'pcm', JSON_ARRAY('pcm', 'wav', 'mp3'), '设备实时播报推荐 pcm；下载试听可用 wav 或 mp3。', 7),
(@doubao_seed_tts_2, 'speech_rate', '语速', 'audio', 'number', 'number', 'speech_rate', 0, NULL, 0, 0, '0', NULL, '范围 -50 到 100，0 为默认。', 8),
(@doubao_seed_tts_2, 'loudness_rate', '音量', 'audio', 'number', 'number', 'loudness_rate', 0, NULL, 0, 0, '0', NULL, '范围 -50 到 100，0 为默认。', 9),
(@doubao_seed_tts_2, 'pitch', '音高', 'audio', 'number', 'number', 'pitch', 0, NULL, 0, 0, '0', NULL, '范围 -12 到 12，0 为默认。', 10),
(@doubao_seed_tts_2, 'emotion', '情感', 'audio', 'string', 'select', 'emotion', 0, NULL, 0, 0, NULL, JSON_ARRAY('neutral', 'happy', 'sad', 'angry', 'fearful', 'disgusted', 'surprised'), '仅部分音色支持；不确定时留空。', 11),
(@doubao_seed_tts_2, 'emotion_scale', '情感强度', 'audio', 'number', 'number', 'emotion_scale', 0, NULL, 0, 0, NULL, NULL, '范围 1 到 5，仅 emotion 生效时使用。', 12),
(@doubao_seed_tts_2, 'enable_ws_reuse', '复用 WebSocket', 'advanced', 'boolean', 'switch', 'enable_ws_reuse', 1, NULL, 0, 0, 'true', NULL, '开启后降低首包延迟，但空闲连接可能占用并发。', 13),
(@doubao_seed_tts_2, 'audio_params', '高级音频参数 JSON', 'advanced', 'json', 'textarea', 'audio_params', 1, NULL, 0, 0, '{\"speech_rate\":0,\"loudness_rate\":0}', NULL, '火山 audio_params 透传；普通设置优先用上方字段。', 14),
(@doubao_seed_tts_2, 'additions', '高级处理参数 JSON', 'advanced', 'json', 'textarea', 'additions', 1, NULL, 0, 0, '{\"post_process\":{\"pitch\":0}}', NULL, '火山 additions 透传；普通音高优先用上方字段。', 15);

UPDATE `ai_model_config`
SET `is_enabled` = 0,
    `is_default` = 0,
    `model_name` = CONCAT(`model_name`, '（已停用）')
WHERE `id` IN ('TTS_DoubaoTTS', 'TTS_TTS302AI', 'TTS_GizwitsTTS', 'TTS_VolcesAiGatewayTTS')
  AND `model_name` NOT LIKE '%已停用%';

UPDATE `ai_model_config`
SET `is_enabled` = 0,
    `is_default` = 0
WHERE `id` IN ('TTS_DoubaoTTS', 'TTS_TTS302AI', 'TTS_GizwitsTTS', 'TTS_VolcesAiGatewayTTS');

UPDATE `ai_model_config`
SET `config_json` = JSON_SET(
      COALESCE(`config_json`, JSON_OBJECT()),
      '$.type', 'huoshan_double_stream',
      '$.ws_url', @doubao_ws_url,
      '$.format', COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.format')), ''), 'pcm'),
      '$.speech_rate', CASE
        WHEN JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.speech_rate') IS NULL THEN 0
        ELSE JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.speech_rate')
      END,
      '$.loudness_rate', CASE
        WHEN JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.loudness_rate') IS NULL THEN 0
        ELSE JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.loudness_rate')
      END,
      '$.pitch', CASE
        WHEN JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.pitch') IS NULL THEN 0
        ELSE JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.pitch')
      END,
      '$.enable_ws_reuse', CASE
        WHEN JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.enable_ws_reuse') IS NULL THEN 'true'
        ELSE JSON_EXTRACT(COALESCE(`config_json`, JSON_OBJECT()), '$.enable_ws_reuse')
      END
    )
WHERE UPPER(`model_type`) = 'TTS'
  AND (`template_code` IN (@doubao_bigtts, @doubao_seed_tts_2) OR `adapter_code` = 'huoshan_double_stream');

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT `id`, 'format', 'pcm', 'string', 'audio'
FROM `ai_model_config`
WHERE UPPER(`model_type`) = 'TTS'
  AND (`template_code` IN (@doubao_bigtts, @doubao_seed_tts_2) OR `adapter_code` = 'huoshan_double_stream')
ON DUPLICATE KEY UPDATE `value_type` = 'string', `param_group` = 'audio';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT `id`, 'speech_rate', '0', 'number', 'audio'
FROM `ai_model_config`
WHERE UPPER(`model_type`) = 'TTS'
  AND (`template_code` IN (@doubao_bigtts, @doubao_seed_tts_2) OR `adapter_code` = 'huoshan_double_stream')
ON DUPLICATE KEY UPDATE `value_type` = 'number', `param_group` = 'audio';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT `id`, 'loudness_rate', '0', 'number', 'audio'
FROM `ai_model_config`
WHERE UPPER(`model_type`) = 'TTS'
  AND (`template_code` IN (@doubao_bigtts, @doubao_seed_tts_2) OR `adapter_code` = 'huoshan_double_stream')
ON DUPLICATE KEY UPDATE `value_type` = 'number', `param_group` = 'audio';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT `id`, 'pitch', '0', 'number', 'audio'
FROM `ai_model_config`
WHERE UPPER(`model_type`) = 'TTS'
  AND (`template_code` IN (@doubao_bigtts, @doubao_seed_tts_2) OR `adapter_code` = 'huoshan_double_stream')
ON DUPLICATE KEY UPDATE `value_type` = 'number', `param_group` = 'audio';

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT `id`, 'enable_ws_reuse', 'true', 'boolean', 'advanced'
FROM `ai_model_config`
WHERE UPPER(`model_type`) = 'TTS'
  AND (`template_code` IN (@doubao_bigtts, @doubao_seed_tts_2) OR `adapter_code` = 'huoshan_double_stream')
ON DUPLICATE KEY UPDATE `value_type` = 'boolean', `param_group` = 'advanced';

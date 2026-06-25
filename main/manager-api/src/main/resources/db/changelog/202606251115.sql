SET @doubao_bigtts = 'TTS_DOUBAO_BIGTTS_STREAM';
SET @doubao_seed_tts_2 = 'TTS_DOUBAO_SEED_TTS_2_STREAM';
SET @doubao_ws_url = 'wss://openspeech.bytedance.com/api/v3/tts/bidirection';

-- ============================================================
-- 对齐上游 github.com/xinnan-tech/xiaozhi-esp32-server 配置风格
-- 核心字段只有 5 个：appid、access_token、resource_id、speaker、audio_params
-- 其余全部收入高级参数
-- ============================================================

-- 1. 更新模板元数据：credential 改为 appid + access_token（匹配上游）
UPDATE `ai_model_template`
SET `product_name` = '语音合成大模型（服务 10007）',
    `doc_link` = 'https://console.volcengine.com/speech/service/10007',
    `setup_hints_json` = JSON_ARRAY(
      '访问 https://console.volcengine.com/speech/service/10007 开通语音合成大模型，购买音色',
      '在页面底部获取 appid 和 access_token',
      '资源 ID 固定填写 volc.service_type.10029'
    ),
    `known_limitations_json` = NULL,
    `source_checked_at` = '2026-06-25'
WHERE `template_code` = @doubao_bigtts;

UPDATE `ai_model_template`
SET `product_name` = '语音合成模型 2.0（服务 10035）',
    `doc_link` = 'https://console.volcengine.com/speech/service/10035',
    `setup_hints_json` = JSON_ARRAY(
      '访问 https://console.volcengine.com/speech/service/10035 开通语音合成模型 2.0，购买音色',
      '在页面底部获取 appid 和 access_token',
      '资源 ID 固定填写 seed-tts-2.0'
    ),
    `known_limitations_json` = NULL,
    `source_checked_at` = '2026-06-25'
WHERE `template_code` = @doubao_seed_tts_2;

-- 2. 删除旧字段定义，重建（按上游优先级排序）
DELETE FROM `ai_model_template_field`
WHERE `template_id` IN (@doubao_bigtts, @doubao_seed_tts_2);

-- 服务 10007：核心字段在前，高级字段在后
INSERT INTO `ai_model_template_field`
(`template_id`, `field_key`, `field_label`, `field_group`, `value_type`, `input_type`, `runtime_key`, `advanced`, `visible_when`, `required`, `secret`, `default_value`, `options_json`, `help_text`, `sort`)
VALUES
-- 核心凭据（匹配上游 appid + access_token）
(@doubao_bigtts, 'appid', 'App ID', 'credentials', 'string', 'text', 'appid', 0, NULL, 1, 0, NULL, NULL, '火山控制台服务 10007 页面底部获取', 1),
(@doubao_bigtts, 'access_token', 'Access Token', 'credentials', 'string', 'password', 'access_token', 0, NULL, 1, 1, NULL, NULL, '火山控制台服务 10007 页面底部获取', 2),
-- 上游模型
(@doubao_bigtts, 'ws_url', 'WebSocket 地址', 'endpoint', 'string', 'text', 'ws_url', 1, NULL, 1, 0, @doubao_ws_url, NULL, '固定值，无需修改', 3),
(@doubao_bigtts, 'resource_id', '资源 ID', 'upstream', 'string', 'text', 'resource_id', 0, NULL, 1, 0, 'volc.service_type.10029', NULL, '固定填写 volc.service_type.10029', 4),
(@doubao_bigtts, 'speaker', '音色', 'audio', 'string', 'text', 'speaker', 0, NULL, 1, 0, 'zh_female_wanwanxiaohe_moon_bigtts', NULL, '填写火山控制台音色列表中的 Voice Type，例如 zh_female_wanwanxiaohe_moon_bigtts', 5),
-- 高级参数（全部收入 advanced，非必填）
(@doubao_bigtts, 'api_key', 'API Key（可选）', 'advanced', 'string', 'password', 'api_key', 1, NULL, 0, 1, NULL, NULL, '新版控制台 API Key 鉴权方式，与 App ID + Access Token 二选一', 10),
(@doubao_bigtts, 'format', '音频格式', 'advanced', 'string', 'select', 'format', 1, NULL, 0, 0, 'pcm', JSON_ARRAY('pcm', 'wav', 'mp3'), '设备播放推荐 pcm', 11),
(@doubao_bigtts, 'audio_params', '音频参数 JSON', 'advanced', 'json', 'textarea', 'audio_params', 1, NULL, 0, 0, '{"speech_rate":0,"loudness_rate":0}', NULL, '语速(-50~100)、音量(-50~100)等，详见火山文档', 12),
(@doubao_bigtts, 'enable_ws_reuse', '复用 WebSocket', 'advanced', 'boolean', 'switch', 'enable_ws_reuse', 1, NULL, 0, 0, 'true', NULL, '开启后降低首包延迟，空闲连接占用并发', 13),
(@doubao_bigtts, 'speech_rate', '语速', 'advanced', 'number', 'number', 'speech_rate', 1, NULL, 0, 0, NULL, NULL, '范围 -50~100，建议在 audio_params 中设置', 14),
(@doubao_bigtts, 'loudness_rate', '音量', 'advanced', 'number', 'number', 'loudness_rate', 1, NULL, 0, 0, NULL, NULL, '范围 -50~100，建议在 audio_params 中设置', 15),
(@doubao_bigtts, 'pitch', '音高', 'advanced', 'number', 'number', 'pitch', 1, NULL, 0, 0, NULL, NULL, '范围 -12~12，建议在 additions 中设置', 16),
(@doubao_bigtts, 'emotion', '情感', 'advanced', 'string', 'select', 'emotion', 1, NULL, 0, 0, NULL, JSON_ARRAY('neutral', 'happy', 'sad', 'angry', 'fearful', 'disgusted', 'surprised'), '仅部分多情感音色支持', 17),
(@doubao_bigtts, 'emotion_scale', '情感强度', 'advanced', 'number', 'number', 'emotion_scale', 1, NULL, 0, 0, NULL, NULL, '1~5，仅 emotion 生效时使用', 18),
(@doubao_bigtts, 'additions', '高级处理 JSON', 'advanced', 'json', 'textarea', 'additions', 1, NULL, 0, 0, NULL, NULL, '火山 additions 透传，普通用户无需填写', 19),
(@doubao_bigtts, 'mix_speaker', '混音 JSON', 'advanced', 'json', 'textarea', 'mix_speaker', 1, NULL, 0, 0, NULL, NULL, '仅 TTS 1.0 混音场景使用', 20);

-- 服务 10035：核心字段在前，高级字段在后
INSERT INTO `ai_model_template_field`
(`template_id`, `field_key`, `field_label`, `field_group`, `value_type`, `input_type`, `runtime_key`, `advanced`, `visible_when`, `required`, `secret`, `default_value`, `options_json`, `help_text`, `sort`)
VALUES
-- 核心凭据（匹配上游 appid + access_token）
(@doubao_seed_tts_2, 'appid', 'App ID', 'credentials', 'string', 'text', 'appid', 0, NULL, 1, 0, NULL, NULL, '火山控制台服务 10035 页面底部获取', 1),
(@doubao_seed_tts_2, 'access_token', 'Access Token', 'credentials', 'string', 'password', 'access_token', 0, NULL, 1, 1, NULL, NULL, '火山控制台服务 10035 页面底部获取', 2),
-- 上游模型
(@doubao_seed_tts_2, 'ws_url', 'WebSocket 地址', 'endpoint', 'string', 'text', 'ws_url', 1, NULL, 1, 0, @doubao_ws_url, NULL, '固定值，无需修改', 3),
(@doubao_seed_tts_2, 'resource_id', '资源 ID', 'upstream', 'string', 'text', 'resource_id', 0, NULL, 1, 0, 'seed-tts-2.0', NULL, '固定填写 seed-tts-2.0', 4),
(@doubao_seed_tts_2, 'speaker', '音色', 'audio', 'string', 'text', 'speaker', 0, NULL, 1, 0, 'zh_female_xiaohe_uranus_bigtts', NULL, '填写火山控制台服务 10035 音色列表中的 Voice Type', 5),
-- 高级参数（全部收入 advanced，非必填）
(@doubao_seed_tts_2, 'api_key', 'API Key（可选）', 'advanced', 'string', 'password', 'api_key', 1, NULL, 0, 1, NULL, NULL, '新版控制台 API Key 鉴权方式，与 App ID + Access Token 二选一', 10),
(@doubao_seed_tts_2, 'format', '音频格式', 'advanced', 'string', 'select', 'format', 1, NULL, 0, 0, 'pcm', JSON_ARRAY('pcm', 'wav', 'mp3'), '设备播放推荐 pcm', 11),
(@doubao_seed_tts_2, 'audio_params', '音频参数 JSON', 'advanced', 'json', 'textarea', 'audio_params', 1, NULL, 0, 0, '{"speech_rate":0,"loudness_rate":0}', NULL, '语速(-50~100)、音量(-50~100)等，详见火山文档', 12),
(@doubao_seed_tts_2, 'enable_ws_reuse', '复用 WebSocket', 'advanced', 'boolean', 'switch', 'enable_ws_reuse', 1, NULL, 0, 0, 'true', NULL, '开启后降低首包延迟，空闲连接占用并发', 13),
(@doubao_seed_tts_2, 'speech_rate', '语速', 'advanced', 'number', 'number', 'speech_rate', 1, NULL, 0, 0, NULL, NULL, '范围 -50~100，建议在 audio_params 中设置', 14),
(@doubao_seed_tts_2, 'loudness_rate', '音量', 'advanced', 'number', 'number', 'loudness_rate', 1, NULL, 0, 0, NULL, NULL, '范围 -50~100，建议在 audio_params 中设置', 15),
(@doubao_seed_tts_2, 'pitch', '音高', 'advanced', 'number', 'number', 'pitch', 1, NULL, 0, 0, NULL, NULL, '范围 -12~12，建议在 additions 中设置', 16),
(@doubao_seed_tts_2, 'emotion', '情感', 'advanced', 'string', 'select', 'emotion', 1, NULL, 0, 0, NULL, JSON_ARRAY('neutral', 'happy', 'sad', 'angry', 'fearful', 'disgusted', 'surprised'), '仅部分多情感音色支持', 17),
(@doubao_seed_tts_2, 'emotion_scale', '情感强度', 'advanced', 'number', 'number', 'emotion_scale', 1, NULL, 0, 0, NULL, NULL, '1~5，仅 emotion 生效时使用', 18),
(@doubao_seed_tts_2, 'additions', '高级处理 JSON', 'advanced', 'json', 'textarea', 'additions', 1, NULL, 0, 0, NULL, NULL, '火山 additions 透传，普通用户无需填写', 19);

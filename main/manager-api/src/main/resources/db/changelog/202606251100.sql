SET @doubao_bigtts = 'TTS_DOUBAO_BIGTTS_STREAM';
SET @doubao_seed_tts_2 = 'TTS_DOUBAO_SEED_TTS_2_STREAM';

-- 更新服务 10007（豆包语音合成大模型）模板元数据
UPDATE `ai_model_template`
SET `product_name` = '语音合成大模型（服务 10007）',
    `doc_link` = 'https://console.volcengine.com/speech/service/10007',
    `setup_hints_json` = JSON_ARRAY(
      '火山控制台 → 语音合成 → 服务 10007',
      'resource_id 固定填写 volc.service_type.10029',
      '推荐使用 API Key 接入；旧版 App ID + Access Token 仅作为兼容字段'
    ),
    `known_limitations_json` = JSON_ARRAY('需要在火山服务 10007 中开通模型和音色；音色列表以控制台实际展示为准'),
    `source_checked_at` = '2026-06-25'
WHERE `template_code` = @doubao_bigtts;

-- 更新服务 10035（豆包语音合成模型 2.0）模板元数据
UPDATE `ai_model_template`
SET `product_name` = '语音合成模型 2.0（服务 10035）',
    `doc_link` = 'https://console.volcengine.com/speech/service/10035',
    `setup_hints_json` = JSON_ARRAY(
      '火山控制台 → 语音合成 → 服务 10035',
      'resource_id 固定填写 seed-tts-2.0',
      '推荐使用 API Key 接入；旧版 App ID + Access Token 仅作为兼容字段'
    ),
    `known_limitations_json` = JSON_ARRAY('需要在火山服务 10035 中开通模型和音色；2.0 模型音色列表与大模型（10007）不同，需在 10035 控制台单独查看'),
    `source_checked_at` = '2026-06-25'
WHERE `template_code` = @doubao_seed_tts_2;

-- 更新 api_key 字段的 help_text：强调优先使用
UPDATE `ai_model_template_field`
SET `help_text` = '推荐使用。火山控制台“快捷 API 接入”生成的 API Key；已填写则无需填写旧版 App ID + Access Token。'
WHERE `template_id` IN (@doubao_bigtts, @doubao_seed_tts_2)
  AND `field_key` = 'api_key';

-- 更新 appid 字段的 help_text：明确旧版兼容身份
UPDATE `ai_model_template_field`
SET `help_text` = '旧版接入兼容字段，非密钥。已填写 API Key 时请留空。'
WHERE `template_id` IN (@doubao_bigtts, @doubao_seed_tts_2)
  AND `field_key` = 'appid';

-- 更新 access_token 字段的 help_text：明确旧版兼容身份
UPDATE `ai_model_template_field`
SET `help_text` = '旧版接入兼容字段。已填写 API Key 时请留空。'
WHERE `template_id` IN (@doubao_bigtts, @doubao_seed_tts_2)
  AND `field_key` = 'access_token';

-- 更新 resource_id 字段的 help_text：服务 10007
UPDATE `ai_model_template_field`
SET `help_text` = '固定填写 volc.service_type.10029。该值对应火山控制台语音合成服务 10007。'
WHERE `template_id` = @doubao_bigtts
  AND `field_key` = 'resource_id';

-- 更新 resource_id 字段的 help_text：服务 10035
UPDATE `ai_model_template_field`
SET `help_text` = '固定填写 seed-tts-2.0。该值对应火山控制台语音合成服务 10035。'
WHERE `template_id` = @doubao_seed_tts_2
  AND `field_key` = 'resource_id';

-- 更新 speaker 字段的 help_text：服务 10007
UPDATE `ai_model_template_field`
SET `help_text` = '填写火山服务 10007 控制台音色列表中的 Voice_type。常用音色：湾湾小何、少年梓辛、爽快思思、温暖阿虎等。'
WHERE `template_id` = @doubao_bigtts
  AND `field_key` = 'speaker';

-- 更新 speaker 字段的 help_text：服务 10035
UPDATE `ai_model_template_field`
SET `help_text` = '填写火山服务 10035 控制台音色列表中的 Voice_type。注意：2.0 模型音色与大模型（10007）不同，需在 10035 控制台查看可用音色列表。'
WHERE `template_id` = @doubao_seed_tts_2
  AND `field_key` = 'speaker';

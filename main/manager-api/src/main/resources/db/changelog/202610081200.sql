SET @doubao_duplex_template = 'LLM_DOUBAO_DUPLEX';
SET @doubao_duplex_model_id = 'LLM_DoubaoDuplex';
SET @doubao_duplex_provider_id = 'SYSTEM_LLM_DOUBAO_DUPLEX';
SET @doubao_duplex_url = 'wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue';
SET @doubao_duplex_default_model = '1.2.6.1';

INSERT INTO `ai_model_provider` (
  `id`, `model_type`, `provider_code`, `name`, `fields`, `sort`,
  `creator`, `create_date`, `updater`, `update_date`
)
VALUES (
  @doubao_duplex_provider_id,
  'LLM',
  'doubao_duplex',
  '豆包3.0 Seeduplex 全双工',
  JSON_ARRAY(
    JSON_OBJECT('key', 'api_key', 'type', 'string', 'label', 'API Key'),
    JSON_OBJECT('key', 'base_url', 'type', 'string', 'label', 'Realtime WebSocket 地址', 'default', @doubao_duplex_url),
    JSON_OBJECT('key', 'model_name', 'type', 'string', 'label', '模型版本', 'default', @doubao_duplex_default_model),
    JSON_OBJECT('key', 'conversation_mode', 'type', 'string', 'label', '会话模式', 'default', 'realtime'),
    JSON_OBJECT('key', 'input_sample_rate', 'type', 'number', 'label', '输入采样率', 'default', 16000),
    JSON_OBJECT('key', 'output_sample_rate', 'type', 'number', 'label', '输出采样率', 'default', 24000),
    JSON_OBJECT('key', 'voice', 'type', 'string', 'label', '音色', 'default', 'zh_female_vv_jupiter_bigtts'),
    JSON_OBJECT('key', 'speech_rate', 'type', 'number', 'label', '语速', 'default', 0),
    JSON_OBJECT('key', 'loudness_rate', 'type', 'number', 'label', '音量', 'default', 0),
    JSON_OBJECT('key', 'connect_timeout_seconds', 'type', 'number', 'label', '连接超时(秒)', 'default', 10),
    JSON_OBJECT('key', 'response_timeout_seconds', 'type', 'number', 'label', '响应超时(秒)', 'default', 120),
    JSON_OBJECT('key', 'max_event_bytes', 'type', 'number', 'label', '最大事件字节数', 'default', 10485760)
  ),
  19,
  1,
  NOW(),
  1,
  NOW()
)
ON DUPLICATE KEY UPDATE
  `model_type` = VALUES(`model_type`),
  `provider_code` = VALUES(`provider_code`),
  `name` = VALUES(`name`),
  `fields` = VALUES(`fields`),
  `sort` = VALUES(`sort`),
  `updater` = VALUES(`updater`),
  `update_date` = VALUES(`update_date`);

INSERT INTO `ai_model_template` (
  `id`, `template_code`, `model_type`, `adapter_code`, `vendor_name`, `product_name`,
  `capability`, `access_mode`, `deployment`, `protocol`, `upstream_model_field`, `doc_link`,
  `enabled`, `sort`, `supported_status`, `selectable`, `hidden`, `deprecated`,
  `recommended_for`, `avoid_when`, `test_contract`, `source_checked_at`,
  `setup_hints_json`, `known_limitations_json`, `aliases_json`
)
VALUES (
  @doubao_duplex_template,
  @doubao_duplex_template,
  'LLM',
  'doubao_duplex',
  '火山引擎',
  '豆包3.0 Seeduplex 全双工',
  'llm',
  'realtime-websocket',
  'cloud',
  'Seeduplex JSON WebSocket',
  'model_name',
  'https://docs.volcengine.com/docs/DoubaoVoice/endtoend-realtime-voice-full-duplex-version?lang=zh',
  1,
  19,
  'SUPPORTED',
  1,
  0,
  0,
  '低延迟中文语音对话、上游统一 ASR/LLM/TTS 的端到端会话。',
  '需要图片、文字输入、文字评测、离线运行或独立替换ASR/LLM/TTS时。',
  '必须通过 xiaozhi-server 建立真实Seeduplex JSON WebSocket，发送内置语音样本，收到完整音频响应并记录首文本、首音频和总耗时；不得以握手冒充通过。',
  '2026-10-08',
  JSON_ARRAY(
    '选择该主模型后，豆包端到端会话直接接管设备音频输入、ASR、对话生成和语音输出。',
    'base_url 固定为火山引擎官方 openspeech.bytedance.com 地址，凭据只通过 WebSocket Header 发送。',
    'API Key来自新版豆包语音控制台，无需App ID；仅保存到密钥表。'
  ),
  JSON_ARRAY(
    '支持通过统一工具运行时回传已授权工具的执行结果。',
    '当前不支持图片输入，不会静默改走独立 VLLM；仅用于主对话，不作为 SLM 候选。',
    '配置校验或 WebSocket 握手成功不能标记测试通过。'
  ),
  JSON_ARRAY('DoubaoDuplex', 'Seeduplex', 'doubao_duplex', '豆包3.0', '豆包全双工')
)
ON DUPLICATE KEY UPDATE
  `model_type` = VALUES(`model_type`),
  `adapter_code` = VALUES(`adapter_code`),
  `vendor_name` = VALUES(`vendor_name`),
  `product_name` = VALUES(`product_name`),
  `capability` = VALUES(`capability`),
  `access_mode` = VALUES(`access_mode`),
  `deployment` = VALUES(`deployment`),
  `protocol` = VALUES(`protocol`),
  `upstream_model_field` = VALUES(`upstream_model_field`),
  `doc_link` = VALUES(`doc_link`),
  `enabled` = VALUES(`enabled`),
  `sort` = VALUES(`sort`),
  `supported_status` = VALUES(`supported_status`),
  `selectable` = VALUES(`selectable`),
  `hidden` = VALUES(`hidden`),
  `deprecated` = VALUES(`deprecated`),
  `recommended_for` = VALUES(`recommended_for`),
  `avoid_when` = VALUES(`avoid_when`),
  `test_contract` = VALUES(`test_contract`),
  `source_checked_at` = VALUES(`source_checked_at`),
  `setup_hints_json` = VALUES(`setup_hints_json`),
  `known_limitations_json` = VALUES(`known_limitations_json`),
  `aliases_json` = VALUES(`aliases_json`);

DELETE FROM `ai_model_template_field`
WHERE `template_id` = @doubao_duplex_template;

INSERT INTO `ai_model_template_field` (
  `template_id`, `field_key`, `field_label`, `field_group`, `value_type`, `input_type`,
  `runtime_key`, `advanced`, `visible_when`, `required`, `secret`, `default_value`,
  `options_json`, `help_text`, `sort`
)
VALUES
(@doubao_duplex_template, 'api_key', 'API Key', 'credentials', 'string', 'password', 'api_key', 0, NULL, 1, 1, NULL, NULL, '火山引擎豆包端到端服务 API Key，只保存到密钥表，不写入 URL、配置或日志。', 2),
(@doubao_duplex_template, 'base_url', 'Realtime WebSocket 地址', 'endpoint', 'string', 'text', 'base_url', 0, NULL, 1, 0, @doubao_duplex_url, NULL, '固定为火山引擎官方地址，服务端会拒绝其他主机、路径、查询参数和非标准端口。', 3),
(@doubao_duplex_template, 'model_name', '模型版本', 'upstream', 'string', 'select', 'model_name', 0, NULL, 1, 0, @doubao_duplex_default_model, JSON_ARRAY('1.2.6.1'), '豆包3.0全双工固定版本。', 4),
(@doubao_duplex_template, 'conversation_mode', '会话模式', 'advanced', 'string', 'select', 'conversation_mode', 0, NULL, 1, 0, 'realtime', JSON_ARRAY('realtime'), '固定为 realtime；选择该模型后端到端会话接管音频输入与输出。', 5),
(@doubao_duplex_template, 'input_sample_rate', '输入采样率', 'audio', 'number', 'number', 'input_sample_rate', 0, NULL, 1, 0, '16000', NULL, '发送给上游的 PCM16 单声道采样率，当前固定 16000 Hz。', 6),
(@doubao_duplex_template, 'output_sample_rate', '输出采样率', 'audio', 'number', 'number', 'output_sample_rate', 0, NULL, 1, 0, '24000', NULL, '上游返回 PCM16 单声道音频的采样率，当前固定 24000 Hz。', 7),
(@doubao_duplex_template, 'voice', '音色', 'audio', 'string', 'text', 'voice', 0, NULL, 0, 0, 'zh_female_vv_jupiter_bigtts', NULL, '填写当前豆包端到端服务账号已开通的音色 ID。', 8),
(@doubao_duplex_template, 'speech_rate', '语速', 'audio', 'number', 'number', 'speech_rate', 1, NULL, 0, 0, '0', NULL, '范围 -50 到 100，0 为默认语速。', 13),
(@doubao_duplex_template, 'loudness_rate', '音量', 'audio', 'number', 'number', 'loudness_rate', 1, NULL, 0, 0, '0', NULL, '范围 -50 到 100，0 为默认音量。', 14),
(@doubao_duplex_template, 'connect_timeout_seconds', '连接超时(秒)', 'advanced', 'number', 'number', 'connect_timeout_seconds', 1, NULL, 0, 0, '10', NULL, '建立火山引擎 Realtime WebSocket 的超时时间。', 16),
(@doubao_duplex_template, 'response_timeout_seconds', '响应超时(秒)', 'advanced', 'number', 'number', 'response_timeout_seconds', 1, NULL, 0, 0, '120', NULL, '等待一轮 Realtime 响应完成的最长时间。', 17),
(@doubao_duplex_template, 'max_event_bytes', '最大事件字节数', 'advanced', 'number', 'number', 'max_event_bytes', 1, NULL, 0, 0, '10485760', NULL, '单个上游 WebSocket 事件允许的最大字节数。', 18);

INSERT INTO `ai_model_config` (
  `id`, `model_type`, `model_code`, `model_name`, `adapter_code`, `template_code`,
  `vendor_name`, `product_name`, `protocol`, `upstream_model_id`, `endpoint_url`,
  `is_default`, `is_enabled`, `test_status`, `test_message`, `test_time`,
  `config_json`, `doc_link`, `remark`, `sort`, `creator`, `create_date`, `updater`, `update_date`
)
VALUES (
  @doubao_duplex_model_id,
  'LLM',
  'DoubaoDuplex',
  '豆包3.0 Seeduplex 全双工',
  'doubao_duplex',
  @doubao_duplex_template,
  '火山引擎',
  '豆包3.0 Seeduplex 全双工',
  'Seeduplex JSON WebSocket',
  @doubao_duplex_default_model,
  @doubao_duplex_url,
  0,
  0,
  0,
  NULL,
  NULL,
  NULL,
  'https://docs.volcengine.com/docs/DoubaoVoice/endtoend-realtime-voice-full-duplex-version?lang=zh',
  'API Key初始为空。填写并完成真实语音输入与音频输出测试后才能启用。',
  19,
  1,
  NOW(),
  1,
  NOW()
)
ON DUPLICATE KEY UPDATE
  `model_type` = VALUES(`model_type`),
  `model_code` = VALUES(`model_code`),
  `model_name` = VALUES(`model_name`),
  `adapter_code` = VALUES(`adapter_code`),
  `template_code` = VALUES(`template_code`),
  `vendor_name` = VALUES(`vendor_name`),
  `product_name` = VALUES(`product_name`),
  `protocol` = VALUES(`protocol`),
  `upstream_model_id` = COALESCE(NULLIF(`upstream_model_id`, ''), VALUES(`upstream_model_id`)),
  `endpoint_url` = COALESCE(NULLIF(`endpoint_url`, ''), VALUES(`endpoint_url`)),
  `is_enabled` = 0,
  `test_status` = 0,
  `test_message` = NULL,
  `test_time` = NULL,
  `doc_link` = VALUES(`doc_link`),
  `remark` = VALUES(`remark`),
  `sort` = VALUES(`sort`),
  `updater` = VALUES(`updater`),
  `update_date` = VALUES(`update_date`);

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
VALUES
(@doubao_duplex_model_id, 'voice', 'zh_female_vv_jupiter_bigtts', 'string', 'audio'),
(@doubao_duplex_model_id, 'conversation_mode', 'realtime', 'string', 'provider'),
(@doubao_duplex_model_id, 'input_sample_rate', '16000', 'number', 'audio'),
(@doubao_duplex_model_id, 'output_sample_rate', '24000', 'number', 'audio'),
(@doubao_duplex_model_id, 'speech_rate', '0', 'number', 'audio'),
(@doubao_duplex_model_id, 'loudness_rate', '0', 'number', 'audio'),
(@doubao_duplex_model_id, 'connect_timeout_seconds', '10', 'number', 'advanced'),
(@doubao_duplex_model_id, 'response_timeout_seconds', '120', 'number', 'advanced'),
(@doubao_duplex_model_id, 'max_event_bytes', '10485760', 'number', 'advanced')
ON DUPLICATE KEY UPDATE
  `param_value` = CASE
    WHEN `param_key` = 'conversation_mode' THEN 'realtime'
    WHEN NULLIF(TRIM(`param_value`), '') IS NULL THEN VALUES(`param_value`)
    ELSE `param_value`
  END,
  `value_type` = VALUES(`value_type`),
  `param_group` = VALUES(`param_group`);

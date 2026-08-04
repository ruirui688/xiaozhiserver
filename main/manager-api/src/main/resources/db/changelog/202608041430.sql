SET @doubao_realtime_template = 'LLM_DOUBAO_REALTIME';
SET @doubao_realtime_model_id = 'LLM_DoubaoRealtime';
SET @doubao_realtime_provider_id = 'SYSTEM_LLM_DOUBAO_REALTIME';
SET @doubao_realtime_url = 'wss://openspeech.bytedance.com/api/v3/realtime/dialogue';
SET @doubao_realtime_default_model = '1.2.1.1';

INSERT INTO `ai_model_provider` (
  `id`, `model_type`, `provider_code`, `name`, `fields`, `sort`,
  `creator`, `create_date`, `updater`, `update_date`
)
VALUES (
  @doubao_realtime_provider_id,
  'LLM',
  'doubao_realtime',
  '豆包端到端实时语音',
  JSON_ARRAY(
    JSON_OBJECT('key', 'app_id', 'type', 'string', 'label', 'App ID'),
    JSON_OBJECT('key', 'api_key', 'type', 'string', 'label', 'Access Key'),
    JSON_OBJECT('key', 'base_url', 'type', 'string', 'label', 'Realtime WebSocket 地址', 'default', @doubao_realtime_url),
    JSON_OBJECT('key', 'model_name', 'type', 'string', 'label', '模型版本', 'default', @doubao_realtime_default_model),
    JSON_OBJECT('key', 'conversation_mode', 'type', 'string', 'label', '会话模式', 'default', 'realtime'),
    JSON_OBJECT('key', 'input_sample_rate', 'type', 'number', 'label', '输入采样率', 'default', 16000),
    JSON_OBJECT('key', 'output_sample_rate', 'type', 'number', 'label', '输出采样率', 'default', 24000),
    JSON_OBJECT('key', 'voice', 'type', 'string', 'label', '音色', 'default', 'zh_female_vv_jupiter_bigtts'),
    JSON_OBJECT('key', 'bot_name', 'type', 'string', 'label', '助手名称', 'default', '小智'),
    JSON_OBJECT('key', 'speaking_style', 'type', 'string', 'label', '说话风格'),
    JSON_OBJECT('key', 'input_mode', 'type', 'string', 'label', '输入模式', 'default', 'audio'),
    JSON_OBJECT('key', 'end_smooth_window_ms', 'type', 'number', 'label', '断句平滑窗口(ms)', 'default', 1500),
    JSON_OBJECT('key', 'speech_rate', 'type', 'number', 'label', '语速', 'default', 0),
    JSON_OBJECT('key', 'loudness_rate', 'type', 'number', 'label', '音量', 'default', 0),
    JSON_OBJECT('key', 'strict_audit', 'type', 'boolean', 'label', '严格内容审核', 'default', TRUE),
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
  @doubao_realtime_template,
  @doubao_realtime_template,
  'LLM',
  'doubao_realtime',
  '火山引擎',
  '豆包端到端实时语音',
  'llm',
  'realtime-websocket',
  'cloud',
  '火山引擎二进制 Realtime WebSocket',
  'model_name',
  'https://www.volcengine.com/docs/6561/1594356',
  1,
  19,
  'SUPPORTED',
  1,
  0,
  0,
  '低延迟中文语音对话、上游统一 ASR/LLM/TTS 的端到端会话。',
  '需要图片输入、本地 Function Calling 工具回注、离线运行，或需要独立替换 ASR/LLM/TTS 时。',
  '必须通过 xiaozhi-server 建立真实火山引擎二进制 Realtime WebSocket，完成至少一轮响应并记录首文本、首音频和总耗时；不得以握手冒充通过。',
  '2026-08-04',
  JSON_ARRAY(
    '选择该主模型后，豆包端到端会话直接接管设备音频输入、ASR、对话生成和语音输出。',
    'base_url 固定为火山引擎官方 openspeech.bytedance.com 地址，凭据只通过 WebSocket Header 发送。',
    'API Key 只保存到密钥表；App ID 是应用标识，不应填写 Access Key。'
  ),
  JSON_ARRAY(
    '当前官方协议未提供项目本地 Function Calling 结果回注能力，因此不会向上游暴露本地工具。',
    '当前不支持图片输入，不会静默改走独立 VLLM；仅用于主对话，不作为 SLM 候选。',
    '配置校验或 WebSocket 握手成功不能标记测试通过。'
  ),
  JSON_ARRAY('DoubaoRealtime', 'doubao realtime', 'doubao_realtime', '豆包端到端', '豆包实时语音')
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
WHERE `template_id` = @doubao_realtime_template;

INSERT INTO `ai_model_template_field` (
  `template_id`, `field_key`, `field_label`, `field_group`, `value_type`, `input_type`,
  `runtime_key`, `advanced`, `visible_when`, `required`, `secret`, `default_value`,
  `options_json`, `help_text`, `sort`
)
VALUES
(@doubao_realtime_template, 'app_id', 'App ID', 'credentials', 'string', 'text', 'app_id', 0, NULL, 1, 0, NULL, NULL, '火山引擎控制台应用 ID，本身不是密钥。', 1),
(@doubao_realtime_template, 'api_key', 'Access Key', 'credentials', 'string', 'password', 'api_key', 0, NULL, 1, 1, NULL, NULL, '火山引擎豆包端到端服务 Access Key，只保存到密钥表，不写入 URL、配置或日志。', 2),
(@doubao_realtime_template, 'base_url', 'Realtime WebSocket 地址', 'endpoint', 'string', 'text', 'base_url', 0, NULL, 1, 0, @doubao_realtime_url, NULL, '固定为火山引擎官方地址，服务端会拒绝其他主机、路径、查询参数和非标准端口。', 3),
(@doubao_realtime_template, 'model_name', '模型版本', 'upstream', 'string', 'select', 'model_name', 0, NULL, 1, 0, @doubao_realtime_default_model, JSON_ARRAY('1.2.1.1', '2.2.0.0'), '1.2.1.1 为 O2.0，2.2.0.0 为 SC2.0。', 4),
(@doubao_realtime_template, 'conversation_mode', '会话模式', 'advanced', 'string', 'select', 'conversation_mode', 0, NULL, 1, 0, 'realtime', JSON_ARRAY('realtime'), '固定为 realtime；选择该模型后端到端会话接管音频输入与输出。', 5),
(@doubao_realtime_template, 'input_sample_rate', '输入采样率', 'audio', 'number', 'number', 'input_sample_rate', 0, NULL, 1, 0, '16000', NULL, '发送给上游的 PCM16 单声道采样率，当前固定 16000 Hz。', 6),
(@doubao_realtime_template, 'output_sample_rate', '输出采样率', 'audio', 'number', 'number', 'output_sample_rate', 0, NULL, 1, 0, '24000', NULL, '上游返回 PCM16 单声道音频的采样率，当前固定 24000 Hz。', 7),
(@doubao_realtime_template, 'voice', '音色', 'audio', 'string', 'text', 'voice', 0, NULL, 0, 0, 'zh_female_vv_jupiter_bigtts', NULL, '填写当前豆包端到端服务账号已开通的音色 ID。', 8),
(@doubao_realtime_template, 'bot_name', '助手名称', 'dialog', 'string', 'text', 'bot_name', 0, NULL, 0, 0, '小智', NULL, '上游会话使用的助手名称，最长 20 个字符。', 9),
(@doubao_realtime_template, 'speaking_style', '说话风格', 'dialog', 'string', 'textarea', 'speaking_style', 0, NULL, 0, 0, '回复简洁自然，适合实时语音对话。', NULL, 'O2.0 使用；SC2.0 使用完整角色设定。', 10),
(@doubao_realtime_template, 'input_mode', '输入模式', 'audio', 'string', 'select', 'input_mode', 0, NULL, 0, 0, 'audio', JSON_ARRAY('audio', 'push_to_talk'), 'audio 自动断句；push_to_talk 在设备 listen.stop 时结束识别。', 11),
(@doubao_realtime_template, 'end_smooth_window_ms', '断句平滑窗口(ms)', 'audio', 'number', 'number', 'end_smooth_window_ms', 1, NULL, 0, 0, '1500', NULL, 'ASR 断句平滑窗口，默认 1500 ms。', 12),
(@doubao_realtime_template, 'speech_rate', '语速', 'audio', 'number', 'number', 'speech_rate', 1, NULL, 0, 0, '0', NULL, '范围 -50 到 100，0 为默认语速。', 13),
(@doubao_realtime_template, 'loudness_rate', '音量', 'audio', 'number', 'number', 'loudness_rate', 1, NULL, 0, 0, '0', NULL, '范围 -50 到 100，0 为默认音量。', 14),
(@doubao_realtime_template, 'strict_audit', '严格内容审核', 'advanced', 'boolean', 'switch', 'strict_audit', 1, NULL, 0, 0, 'true', NULL, '生产默认开启火山引擎严格内容审核。', 15),
(@doubao_realtime_template, 'connect_timeout_seconds', '连接超时(秒)', 'advanced', 'number', 'number', 'connect_timeout_seconds', 1, NULL, 0, 0, '10', NULL, '建立火山引擎 Realtime WebSocket 的超时时间。', 16),
(@doubao_realtime_template, 'response_timeout_seconds', '响应超时(秒)', 'advanced', 'number', 'number', 'response_timeout_seconds', 1, NULL, 0, 0, '120', NULL, '等待一轮 Realtime 响应完成的最长时间。', 17),
(@doubao_realtime_template, 'max_event_bytes', '最大事件字节数', 'advanced', 'number', 'number', 'max_event_bytes', 1, NULL, 0, 0, '10485760', NULL, '单个上游 WebSocket 事件允许的最大字节数。', 18);

INSERT INTO `ai_model_config` (
  `id`, `model_type`, `model_code`, `model_name`, `adapter_code`, `template_code`,
  `vendor_name`, `product_name`, `protocol`, `upstream_model_id`, `endpoint_url`,
  `is_default`, `is_enabled`, `test_status`, `test_message`, `test_time`,
  `config_json`, `doc_link`, `remark`, `sort`, `creator`, `create_date`, `updater`, `update_date`
)
VALUES (
  @doubao_realtime_model_id,
  'LLM',
  'DoubaoRealtime',
  '豆包端到端实时语音 O2.0',
  'doubao_realtime',
  @doubao_realtime_template,
  '火山引擎',
  '豆包端到端实时语音',
  '火山引擎二进制 Realtime WebSocket',
  @doubao_realtime_default_model,
  @doubao_realtime_url,
  0,
  0,
  0,
  NULL,
  NULL,
  NULL,
  'https://www.volcengine.com/docs/6561/1594356',
  '端到端实时语音主模型。App ID 和 Access Key 初始为空，填写并完成真实 Realtime 测试后才能启用。',
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

INSERT INTO `ai_model_config_secret` (`model_id`, `secret_key`, `secret_value`, `masked_value`, `value_type`)
SELECT
  @doubao_realtime_model_id,
  'api_key',
  JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.api_key')),
  CASE
    WHEN CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.api_key'))) <= 8 THEN '****'
    ELSE CONCAT(
      LEFT(JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.api_key')), 4),
      '****',
      RIGHT(JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.api_key')), 4)
    )
  END,
  'secret'
FROM `ai_model_config`
WHERE `id` = @doubao_realtime_model_id
  AND `config_json` IS NOT NULL
  AND JSON_VALID(`config_json`)
  AND NULLIF(TRIM(JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.api_key'))), '') IS NOT NULL
  AND JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.api_key')) NOT LIKE '你的%'
  AND JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.api_key')) NOT LIKE '%placeholder%'
ON DUPLICATE KEY UPDATE
  `masked_value` = CASE
    WHEN NULLIF(TRIM(`secret_value`), '') IS NULL THEN VALUES(`masked_value`)
    ELSE `masked_value`
  END,
  `secret_value` = CASE
    WHEN NULLIF(TRIM(`secret_value`), '') IS NULL THEN VALUES(`secret_value`)
    ELSE `secret_value`
  END,
  `value_type` = 'secret';

UPDATE `ai_model_config`
SET `config_json` = NULL
WHERE `id` = @doubao_realtime_model_id;

DELETE FROM `ai_model_config_secret`
WHERE `model_id` = @doubao_realtime_model_id
  AND `secret_key` <> 'api_key';

DELETE FROM `ai_model_config_param`
WHERE `model_id` = @doubao_realtime_model_id
  AND `param_key` NOT IN (
    'app_id', 'voice', 'conversation_mode', 'input_sample_rate', 'output_sample_rate',
    'bot_name', 'speaking_style', 'input_mode', 'end_smooth_window_ms',
    'speech_rate', 'loudness_rate', 'strict_audit', 'connect_timeout_seconds',
    'response_timeout_seconds', 'max_event_bytes'
  );

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
VALUES
(@doubao_realtime_model_id, 'app_id', '', 'string', 'credentials'),
(@doubao_realtime_model_id, 'voice', 'zh_female_vv_jupiter_bigtts', 'string', 'audio'),
(@doubao_realtime_model_id, 'conversation_mode', 'realtime', 'string', 'provider'),
(@doubao_realtime_model_id, 'input_sample_rate', '16000', 'number', 'audio'),
(@doubao_realtime_model_id, 'output_sample_rate', '24000', 'number', 'audio'),
(@doubao_realtime_model_id, 'bot_name', '小智', 'string', 'dialog'),
(@doubao_realtime_model_id, 'speaking_style', '回复简洁自然，适合实时语音对话。', 'string', 'dialog'),
(@doubao_realtime_model_id, 'input_mode', 'audio', 'string', 'audio'),
(@doubao_realtime_model_id, 'end_smooth_window_ms', '1500', 'number', 'audio'),
(@doubao_realtime_model_id, 'speech_rate', '0', 'number', 'audio'),
(@doubao_realtime_model_id, 'loudness_rate', '0', 'number', 'audio'),
(@doubao_realtime_model_id, 'strict_audit', 'true', 'boolean', 'advanced'),
(@doubao_realtime_model_id, 'connect_timeout_seconds', '10', 'number', 'advanced'),
(@doubao_realtime_model_id, 'response_timeout_seconds', '120', 'number', 'advanced'),
(@doubao_realtime_model_id, 'max_event_bytes', '10485760', 'number', 'advanced')
ON DUPLICATE KEY UPDATE
  `param_value` = CASE
    WHEN `param_key` = 'conversation_mode' THEN 'realtime'
    WHEN NULLIF(TRIM(`param_value`), '') IS NULL THEN VALUES(`param_value`)
    ELSE `param_value`
  END,
  `value_type` = VALUES(`value_type`),
  `param_group` = VALUES(`param_group`);

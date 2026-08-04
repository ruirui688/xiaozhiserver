SET @qwen_omni_template = 'LLM_ALI_QWEN_OMNI_REALTIME';
SET @qwen_omni_model_id = 'LLM_QwenOmniRealtime';
SET @qwen_omni_provider_id = 'SYSTEM_LLM_QWEN_OMNI_REALTIME';
SET @qwen_omni_base_url = NULL;
SET @qwen_omni_default_model = 'qwen3.5-omni-flash-realtime';

INSERT INTO `ai_model_provider` (
  `id`, `model_type`, `provider_code`, `name`, `fields`, `sort`,
  `creator`, `create_date`, `updater`, `update_date`
)
VALUES (
  @qwen_omni_provider_id,
  'LLM',
  'qwen_omni_realtime',
  'Qwen Omni Realtime',
  JSON_ARRAY(
    JSON_OBJECT('key', 'api_key', 'type', 'string', 'label', 'API Key'),
    JSON_OBJECT('key', 'base_url', 'type', 'string', 'label', 'Realtime WebSocket 地址'),
    JSON_OBJECT('key', 'model_name', 'type', 'string', 'label', '模型', 'default', @qwen_omni_default_model),
    JSON_OBJECT('key', 'voice', 'type', 'string', 'label', '音色', 'default', 'Ethan'),
    JSON_OBJECT('key', 'conversation_mode', 'type', 'string', 'label', '会话模式', 'default', 'realtime'),
    JSON_OBJECT('key', 'input_sample_rate', 'type', 'number', 'label', '输入采样率', 'default', 16000),
    JSON_OBJECT('key', 'output_sample_rate', 'type', 'number', 'label', '输出采样率', 'default', 24000),
    JSON_OBJECT('key', 'turn_detection_type', 'type', 'string', 'label', '轮次检测', 'default', 'server_vad'),
    JSON_OBJECT('key', 'turn_detection_threshold', 'type', 'number', 'label', 'VAD 阈值', 'default', 0.2),
    JSON_OBJECT('key', 'prefix_padding_ms', 'type', 'number', 'label', '语音前缀保留(ms)', 'default', 300),
    JSON_OBJECT('key', 'silence_duration_ms', 'type', 'number', 'label', '断句静音时长(ms)', 'default', 800),
    JSON_OBJECT('key', 'enable_input_audio_transcription', 'type', 'boolean', 'label', '输入音频转写', 'default', TRUE),
    JSON_OBJECT('key', 'input_audio_transcription_model', 'type', 'string', 'label', '输入转写模型', 'default', ''),
    JSON_OBJECT('key', 'connect_timeout_seconds', 'type', 'number', 'label', '连接超时(秒)', 'default', 10),
    JSON_OBJECT('key', 'response_timeout_seconds', 'type', 'number', 'label', '响应超时(秒)', 'default', 120),
    JSON_OBJECT('key', 'max_event_bytes', 'type', 'number', 'label', '最大事件字节数', 'default', 10485760)
  ),
  18,
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
  @qwen_omni_template,
  @qwen_omni_template,
  'LLM',
  'qwen_omni_realtime',
  '阿里云百炼',
  'Qwen Omni Realtime',
  'llm',
  'realtime-websocket',
  'cloud',
  'DashScope Realtime WebSocket',
  'model_name',
  'https://help.aliyun.com/zh/model-studio',
  1,
  18,
  'SUPPORTED',
  1,
  0,
  0,
  '实时语音对话、图片帧理解、音频/文本多模态输出和 Function Calling。',
  '需要独立替换 ASR、LLM 或 TTS、需要离线运行，或上游网络无法稳定维持 WebSocket 长连接时。',
  '必须通过 xiaozhi-server 建立真实 DashScope Realtime WebSocket，完成至少一轮响应并记录首文本、首音频和总耗时；不得以配置校验或握手冒充通过。',
  '2026-08-03',
  JSON_ARRAY(
    '选择该主模型后，会切换为端到端 Realtime 会话并绕过传统 ASR、VAD、LLM、TTS 拼接链路。',
    'base_url 必须填写阿里云百炼官方 DashScope 地址，或北京/新加坡地域的业务空间 Realtime 地址；禁止把 API Key 发送到其他主机。',
    '切回普通 LLM 后，智能体继续使用原有 ASR、VAD 和 TTS 配置。'
  ),
  JSON_ARRAY(
    '仅用于主对话实时会话，不作为 SLM 轻量文本模型候选。',
    '必须由 xiaozhi-server Realtime provider 执行真实 WebSocket 推理，配置校验或握手成功不能标记测试通过。'
  ),
  JSON_ARRAY(
    'QwenOmniRealtime', 'qwen omni realtime', 'qwen3.5-omni-plus-realtime',
    'qwen3.5-omni-flash-realtime', 'qwen3-omni-flash-realtime'
  )
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
WHERE `template_id` = @qwen_omni_template;

INSERT INTO `ai_model_template_field` (
  `template_id`, `field_key`, `field_label`, `field_group`, `value_type`, `input_type`,
  `runtime_key`, `advanced`, `visible_when`, `required`, `secret`, `default_value`,
  `options_json`, `help_text`, `sort`
)
VALUES
(@qwen_omni_template, 'api_key', 'API Key', 'credentials', 'string', 'password', 'api_key', 0, NULL, 1, 1, NULL, NULL, '阿里云百炼 API Key，只保存到密钥表，不写入配置、日志或 URL。', 1),
(@qwen_omni_template, 'base_url', 'Realtime WebSocket 地址', 'endpoint', 'string', 'text', 'base_url', 0, NULL, 1, 0, NULL, NULL, '填写控制台业务空间对应地域的官方地址，例如 wss://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime；也兼容旧版官方 DashScope 地址。', 2),
(@qwen_omni_template, 'model_name', '模型', 'upstream', 'string', 'select', 'model_name', 0, NULL, 1, 0, @qwen_omni_default_model, JSON_ARRAY('qwen3.5-omni-plus-realtime', 'qwen3.5-omni-flash-realtime', 'qwen3-omni-flash-realtime'), '上游实际调用的 Qwen Omni Realtime 模型 ID。', 3),
(@qwen_omni_template, 'conversation_mode', '会话模式', 'advanced', 'string', 'select', 'conversation_mode', 0, NULL, 1, 0, 'realtime', JSON_ARRAY('realtime'), '固定为 realtime；选择该模型后端到端实时会话接管音频输入与输出。', 4),
(@qwen_omni_template, 'input_sample_rate', '输入采样率', 'audio', 'number', 'number', 'input_sample_rate', 0, NULL, 1, 0, '16000', NULL, '发送给上游的 PCM16 单声道采样率，当前固定 16000 Hz。', 5),
(@qwen_omni_template, 'output_sample_rate', '输出采样率', 'audio', 'number', 'number', 'output_sample_rate', 0, NULL, 1, 0, '24000', NULL, '上游返回 PCM16 单声道音频的采样率，当前固定 24000 Hz。', 6),
(@qwen_omni_template, 'voice', '音色', 'audio', 'string', 'text', 'voice', 0, NULL, 0, 0, 'Ethan', NULL, '上游 Realtime 音色，默认 Ethan。', 7),
(@qwen_omni_template, 'turn_detection_type', '轮次检测', 'audio', 'string', 'select', 'turn_detection_type', 0, NULL, 0, 0, 'server_vad', JSON_ARRAY('server_vad', 'semantic_vad', 'manual'), 'server_vad/semantic_vad 由上游自动断句（Qwen 3.5 推荐 semantic_vad）；manual 在设备 listen.stop 时提交音频并创建响应，适合按键拾音。', 8),
(@qwen_omni_template, 'turn_detection_threshold', 'VAD 阈值', 'audio', 'number', 'number', 'turn_detection_threshold', 1, NULL, 0, 0, '0.2', NULL, '上游 server_vad 阈值；噪声环境可适当提高。', 9),
(@qwen_omni_template, 'prefix_padding_ms', '语音前缀保留(ms)', 'audio', 'number', 'number', 'prefix_padding_ms', 1, NULL, 0, 0, '300', NULL, 'VAD 检测到语音前额外保留的音频长度。', 10),
(@qwen_omni_template, 'silence_duration_ms', '断句静音时长(ms)', 'audio', 'number', 'number', 'silence_duration_ms', 1, NULL, 0, 0, '800', NULL, 'server_vad 判定用户说完所需的连续静音时长。', 11),
(@qwen_omni_template, 'enable_input_audio_transcription', '输入音频转写', 'audio', 'boolean', 'switch', 'enable_input_audio_transcription', 1, NULL, 0, 0, 'true', NULL, '接收输入音频转写，用于字幕、对话记录和评测。', 12),
(@qwen_omni_template, 'input_audio_transcription_model', '输入转写模型', 'audio', 'string', 'text', 'input_audio_transcription_model', 1, NULL, 0, 0, '', NULL, '可选；留空时使用上游服务默认的输入转写能力。', 13),
(@qwen_omni_template, 'connect_timeout_seconds', '连接超时(秒)', 'advanced', 'number', 'number', 'connect_timeout_seconds', 1, NULL, 0, 0, '10', NULL, '建立 DashScope Realtime WebSocket 的超时时间。', 14),
(@qwen_omni_template, 'response_timeout_seconds', '响应超时(秒)', 'advanced', 'number', 'number', 'response_timeout_seconds', 1, NULL, 0, 0, '120', NULL, '等待一轮 Realtime 响应完成的最长时间。', 15),
(@qwen_omni_template, 'max_event_bytes', '最大事件字节数', 'advanced', 'number', 'number', 'max_event_bytes', 1, NULL, 0, 0, '10485760', NULL, '单个上游 WebSocket 事件允许的最大字节数，用于限制异常大消息。', 16);

INSERT INTO `ai_model_config` (
  `id`, `model_type`, `model_code`, `model_name`, `adapter_code`, `template_code`,
  `vendor_name`, `product_name`, `protocol`, `upstream_model_id`, `endpoint_url`,
  `is_default`, `is_enabled`, `test_status`, `test_message`, `test_time`,
  `config_json`, `doc_link`, `remark`, `sort`, `creator`, `create_date`, `updater`, `update_date`
)
VALUES (
  @qwen_omni_model_id,
  'LLM',
  'QwenOmniRealtime',
  'Qwen3.5 Omni Flash Realtime',
  'qwen_omni_realtime',
  @qwen_omni_template,
  '阿里云百炼',
  'Qwen Omni Realtime',
  'DashScope Realtime WebSocket',
  @qwen_omni_default_model,
  @qwen_omni_base_url,
  0,
  0,
  0,
  NULL,
  NULL,
  NULL,
  'https://help.aliyun.com/zh/model-studio',
  '端到端实时多模态主模型。选择后绕过传统 ASR/VAD/TTS 拼接链路；API Key 初始为空，填写并完成真实 Realtime 测试后才能启用。',
  18,
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
  @qwen_omni_model_id,
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
WHERE `id` = @qwen_omni_model_id
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
WHERE `id` = @qwen_omni_model_id;

DELETE FROM `ai_model_config_secret`
WHERE `model_id` = @qwen_omni_model_id
  AND `secret_key` <> 'api_key';

DELETE FROM `ai_model_config_param`
WHERE `model_id` = @qwen_omni_model_id
  AND `param_key` NOT IN (
    'voice', 'conversation_mode', 'input_sample_rate', 'output_sample_rate',
    'turn_detection_type', 'turn_detection_threshold', 'prefix_padding_ms',
    'silence_duration_ms', 'enable_input_audio_transcription',
    'input_audio_transcription_model', 'connect_timeout_seconds',
    'response_timeout_seconds', 'max_event_bytes'
  );

INSERT INTO `ai_model_config_param` (`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
VALUES
(@qwen_omni_model_id, 'voice', 'Ethan', 'string', 'audio'),
(@qwen_omni_model_id, 'conversation_mode', 'realtime', 'string', 'provider'),
(@qwen_omni_model_id, 'input_sample_rate', '16000', 'number', 'audio'),
(@qwen_omni_model_id, 'output_sample_rate', '24000', 'number', 'audio'),
(@qwen_omni_model_id, 'turn_detection_type', 'server_vad', 'string', 'audio'),
(@qwen_omni_model_id, 'turn_detection_threshold', '0.2', 'number', 'audio'),
(@qwen_omni_model_id, 'prefix_padding_ms', '300', 'number', 'audio'),
(@qwen_omni_model_id, 'silence_duration_ms', '800', 'number', 'audio'),
(@qwen_omni_model_id, 'enable_input_audio_transcription', 'true', 'boolean', 'audio'),
(@qwen_omni_model_id, 'input_audio_transcription_model', '', 'string', 'audio'),
(@qwen_omni_model_id, 'connect_timeout_seconds', '10', 'number', 'advanced'),
(@qwen_omni_model_id, 'response_timeout_seconds', '120', 'number', 'advanced'),
(@qwen_omni_model_id, 'max_event_bytes', '10485760', 'number', 'advanced')
ON DUPLICATE KEY UPDATE
  `param_value` = CASE
    WHEN `param_key` = 'conversation_mode' THEN 'realtime'
    WHEN NULLIF(TRIM(`param_value`), '') IS NULL THEN VALUES(`param_value`)
    ELSE `param_value`
  END,
  `value_type` = VALUES(`value_type`),
  `param_group` = VALUES(`param_group`);

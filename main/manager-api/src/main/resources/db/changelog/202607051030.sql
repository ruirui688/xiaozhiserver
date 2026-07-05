-- 收敛火山引擎 ASR：只保留豆包录音识别模型2.0和豆包流式语音识别模型2.0。

UPDATE `ai_agent`
SET `asr_model_id` = 'ASR_DoubaoStreamASRV2'
WHERE `asr_model_id` IN ('ASR_DoubaoASR', 'ASR_DoubaoStreamASR');

UPDATE `ai_agent_template`
SET `asr_model_id` = 'ASR_DoubaoStreamASRV2'
WHERE `asr_model_id` IN ('ASR_DoubaoASR', 'ASR_DoubaoStreamASR');

DELETE FROM `ai_model_config_param`
WHERE `model_id` IN ('ASR_DoubaoASR', 'ASR_DoubaoStreamASR');

DELETE FROM `ai_model_config_secret`
WHERE `model_id` IN ('ASR_DoubaoASR', 'ASR_DoubaoStreamASR');

DELETE FROM `ai_model_config`
WHERE `id` IN ('ASR_DoubaoASR', 'ASR_DoubaoStreamASR');

DELETE FROM `ai_model_provider`
WHERE `id` IN ('SYSTEM_ASR_DoubaoASR', 'SYSTEM_ASR_DoubaoStreamASR');

INSERT INTO `ai_model_provider`
(`id`, `model_type`, `provider_code`, `name`, `fields`, `sort`, `creator`, `create_date`, `updater`, `update_date`)
VALUES
('SYSTEM_ASR_DoubaoRecording2', 'ASR', 'doubao_recording', '豆包录音识别模型2.0',
 JSON_ARRAY(
   JSON_OBJECT('key', 'api_key', 'label', 'API Key', 'type', 'password'),
   JSON_OBJECT('key', 'context_text', 'label', '上下文文本', 'type', 'string')
 ), 3, 1, NOW(), 1, NOW()),
('SYSTEM_ASR_DoubaoStream2', 'ASR', 'doubao_stream', '豆包流式语音识别模型2.0',
 JSON_ARRAY(
   JSON_OBJECT('key', 'api_key', 'label', 'API Key', 'type', 'password'),
   JSON_OBJECT('key', 'context_text', 'label', '上下文文本', 'type', 'string')
 ), 4, 1, NOW(), 1, NOW())
ON DUPLICATE KEY UPDATE
  `provider_code` = VALUES(`provider_code`),
  `name` = VALUES(`name`),
  `fields` = VALUES(`fields`),
  `sort` = VALUES(`sort`),
  `updater` = VALUES(`updater`),
  `update_date` = VALUES(`update_date`);

DELETE FROM `ai_model_template_field`
WHERE `template_id` IN (
  'ASR_DOUBAO_LEGACY_HTTP',
  'ASR_DOUBAO_BIGASR_STREAM',
  'ASR_DOUBAO_RECORDING_ASR_2',
  'ASR_DOUBAO_SEED_ASR_2_STREAM'
);

DELETE FROM `ai_model_template`
WHERE `template_code` IN ('ASR_DOUBAO_LEGACY_HTTP', 'ASR_DOUBAO_BIGASR_STREAM');

INSERT INTO `ai_model_template`
(`id`, `template_code`, `model_type`, `adapter_code`, `vendor_name`, `product_name`, `capability`,
 `access_mode`, `deployment`, `protocol`, `upstream_model_field`, `doc_link`, `enabled`, `sort`,
 `supported_status`, `selectable`, `hidden`, `deprecated`, `recommended_for`, `avoid_when`,
 `test_contract`, `source_checked_at`, `setup_hints_json`, `known_limitations_json`, `aliases_json`)
VALUES
('ASR_DOUBAO_RECORDING_ASR_2', 'ASR_DOUBAO_RECORDING_ASR_2', 'ASR', 'doubao_recording',
 '火山引擎', '豆包录音识别模型2.0', 'asr', 'http', 'cloud', 'HTTP',
 'resource_id', 'https://www.volcengine.com/docs/6561/1354868', 1, 3,
 'SUPPORTED', 1, 0, 0,
 '录音文件识别、非实时 ASR 测试。',
 '实时设备对话建议选择豆包流式语音识别模型2.0。',
 '必须发送真实音频并返回非空识别文本。',
 '2026-07-05',
 JSON_ARRAY('只需填写火山控制台 API Key。', 'Resource ID 固定为 volc.seedasr.auc，用户无需填写。'),
 JSON_ARRAY('录音识别需要提交音频后查询结果，不适合作为低延迟实时对话首选。'),
 JSON_ARRAY('DoubaoRecordingASR2', 'volc.seedasr.auc')),
('ASR_DOUBAO_SEED_ASR_2_STREAM', 'ASR_DOUBAO_SEED_ASR_2_STREAM', 'ASR', 'doubao_stream',
 '火山引擎', '豆包流式语音识别模型2.0', 'asr', 'websocket', 'cloud', 'WebSocket',
 'resource_id', 'https://www.volcengine.com/docs/6561/1354869', 1, 4,
 'SUPPORTED', 1, 0, 0,
 '实时设备对话、短句语音识别。',
 NULL,
 '必须发送真实音频并返回非空识别文本。',
 '2026-07-05',
 JSON_ARRAY('只需填写火山控制台 API Key。', 'Resource ID 固定为 volc.seedasr.sauc.duration，用户无需填写。'),
 JSON_ARRAY(),
 JSON_ARRAY('DoubaoStreamASRV2', 'seedasr', 'volc.seedasr'))
ON DUPLICATE KEY UPDATE
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

INSERT INTO `ai_model_template_field`
(`template_id`, `field_key`, `field_label`, `field_group`, `value_type`, `input_type`, `runtime_key`,
 `advanced`, `visible_when`, `required`, `secret`, `default_value`, `options_json`, `help_text`, `sort`)
VALUES
('ASR_DOUBAO_RECORDING_ASR_2', 'api_key', 'API Key', 'credentials', 'string', 'password', 'api_key',
 0, NULL, 1, 1, NULL, NULL, '火山控制台 API Key，作为 X-Api-Key 请求头发送。', 1),
('ASR_DOUBAO_RECORDING_ASR_2', 'resource_id', 'Resource ID', 'upstream', 'string', 'text', 'resource_id',
 0, NULL, 0, 0, 'volc.seedasr.auc', NULL, '豆包录音识别模型2.0固定资源 ID，默认使用该值。', 2),
('ASR_DOUBAO_RECORDING_ASR_2', 'context_text', '上下文文本', 'advanced', 'string', 'textarea', 'context_text',
 1, NULL, 0, 0, NULL, NULL, '可选上下文文本，用于提升专有名词和业务场景识别准确率；为空时不发送。', 3),
('ASR_DOUBAO_SEED_ASR_2_STREAM', 'api_key', 'API Key', 'credentials', 'string', 'password', 'api_key',
 0, NULL, 1, 1, NULL, NULL, '火山控制台 API Key，作为 X-Api-Key 请求头发送。', 1),
('ASR_DOUBAO_SEED_ASR_2_STREAM', 'resource_id', 'Resource ID', 'upstream', 'string', 'text', 'resource_id',
 0, NULL, 0, 0, 'volc.seedasr.sauc.duration', NULL, '豆包流式语音识别模型2.0固定资源 ID，默认使用该值。', 2),
('ASR_DOUBAO_SEED_ASR_2_STREAM', 'context_text', '上下文文本', 'advanced', 'string', 'textarea', 'context_text',
 1, NULL, 0, 0, NULL, NULL, '可选上下文文本，用于提升专有名词和业务场景识别准确率；为空时不发送。', 3);

INSERT INTO `ai_model_config`
(`id`, `model_type`, `model_code`, `model_name`, `adapter_code`, `template_code`, `vendor_name`,
 `product_name`, `protocol`, `upstream_model_id`, `endpoint_url`, `is_default`, `is_enabled`,
 `test_status`, `test_message`, `test_time`, `config_json`, `doc_link`, `remark`, `sort`,
 `creator`, `create_date`, `updater`, `update_date`)
VALUES
('ASR_DoubaoRecordingASR2', 'ASR', 'DoubaoRecordingASR2', '豆包录音识别模型2.0',
 'doubao_recording', 'ASR_DOUBAO_RECORDING_ASR_2', '火山引擎', '豆包录音识别模型2.0',
 'HTTP', 'volc.seedasr.auc', NULL, 0, 1, 0, NULL, NULL, NULL,
 'https://www.volcengine.com/docs/6561/1354868',
 '新版火山快捷 API 接入，只需填写 API Key。', 3, 1, NOW(), 1, NOW()),
('ASR_DoubaoStreamASRV2', 'ASR', 'DoubaoStreamASRV2', '豆包流式语音识别模型2.0',
 'doubao_stream', 'ASR_DOUBAO_SEED_ASR_2_STREAM', '火山引擎', '豆包流式语音识别模型2.0',
 'WebSocket', 'volc.seedasr.sauc.duration', NULL, 0, 1, 0, NULL, NULL, NULL,
 'https://www.volcengine.com/docs/6561/1354869',
 '新版火山快捷 API 接入，只需填写 API Key。', 4, 1, NOW(), 1, NOW())
ON DUPLICATE KEY UPDATE
  `model_code` = VALUES(`model_code`),
  `model_name` = VALUES(`model_name`),
  `adapter_code` = VALUES(`adapter_code`),
  `template_code` = VALUES(`template_code`),
  `vendor_name` = VALUES(`vendor_name`),
  `product_name` = VALUES(`product_name`),
  `protocol` = VALUES(`protocol`),
  `upstream_model_id` = VALUES(`upstream_model_id`),
  `endpoint_url` = VALUES(`endpoint_url`),
  `config_json` = VALUES(`config_json`),
  `doc_link` = VALUES(`doc_link`),
  `remark` = VALUES(`remark`),
  `sort` = VALUES(`sort`),
  `test_status` = 0,
  `test_message` = NULL,
  `test_time` = NULL,
  `updater` = VALUES(`updater`),
  `update_date` = VALUES(`update_date`);

DELETE FROM `ai_model_config_param`
WHERE `model_id` IN ('ASR_DoubaoRecordingASR2', 'ASR_DoubaoStreamASRV2')
  AND `param_key` IN (
    'appid', 'access_token', 'auth_method', 'boosting_table_name', 'correct_table_name',
    'end_window_size', 'enable_multilingual', 'language', 'cluster'
  );

DELETE FROM `ai_model_config_secret`
WHERE `model_id` IN ('ASR_DoubaoRecordingASR2', 'ASR_DoubaoStreamASRV2')
  AND `secret_key` <> 'api_key';

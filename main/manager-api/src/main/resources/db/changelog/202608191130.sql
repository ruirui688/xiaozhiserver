-- 恢复豆包流式 ASR 2.0 的官方句尾控制：由上游内部 VAD 使用 end_window_size。

UPDATE `ai_model_provider`
SET `fields` = JSON_ARRAY(
    JSON_OBJECT('key', 'api_key', 'label', 'API Key', 'type', 'password'),
    JSON_OBJECT('key', 'context_text', 'label', '上下文文本', 'type', 'string'),
    JSON_OBJECT('key', 'end_window_size', 'label', '句尾静音窗口(ms)', 'type', 'number')
)
WHERE `id` = 'SYSTEM_ASR_DoubaoStream2'
  AND `provider_code` = 'doubao_stream';

DELETE FROM `ai_model_template_field`
WHERE `template_id` = 'ASR_DOUBAO_SEED_ASR_2_STREAM'
  AND `field_key` = 'end_window_size';

INSERT INTO `ai_model_template_field`
(`template_id`, `field_key`, `field_label`, `field_group`, `value_type`, `input_type`, `runtime_key`,
 `advanced`, `visible_when`, `required`, `secret`, `default_value`, `options_json`, `help_text`, `sort`)
VALUES
('ASR_DOUBAO_SEED_ASR_2_STREAM', 'end_window_size', '句尾静音窗口(ms)', 'advanced', 'number', 'number',
 'end_window_size', 1, NULL, 0, 0, '200', NULL,
 '豆包内部 VAD 判定句尾所需的连续静音时长；自动/实时模式等待 definite=true。', 4);

INSERT INTO `ai_model_config_param`
(`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT 'ASR_DoubaoStreamASRV2', 'end_window_size', '200', 'number', 'advanced'
WHERE EXISTS (
    SELECT 1 FROM `ai_model_config` WHERE `id` = 'ASR_DoubaoStreamASRV2'
)
  AND NOT EXISTS (
    SELECT 1
    FROM `ai_model_config_param`
    WHERE `model_id` = 'ASR_DoubaoStreamASRV2'
      AND `param_key` = 'end_window_size'
  );

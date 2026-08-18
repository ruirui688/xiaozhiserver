-- 将实时对话链路收敛到官方 200ms 句尾静音，并启用已有的豆包流式 ASR。
-- ai_model_config_param 是标准模型存储，也是运行时 typed_config 的权威来源。

UPDATE `ai_model_config_param`
SET `param_value` = '200',
    `value_type` = 'number',
    `param_group` = 'provider'
WHERE `model_id` = 'VAD_SileroVAD'
  AND `param_key` = 'min_silence_duration_ms'
  AND TRIM(COALESCE(`param_value`, '')) IN ('', '700', '700.0');

INSERT INTO `ai_model_config_param`
(`model_id`, `param_key`, `param_value`, `value_type`, `param_group`)
SELECT 'VAD_SileroVAD', 'min_silence_duration_ms', '200', 'number', 'provider'
WHERE EXISTS (
    SELECT 1 FROM `ai_model_config` WHERE `id` = 'VAD_SileroVAD'
)
  AND NOT EXISTS (
    SELECT 1
    FROM `ai_model_config_param`
    WHERE `model_id` = 'VAD_SileroVAD'
      AND `param_key` = 'min_silence_duration_ms'
  );

-- 仅同步仍在使用旧 700ms（或缺失值）的兼容配置，不覆盖其他人工调参。
UPDATE `ai_model_config`
SET `config_json` = JSON_SET(`config_json`, '$.min_silence_duration_ms', 200)
WHERE `id` = 'VAD_SileroVAD'
  AND `config_json` IS NOT NULL
  AND JSON_VALID(`config_json`)
  AND (
    JSON_EXTRACT(`config_json`, '$.min_silence_duration_ms') IS NULL
    OR JSON_UNQUOTE(JSON_EXTRACT(`config_json`, '$.min_silence_duration_ms')) IN ('700', '700.0')
  );

UPDATE `sys_params`
SET `param_value` = '200',
    `value_type` = 'number',
    `remark` = 'Silero VAD最小静音持续时间，单位毫秒；实时对话默认200ms，达到后判定本轮说话结束'
WHERE `param_code` = 'server.vad_min_silence_duration_ms'
  AND TRIM(COALESCE(`param_value`, '')) IN ('', '700', '700.0');

-- 流式模型缺少 API Key 时复用同一火山账号下录音模型的 Key；已有流式 Key 永不覆盖。
INSERT INTO `ai_model_config_secret`
(`model_id`, `secret_key`, `secret_value`, `masked_value`, `value_type`)
SELECT 'ASR_DoubaoStreamASRV2', `secret_key`, `secret_value`, `masked_value`, `value_type`
FROM `ai_model_config_secret` recording_secret
WHERE recording_secret.`model_id` = 'ASR_DoubaoRecordingASR2'
  AND recording_secret.`secret_key` = 'api_key'
  AND NULLIF(TRIM(recording_secret.`secret_value`), '') IS NOT NULL
  AND EXISTS (
    SELECT 1
    FROM `ai_model_config`
    WHERE `id` = 'ASR_DoubaoStreamASRV2'
      AND `is_enabled` = 1
  )
  AND NOT EXISTS (
    SELECT 1
    FROM `ai_model_config_secret` stream_secret
    WHERE stream_secret.`model_id` = 'ASR_DoubaoStreamASRV2'
      AND stream_secret.`secret_key` = 'api_key'
  );

-- 只迁移当前仍使用录音 2.0 且流式模型确实可用的智能体，避免生成不可用路由。
UPDATE `ai_agent` agent
SET `asr_model_id` = 'ASR_DoubaoStreamASRV2'
WHERE agent.`asr_model_id` = 'ASR_DoubaoRecordingASR2'
  AND EXISTS (
    SELECT 1
    FROM `ai_model_config` stream_model
    WHERE stream_model.`id` = 'ASR_DoubaoStreamASRV2'
      AND stream_model.`is_enabled` = 1
  )
  AND EXISTS (
    SELECT 1
    FROM `ai_model_config_secret` stream_secret
    WHERE stream_secret.`model_id` = 'ASR_DoubaoStreamASRV2'
      AND stream_secret.`secret_key` = 'api_key'
      AND NULLIF(TRIM(stream_secret.`secret_value`), '') IS NOT NULL
  );

-- 生产部署允许所有模型暂时为空，不再默认绑定本地 FunASR / SenseVoiceSmall
UPDATE `ai_model_config`
SET `is_default` = 0
WHERE `model_type` IN ('VAD', 'ASR', 'LLM', 'VLLM', 'SLM', 'TTS', 'Memory', 'Intent', 'RAG');

UPDATE `ai_model_config`
SET `is_enabled` = 0,
    `is_default` = 0
WHERE `model_type` = 'ASR'
  AND `model_code` IN ('FunASR', 'FunASRServer');

UPDATE `ai_agent_template`
SET `asr_model_id` = NULL,
    `vad_model_id` = NULL,
    `llm_model_id` = NULL,
    `vllm_model_id` = NULL,
    `slm_model_id` = NULL,
    `tts_model_id` = NULL,
    `tts_voice_id` = NULL,
    `mem_model_id` = NULL,
    `intent_model_id` = NULL;

UPDATE `ai_agent`
SET `asr_model_id` = NULL
WHERE `asr_model_id` = 'ASR_FunASR';

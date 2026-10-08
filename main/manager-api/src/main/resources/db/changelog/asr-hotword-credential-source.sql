-- Existing model IDs remain for safe legacy-draft source resolution; new libraries bind only a credential fingerprint.
ALTER TABLE ai_asr_hotword MODIFY COLUMN model_id VARCHAR(64) NULL;

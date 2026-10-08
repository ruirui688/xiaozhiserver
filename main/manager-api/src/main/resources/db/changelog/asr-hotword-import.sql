-- A vendor table is claimed by exactly one managed library. NULL publication intents remain allowed.
ALTER TABLE ai_asr_hotword_revision ADD UNIQUE KEY uk_hotword_revision_remote (remote_id);

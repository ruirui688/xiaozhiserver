-- Registration trial policy marker. Keeps the one-time trial grant per user
-- traceable without touching money columns or historical ledger entries.
ALTER TABLE ai_platform_account ADD COLUMN trial_policy VARCHAR(64) NULL;

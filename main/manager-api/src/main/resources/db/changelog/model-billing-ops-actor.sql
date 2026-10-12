-- Preserve historical tenant-user actors. New operations use the independent employee identity.
ALTER TABLE ai_billing_sync_run MODIFY COLUMN created_by BIGINT NULL;
ALTER TABLE ai_billing_sync_run ADD COLUMN created_by_employee_id VARCHAR(36) NULL;
ALTER TABLE ai_billing_sync_run ADD CONSTRAINT chk_billing_sync_actor
  CHECK (created_by IS NOT NULL OR created_by_employee_id IS NOT NULL);

ALTER TABLE ai_billing_binding MODIFY COLUMN created_by BIGINT NULL;
ALTER TABLE ai_billing_binding ADD COLUMN created_by_employee_id VARCHAR(36) NULL;
ALTER TABLE ai_billing_binding ADD CONSTRAINT chk_billing_binding_actor
  CHECK (created_by IS NOT NULL OR created_by_employee_id IS NOT NULL);

-- Official prepaid bills can omit their consumption end. Keep the unknown value without inventing a period.
ALTER TABLE ai_billing_bill MODIFY COLUMN expense_end datetime(3) NULL;

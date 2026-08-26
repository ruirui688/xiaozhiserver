ALTER TABLE `ai_agent_template`
  ADD COLUMN `visibility` VARCHAR(16) NOT NULL DEFAULT 'WORKSPACE' COMMENT 'WORKSPACE/PUBLIC/OFFICIAL' AFTER `workspace_id`,
  ADD COLUMN `review_status` VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING/APPROVED/REJECTED' AFTER `visibility`,
  ADD COLUMN `reviewer_id` BIGINT NULL COMMENT '审核人' AFTER `review_status`,
  ADD COLUMN `review_note` VARCHAR(500) NULL COMMENT '审核说明' AFTER `reviewer_id`,
  ADD COLUMN `reviewed_at` DATETIME NULL COMMENT '审核时间' AFTER `review_note`,
  ADD COLUMN `published_at` DATETIME NULL COMMENT '发布时间' AFTER `reviewed_at`,
  ADD COLUMN `public_snapshot_json` JSON NULL COMMENT '跨工作空间应用的脱敏快照' AFTER `published_at`,
  ADD KEY `idx_agent_template_marketplace` (`visibility`, `review_status`, `sort`);

UPDATE `ai_agent_template`
SET `visibility` = 'OFFICIAL', `review_status` = 'APPROVED', `published_at` = COALESCE(`updated_at`, `created_at`, NOW())
WHERE `workspace_id` IS NULL;

ALTER TABLE `ai_rag_dataset`
  ADD COLUMN `scope` VARCHAR(16) NOT NULL DEFAULT 'WORKSPACE' COMMENT 'WORKSPACE/OFFICIAL' AFTER `workspace_id`,
  ADD KEY `idx_ai_rag_dataset_scope_status` (`scope`, `status`, `created_at`);

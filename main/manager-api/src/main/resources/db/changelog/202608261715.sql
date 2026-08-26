ALTER TABLE `ai_maintenance_policy`
  ADD COLUMN `custom_prompt` text DEFAULT NULL COMMENT '用户可编辑的业务维护指令，不覆盖系统安全规则' AFTER `topics_json`;

ALTER TABLE `ai_maintenance_run`
  ADD COLUMN `user_instruction` varchar(2000) DEFAULT NULL COMMENT '本次人工对话指令' AFTER `trigger_type`;

ALTER TABLE `ai_maintenance_proposal`
  MODIFY COLUMN `action_type` varchar(24) NOT NULL COMMENT 'ADD_SOURCE/REMOVE_SOURCE/UPDATE/ARCHIVE/DELETE',
  MODIFY COLUMN `target_id` varchar(64) DEFAULT NULL COMMENT '记忆ID或动态来源ID';

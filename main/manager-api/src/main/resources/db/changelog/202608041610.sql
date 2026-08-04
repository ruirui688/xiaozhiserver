ALTER TABLE `ai_agent_template`
  ADD COLUMN `workspace_id` VARCHAR(32) NULL COMMENT '工作空间ID，NULL表示系统模板' AFTER `version`,
  ADD COLUMN `template_name` VARCHAR(64) NULL COMMENT '模板显示名称' AFTER `workspace_id`,
  ADD COLUMN `source_agent_id` VARCHAR(32) NULL COMMENT '创建快照时的来源智能体ID' AFTER `template_name`,
  ADD COLUMN `snapshot_json` JSON NULL COMMENT '工作空间角色模板快照' AFTER `source_agent_id`,
  ADD KEY `idx_agent_template_workspace_sort` (`workspace_id`, `sort`),
  ADD UNIQUE KEY `uk_agent_template_workspace_name` (`workspace_id`, `template_name`);

UPDATE `ai_agent_template`
SET `template_name` = `agent_name`
WHERE `template_name` IS NULL OR `template_name` = '';

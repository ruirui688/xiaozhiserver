ALTER TABLE `ai_agent_feishu_config`
  MODIFY COLUMN `enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否启用飞书集成',
  MODIFY COLUMN `meeting_report_enabled` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否自动发送会议纪要';

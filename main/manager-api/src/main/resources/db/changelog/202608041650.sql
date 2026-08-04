ALTER TABLE `ai_agent_voice_print`
  ADD COLUMN `workspace_id` VARCHAR(32) NULL COMMENT '声纹所属工作空间' AFTER `agent_id`,
  ADD KEY `idx_agent_voice_print_workspace` (`workspace_id`);

UPDATE `ai_agent_voice_print` voice_print
INNER JOIN `ai_agent` agent ON agent.`id` = voice_print.`agent_id`
SET voice_print.`workspace_id` = agent.`workspace_id`
WHERE voice_print.`workspace_id` IS NULL;

CREATE TABLE `ai_agent_voice_print_binding` (
  `id` VARCHAR(32) NOT NULL COMMENT '绑定ID',
  `agent_id` VARCHAR(32) NOT NULL COMMENT '使用声纹的智能体ID',
  `voice_print_id` VARCHAR(32) NOT NULL COMMENT '声纹资源ID',
  `creator` BIGINT NULL COMMENT '创建者',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_voice_print_binding` (`agent_id`, `voice_print_id`),
  KEY `idx_voice_print_binding_voice_print` (`voice_print_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体声纹绑定关系';

INSERT IGNORE INTO `ai_agent_voice_print_binding`
  (`id`, `agent_id`, `voice_print_id`, `creator`, `created_at`)
SELECT REPLACE(UUID(), '-', ''), `agent_id`, `id`, `creator`, COALESCE(`create_date`, NOW())
FROM `ai_agent_voice_print`
WHERE `agent_id` IS NOT NULL AND `agent_id` <> '';

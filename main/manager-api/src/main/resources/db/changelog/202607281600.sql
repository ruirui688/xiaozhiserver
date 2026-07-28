CREATE TABLE IF NOT EXISTS `ai_agent_semantic_skill` (
  `id` varchar(32) NOT NULL COMMENT '主键',
  `agent_id` varchar(32) NOT NULL COMMENT '所属智能体ID',
  `skill_name` varchar(64) NOT NULL COMMENT '技能名称',
  `enabled` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
  `trigger_rules` json NOT NULL COMMENT '触发规则，支持phrase与all_keywords',
  `response_text` text NOT NULL COMMENT '命中后的固定播报文本',
  `priority` int NOT NULL DEFAULT 0 COMMENT '冲突优先级，数值越大越优先',
  `sort` int NOT NULL DEFAULT 0 COMMENT '页面排序，数值越小越靠前',
  `creator` bigint DEFAULT NULL COMMENT '创建者',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` bigint DEFAULT NULL COMMENT '更新者',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_agent_semantic_skill_agent_sort` (`agent_id`, `sort`, `id`),
  KEY `idx_agent_semantic_skill_runtime` (`agent_id`, `enabled`, `priority`, `sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能体固定播报技能';

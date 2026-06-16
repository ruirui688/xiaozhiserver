CREATE TABLE IF NOT EXISTS `ai_agent_mcp_no_code_tools` (
  `id` varchar(64) NOT NULL COMMENT '主键',
  `agent_id` varchar(64) NOT NULL COMMENT '智能体ID',
  `tools` json DEFAULT NULL COMMENT '可视化 MCP HTTP 工具配置',
  `creator` bigint DEFAULT NULL COMMENT '创建者',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` bigint DEFAULT NULL COMMENT '更新者',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_agent_mcp_no_code_tools_agent_id` (`agent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能体可视化 MCP HTTP 工具配置';

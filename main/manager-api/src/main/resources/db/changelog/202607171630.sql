CREATE TABLE IF NOT EXISTS `ai_user_plugin_config` (
  `id` bigint NOT NULL COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `provider_code` varchar(64) NOT NULL COMMENT '插件provider_code',
  `config_ciphertext` text NOT NULL COMMENT '使用部署级密钥加密的共享配置JSON',
  `creator` bigint DEFAULT NULL COMMENT '创建者',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updater` bigint DEFAULT NULL COMMENT '更新者',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_plugin_config_user_provider` (`user_id`, `provider_code`),
  KEY `idx_user_plugin_config_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户级插件共享配置';

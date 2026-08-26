ALTER TABLE `ai_maintenance_policy`
  ADD COLUMN `model_config_id` varchar(32) DEFAULT NULL COMMENT '模型管理中的DeepSeek LLM配置ID' AFTER `max_monthly_runs`,
  ADD KEY `idx_maintenance_model_config` (`model_config_id`);

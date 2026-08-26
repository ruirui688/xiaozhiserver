CREATE TABLE `ai_maintenance_run_event` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '单调递增事件ID，用于前端增量轮询',
    `run_id` varchar(32) NOT NULL COMMENT '维护运行ID',
    `phase` varchar(32) NOT NULL COMMENT 'DISCOVERY/PROPOSAL/SOURCE_SYNC/COMPLETE',
    `level` varchar(16) NOT NULL DEFAULT 'INFO' COMMENT 'INFO/SUCCESS/WARN/ERROR',
    `message` varchar(500) NOT NULL COMMENT '已脱敏的用户可见运行说明',
    `detail_json` json DEFAULT NULL COMMENT '有界结构化指标，不保存提示词、密钥或模型原文',
    `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事件时间',
    PRIMARY KEY (`id`),
    KEY `idx_maintenance_run_event_cursor` (`run_id`, `id`),
    CONSTRAINT `fk_maintenance_run_event_run` FOREIGN KEY (`run_id`)
        REFERENCES `ai_maintenance_run` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自主维护运行的持久化可观察事件';

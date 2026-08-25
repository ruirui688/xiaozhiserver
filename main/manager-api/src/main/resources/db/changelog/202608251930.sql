CREATE TABLE `ai_rag_knowledge_source` (
    `id` varchar(32) NOT NULL COMMENT '数据源ID',
    `dataset_id` varchar(32) NOT NULL COMMENT '知识库ID',
    `workspace_id` varchar(32) NOT NULL COMMENT '工作空间ID',
    `name` varchar(100) NOT NULL COMMENT '数据源名称',
    `source_type` varchar(32) NOT NULL COMMENT '数据源类型: WEB_PAGE',
    `source_uri` varchar(2048) NOT NULL COMMENT '经安全校验的数据源地址',
    `refresh_interval_minutes` int NOT NULL DEFAULT 30 COMMENT '刷新间隔（分钟）',
    `enabled` tinyint NOT NULL DEFAULT 1 COMMENT '是否启用自动同步',
    `sync_status` varchar(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SYNCING/INDEXED/UNCHANGED/ERROR/PAUSED',
    `etag` varchar(512) DEFAULT NULL COMMENT 'HTTP ETag',
    `last_modified` varchar(512) DEFAULT NULL COMMENT 'HTTP Last-Modified',
    `content_hash` varchar(64) DEFAULT NULL COMMENT '当前发布内容SHA-256',
    `current_document_id` varchar(64) DEFAULT NULL COMMENT '当前已发布文档ID',
    `current_revision` int NOT NULL DEFAULT 0 COMMENT '当前发布版本号',
    `last_sync_at` datetime DEFAULT NULL COMMENT '最后同步尝试时间',
    `last_success_at` datetime DEFAULT NULL COMMENT '最后成功同步时间',
    `next_sync_at` datetime DEFAULT NULL COMMENT '下次计划同步时间',
    `last_error` varchar(500) DEFAULT NULL COMMENT '脱敏后的最后错误',
    `consecutive_failures` int NOT NULL DEFAULT 0 COMMENT '连续失败次数',
    `creator` bigint DEFAULT NULL COMMENT '创建者',
    `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_knowledge_source_dataset` (`dataset_id`, `enabled`),
    KEY `idx_knowledge_source_schedule` (`enabled`, `next_sync_at`),
    CONSTRAINT `fk_knowledge_source_dataset` FOREIGN KEY (`dataset_id`)
        REFERENCES `ai_rag_dataset` (`dataset_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库可选动态数据源';

CREATE TABLE `ai_rag_knowledge_sync_run` (
    `id` varchar(32) NOT NULL COMMENT '同步运行ID',
    `source_id` varchar(32) NOT NULL COMMENT '数据源ID',
    `dataset_id` varchar(32) NOT NULL COMMENT '知识库ID',
    `trigger_type` varchar(16) NOT NULL COMMENT 'MANUAL/SCHEDULED',
    `status` varchar(32) NOT NULL COMMENT 'RUNNING/INDEXING/INDEXED/UNCHANGED/FAILED',
    `revision` int DEFAULT NULL COMMENT '本次候选版本号',
    `bytes_fetched` bigint NOT NULL DEFAULT 0 COMMENT '抓取字节数',
    `documents_updated` int NOT NULL DEFAULT 0 COMMENT '更新文档数',
    `documents_skipped` int NOT NULL DEFAULT 0 COMMENT '跳过文档数',
    `error` varchar(500) DEFAULT NULL COMMENT '脱敏后的错误',
    `started_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开始时间',
    `finished_at` datetime DEFAULT NULL COMMENT '完成时间',
    PRIMARY KEY (`id`),
    KEY `idx_knowledge_sync_source_started` (`source_id`, `started_at`),
    CONSTRAINT `fk_knowledge_sync_source` FOREIGN KEY (`source_id`)
        REFERENCES `ai_rag_knowledge_source` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库数据源同步运行记录';

ALTER TABLE `ai_rag_knowledge_document`
    ADD COLUMN `knowledge_source_id` varchar(32) DEFAULT NULL COMMENT '动态数据源ID' AFTER `source_type`,
    ADD COLUMN `source_revision` int DEFAULT NULL COMMENT '数据源版本号' AFTER `knowledge_source_id`,
    ADD COLUMN `content_hash` varchar(64) DEFAULT NULL COMMENT '源内容SHA-256' AFTER `source_revision`,
    ADD COLUMN `published_at` datetime DEFAULT NULL COMMENT '版本发布时间' AFTER `content_hash`,
    ADD KEY `idx_knowledge_document_source_revision` (`knowledge_source_id`, `source_revision`),
    ADD KEY `idx_knowledge_document_active` (`dataset_id`, `enabled`);

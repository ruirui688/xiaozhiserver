CREATE TABLE `ai_maintenance_policy` (
    `id` varchar(32) NOT NULL COMMENT '维护策略ID',
    `workspace_id` varchar(32) NOT NULL COMMENT '资源所属工作空间',
    `resource_type` varchar(16) NOT NULL COMMENT 'KNOWLEDGE/MEMORY',
    `resource_id` varchar(64) NOT NULL COMMENT '知识库datasetId或智能体ID',
    `mode` varchar(32) NOT NULL DEFAULT 'OFF' COMMENT 'OFF/DISCOVERY_REVIEW/DISCOVERY_AUTO/REVIEW',
    `topics_json` text COMMENT '检索主题JSON数组',
    `trusted_domains_json` text COMMENT '自动采纳域名白名单JSON数组',
    `schedule_minutes` int NOT NULL DEFAULT 360 COMMENT '维护间隔分钟',
    `max_proposals` int NOT NULL DEFAULT 8 COMMENT '单次最多提案数',
    `max_monthly_runs` int NOT NULL DEFAULT 60 COMMENT '每月最多Harness运行数',
    `enabled` tinyint NOT NULL DEFAULT 0 COMMENT '是否启用',
    `last_run_at` datetime DEFAULT NULL,
    `next_run_at` datetime DEFAULT NULL,
    `creator` bigint DEFAULT NULL,
    `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_maintenance_resource` (`resource_type`, `resource_id`),
    KEY `idx_maintenance_schedule` (`enabled`, `next_run_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='按资源启用的Harness维护策略';

CREATE TABLE `ai_maintenance_run` (
    `id` varchar(32) NOT NULL COMMENT '维护运行ID',
    `policy_id` varchar(32) NOT NULL,
    `workspace_id` varchar(32) NOT NULL,
    `resource_type` varchar(16) NOT NULL,
    `resource_id` varchar(64) NOT NULL,
    `trigger_type` varchar(16) NOT NULL COMMENT 'MANUAL/SCHEDULED',
    `status` varchar(16) NOT NULL COMMENT 'QUEUED/RUNNING/SUCCEEDED/FAILED',
    `proposal_count` int NOT NULL DEFAULT 0,
    `input_chars` int NOT NULL DEFAULT 0,
    `output_chars` int NOT NULL DEFAULT 0,
    `error` varchar(500) DEFAULT NULL COMMENT '脱敏后的错误',
    `started_at` datetime DEFAULT NULL,
    `finished_at` datetime DEFAULT NULL,
    `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_maintenance_run_resource` (`resource_type`, `resource_id`, `created_at`),
    KEY `idx_maintenance_run_policy` (`policy_id`, `created_at`),
    CONSTRAINT `fk_maintenance_run_policy` FOREIGN KEY (`policy_id`)
        REFERENCES `ai_maintenance_policy` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Harness维护运行审计';

CREATE TABLE `ai_maintenance_proposal` (
    `id` varchar(32) NOT NULL COMMENT '维护提案ID',
    `run_id` varchar(32) NOT NULL,
    `policy_id` varchar(32) NOT NULL,
    `workspace_id` varchar(32) NOT NULL,
    `resource_type` varchar(16) NOT NULL,
    `resource_id` varchar(64) NOT NULL,
    `action_type` varchar(24) NOT NULL COMMENT 'ADD_SOURCE/UPDATE/ARCHIVE/DELETE',
    `target_id` varchar(64) DEFAULT NULL COMMENT '记忆ID',
    `source_uri` varchar(2048) DEFAULT NULL COMMENT '候选网页地址',
    `title` varchar(255) DEFAULT NULL,
    `rationale` varchar(1000) NOT NULL,
    `payload_json` mediumtext COMMENT '经过字段白名单校验的动作参数',
    `evidence_json` mediumtext COMMENT '证据摘要JSON',
    `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPLYING/APPLIED/REJECTED/FAILED',
    `reviewer` bigint DEFAULT NULL,
    `reviewed_at` datetime DEFAULT NULL,
    `error` varchar(500) DEFAULT NULL,
    `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_maintenance_proposal_resource` (`resource_type`, `resource_id`, `status`, `created_at`),
    KEY `idx_maintenance_proposal_run` (`run_id`),
    CONSTRAINT `fk_maintenance_proposal_run` FOREIGN KEY (`run_id`)
        REFERENCES `ai_maintenance_run` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_maintenance_proposal_policy` FOREIGN KEY (`policy_id`)
        REFERENCES `ai_maintenance_policy` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Harness结构化维护提案';

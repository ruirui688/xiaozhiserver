CREATE TABLE `ai_maintenance_conversation` (
    `id` varchar(32) NOT NULL COMMENT '维护会话ID',
    `policy_id` varchar(32) NOT NULL COMMENT '维护策略ID',
    `workspace_id` varchar(32) NOT NULL COMMENT '资源所属工作空间',
    `resource_type` varchar(16) NOT NULL COMMENT 'KNOWLEDGE/MEMORY',
    `resource_id` varchar(64) NOT NULL COMMENT '知识库datasetId或智能体ID',
    `origin_type` varchar(16) NOT NULL COMMENT 'USER/MANUAL/SCHEDULED',
    `title` varchar(100) NOT NULL COMMENT '用户可见会话标题',
    `archived` tinyint NOT NULL DEFAULT 0 COMMENT '是否隐藏到已隐藏会话',
    `creator` bigint DEFAULT NULL,
    `last_message_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `deleted_at` datetime(3) DEFAULT NULL COMMENT '软删除时间，运行审计仍保留',
    `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_maintenance_conversation_resource` (`resource_type`, `resource_id`, `deleted_at`, `archived`, `last_message_at`),
    KEY `idx_maintenance_conversation_policy_origin` (`policy_id`, `origin_type`, `deleted_at`),
    CONSTRAINT `fk_maintenance_conversation_policy` FOREIGN KEY (`policy_id`)
        REFERENCES `ai_maintenance_policy` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Harness资源级多轮维护会话';

ALTER TABLE `ai_maintenance_run`
    ADD COLUMN `conversation_id` varchar(32) DEFAULT NULL COMMENT '所属维护会话ID' AFTER `policy_id`,
    MODIFY COLUMN `user_instruction` text DEFAULT NULL COMMENT '本次人工会话消息',
    ADD KEY `idx_maintenance_run_conversation` (`conversation_id`, `created_at`);

-- 旧的交互运行无法可靠推断彼此是否属于同一会话，因此每轮保留为一个历史会话。
INSERT INTO `ai_maintenance_conversation` (
    `id`, `policy_id`, `workspace_id`, `resource_type`, `resource_id`, `origin_type`, `title`,
    `archived`, `creator`, `last_message_at`, `created_at`, `updated_at`
)
SELECT r.`id`, r.`policy_id`, r.`workspace_id`, r.`resource_type`, r.`resource_id`, 'USER',
       CASE WHEN r.`user_instruction` IS NOT NULL AND r.`user_instruction` <> ''
                THEN LEFT(r.`user_instruction`, 100)
            ELSE '历史交互会话' END,
       0, p.`creator`, COALESCE(r.`finished_at`, r.`created_at`), r.`created_at`,
       COALESCE(r.`finished_at`, r.`created_at`)
FROM `ai_maintenance_run` r
JOIN `ai_maintenance_policy` p ON p.`id` = r.`policy_id`
WHERE r.`trigger_type` = 'INTERACTIVE';

-- 同一资源的旧手动/定时运行分别归并到稳定的系统会话，避免左侧继续出现重复记录。
INSERT INTO `ai_maintenance_conversation` (
    `id`, `policy_id`, `workspace_id`, `resource_type`, `resource_id`, `origin_type`, `title`,
    `archived`, `creator`, `last_message_at`, `created_at`, `updated_at`
)
SELECT MIN(r.`id`), r.`policy_id`, r.`workspace_id`, r.`resource_type`, r.`resource_id`,
       CASE WHEN r.`trigger_type` = 'SCHEDULED' THEN 'SCHEDULED' ELSE 'MANUAL' END,
       CASE WHEN r.`trigger_type` = 'SCHEDULED' THEN '定时维护' ELSE '手动维护' END,
       0, p.`creator`, MAX(COALESCE(r.`finished_at`, r.`created_at`)), MIN(r.`created_at`),
       MAX(COALESCE(r.`finished_at`, r.`created_at`))
FROM `ai_maintenance_run` r
JOIN `ai_maintenance_policy` p ON p.`id` = r.`policy_id`
WHERE r.`trigger_type` <> 'INTERACTIVE'
GROUP BY r.`policy_id`, r.`workspace_id`, r.`resource_type`, r.`resource_id`,
         CASE WHEN r.`trigger_type` = 'SCHEDULED' THEN 'SCHEDULED' ELSE 'MANUAL' END,
         CASE WHEN r.`trigger_type` = 'SCHEDULED' THEN '定时维护' ELSE '手动维护' END,
         p.`creator`;

UPDATE `ai_maintenance_run` r
SET r.`conversation_id` = r.`id`
WHERE r.`trigger_type` = 'INTERACTIVE' AND r.`conversation_id` IS NULL;

UPDATE `ai_maintenance_run` r
JOIN `ai_maintenance_conversation` c
  ON c.`policy_id` = r.`policy_id`
 AND c.`workspace_id` = r.`workspace_id`
 AND c.`resource_type` = r.`resource_type`
 AND c.`resource_id` = r.`resource_id`
 AND c.`origin_type` = CASE WHEN r.`trigger_type` = 'SCHEDULED' THEN 'SCHEDULED' ELSE 'MANUAL' END
SET r.`conversation_id` = c.`id`
WHERE r.`trigger_type` <> 'INTERACTIVE' AND r.`conversation_id` IS NULL;

ALTER TABLE `ai_maintenance_run`
    ADD CONSTRAINT `fk_maintenance_run_conversation` FOREIGN KEY (`conversation_id`)
        REFERENCES `ai_maintenance_conversation` (`id`) ON DELETE SET NULL;

CREATE TABLE `ai_maintenance_message` (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '会话消息ID',
    `conversation_id` varchar(32) NOT NULL COMMENT '维护会话ID',
    `run_id` varchar(32) DEFAULT NULL COMMENT '产生该消息的维护运行ID',
    `role` varchar(16) NOT NULL COMMENT 'USER/ASSISTANT/SYSTEM',
    `content` mediumtext NOT NULL COMMENT '智控台完整展示的用户消息或Harness最终回复',
    `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`id`),
    KEY `idx_maintenance_message_conversation` (`conversation_id`, `id`),
    KEY `idx_maintenance_message_run` (`run_id`, `id`),
    CONSTRAINT `fk_maintenance_message_conversation` FOREIGN KEY (`conversation_id`)
        REFERENCES `ai_maintenance_conversation` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_maintenance_message_run` FOREIGN KEY (`run_id`)
        REFERENCES `ai_maintenance_run` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Harness维护会话的完整可见消息';

INSERT INTO `ai_maintenance_message` (`conversation_id`, `run_id`, `role`, `content`, `created_at`)
SELECT r.`conversation_id`, r.`id`,
       CASE WHEN r.`trigger_type` = 'SCHEDULED' THEN 'SYSTEM' ELSE 'USER' END,
       CASE WHEN r.`user_instruction` IS NOT NULL AND r.`user_instruction` <> '' THEN r.`user_instruction`
            WHEN r.`trigger_type` = 'SCHEDULED' THEN '按照已保存的维护策略执行定时维护。'
            ELSE '按照已保存的维护策略执行手动维护。' END,
       r.`created_at`
FROM `ai_maintenance_run` r;

INSERT INTO `ai_maintenance_message` (`conversation_id`, `run_id`, `role`, `content`, `created_at`)
SELECT r.`conversation_id`, r.`id`, 'ASSISTANT',
       CASE WHEN r.`status` = 'FAILED' THEN CONCAT('本轮维护失败：', COALESCE(r.`error`, '未知错误'))
            WHEN r.`status` = 'SUCCEEDED' THEN CONCAT('本轮维护完成，生成 ', r.`proposal_count`, ' 条提案。')
            ELSE CONCAT('历史维护运行状态：', r.`status`) END,
       COALESCE(r.`finished_at`, r.`created_at`)
FROM `ai_maintenance_run` r;

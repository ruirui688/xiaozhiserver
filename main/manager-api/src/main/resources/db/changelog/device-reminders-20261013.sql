CREATE TABLE IF NOT EXISTS `ai_device_reminder` (
    `id` VARCHAR(32) NOT NULL COMMENT '提醒ID',
    `workspace_id` VARCHAR(32) NOT NULL COMMENT '所属工作空间ID',
    `device_id` VARCHAR(32) NOT NULL COMMENT 'ai_device数据库记录ID，不是MAC地址',
    `agent_id` VARCHAR(32) NOT NULL COMMENT '创建提醒时设备绑定的智能体ID',
    `title` VARCHAR(200) NOT NULL COMMENT '提醒标题',
    `spoken_text` VARCHAR(500) NOT NULL COMMENT '到点播报文本，不写入日志',
    `remind_at` DATETIME(3) NOT NULL COMMENT 'UTC时刻，由JDBC按统一时区持久化',
    `timezone` VARCHAR(64) NOT NULL COMMENT 'IANA时区名称',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DELIVERED/CANCELLED/MISSED',
    `source` VARCHAR(16) NOT NULL COMMENT 'VOICE/MINI_PROGRAM',
    `source_request_id` VARCHAR(128) NULL COMMENT '语音工具调用幂等ID',
    `request_hash` CHAR(64) NULL COMMENT '幂等请求内容SHA-256',
    `attempt_count` INT NOT NULL DEFAULT 0 COMMENT '投递领取次数',
    `next_attempt_at` DATETIME(3) NULL COMMENT '下次允许投递时间',
    `last_attempt_at` DATETIME(3) NULL COMMENT '最近一次领取时间',
    `delivered_at` DATETIME(3) NULL COMMENT '实时服务确认排队时间',
    `last_error_code` VARCHAR(64) NULL COMMENT '仅保存错误码，不保存响应正文',
    `lease_token` VARCHAR(36) NULL COMMENT '多实例投递租约令牌',
    `lease_until` DATETIME(3) NULL COMMENT '投递租约到期时间',
    `creator` BIGINT NULL COMMENT '创建者；语音创建时可为空',
    `create_date` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    `updater` BIGINT NULL COMMENT '更新者',
    `update_date` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_device_reminder_request` (`device_id`, `source_request_id`),
    KEY `idx_device_reminder_due` (`status`, `next_attempt_at`),
    KEY `idx_device_reminder_expiry` (`status`, `remind_at`),
    KEY `idx_device_reminder_pending_hash` (`device_id`, `status`, `request_hash`),
    KEY `idx_device_reminder_workspace_time` (`workspace_id`, `status`, `remind_at`),
    KEY `idx_device_reminder_device_time` (`device_id`, `status`, `remind_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备一次性日程提醒与可靠投递状态';

-- 只登记可选插件目录，不自动绑定到任何现有智能体。
INSERT INTO `ai_model_provider` (
    `id`, `model_type`, `provider_code`, `name`, `fields`, `sort`,
    `creator`, `create_date`, `updater`, `update_date`
)
SELECT 'SYSTEM_PLUGIN_DEVICE_REMINDER',
       'Plugin',
       'device_reminder',
       '设备日程提醒',
       JSON_ARRAY(
           JSON_OBJECT(
               'key', 'tool_source',
               'type', 'string',
               'label', '工具来源',
               'default', 'server_plugin'
           ),
           JSON_OBJECT(
               'key', 'description',
               'type', 'textarea',
               'label', '工具调用说明',
               'default', '仅在用户明确要求创建、查看或取消提醒时调用；普通讨论未来时间不得创建提醒。只有工具成功返回后才可声称已创建或取消，创建成功后必须向用户复述明确日期、时间和时区。'
           )
       ),
       96,
       0,
       NOW(),
       0,
       NOW()
WHERE NOT EXISTS (
    SELECT 1
    FROM `ai_model_provider`
    WHERE `id` = 'SYSTEM_PLUGIN_DEVICE_REMINDER'
       OR (`model_type` = 'Plugin' AND `provider_code` = 'device_reminder')
);

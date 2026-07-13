CREATE TABLE IF NOT EXISTS ai_memory_session_state (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    agent_id VARCHAR(64) NOT NULL COMMENT '智能体ID',
    session_id VARCHAR(64) NOT NULL COMMENT '会话ID',
    last_event_id BIGINT NOT NULL COMMENT '最后一条对话事件行ID',
    last_user_event_id BIGINT NOT NULL DEFAULT 0 COMMENT '最后一条用户事件行ID',
    last_event_at DATETIME NOT NULL COMMENT '最后一条对话事件时间',
    processed_through_event_id BIGINT NOT NULL DEFAULT 0 COMMENT '已整理至事件行ID',
    processing_through_event_id BIGINT NULL COMMENT '当前正在整理至事件行ID',
    processing_started_at DATETIME NULL COMMENT '本轮整理开始时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_memory_session_state_agent_session (agent_id, session_id),
    KEY idx_ai_memory_session_state_idle (last_event_at, processed_through_event_id, last_user_event_id),
    KEY idx_ai_memory_session_state_processing (processing_started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='长期记忆会话空闲整理水位';

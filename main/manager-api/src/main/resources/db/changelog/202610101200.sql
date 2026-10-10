CREATE TABLE IF NOT EXISTS ai_memory_policy (
    agent_id VARCHAR(64) NOT NULL,
    policy_json JSON NOT NULL,
    PRIMARY KEY (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每智能体记忆策略';

ALTER TABLE ai_memory_forget_tombstone
    ADD COLUMN speaker_id VARCHAR(128) NULL COMMENT '空为共享事实，非空为指定人物',
    ADD COLUMN match_type VARCHAR(16) NOT NULL DEFAULT 'contains' COMMENT 'exact或contains',
    ADD KEY idx_memory_forget_identity (agent_id, status, speaker_id);

ALTER TABLE ai_memory_revision
    ADD KEY idx_memory_session_processed (agent_id, revision_action, actor_id);

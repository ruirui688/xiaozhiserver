ALTER TABLE ai_memory_fact
    ADD COLUMN embedding_json LONGTEXT NULL COMMENT '事实嵌入向量(JSON数组格式)',
    ADD COLUMN importance_dimensions JSON NULL COMMENT '多维度重要性评分',
    ADD COLUMN memory_tier VARCHAR(16) DEFAULT 'short_term' COMMENT '记忆层级: working/short_term/long_term';

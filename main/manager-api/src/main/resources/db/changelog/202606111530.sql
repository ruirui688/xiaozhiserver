CREATE TABLE IF NOT EXISTS `ai_rag_knowledge_chunk` (
    `id` varchar(36) NOT NULL COMMENT '本地唯一ID',
    `dataset_id` varchar(64) NOT NULL COMMENT '知识库ID',
    `document_id` varchar(64) NOT NULL COMMENT '文档ID',
    `chunk_id` varchar(96) NOT NULL COMMENT '切片ID',
    `content` mediumtext NOT NULL COMMENT '切片内容',
    `question` text DEFAULT NULL COMMENT '问答模式的问题',
    `answer` mediumtext DEFAULT NULL COMMENT '问答模式的答案',
    `sort_order` int(11) DEFAULT 0 COMMENT '切片排序',
    `token_count` bigint(20) DEFAULT 0 COMMENT '粗略Token数',
    `created_at` datetime DEFAULT NULL COMMENT '创建时间',
    `updated_at` datetime DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_local_chunk_id` (`chunk_id`),
    KEY `idx_local_chunk_dataset` (`dataset_id`),
    KEY `idx_local_chunk_document` (`document_id`),
    FULLTEXT KEY `ft_local_chunk_content` (`content`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地知识库切片表';

DELETE FROM `ai_model_provider` WHERE `id` = 'SYSTEM_RAG_local_knowledge';
INSERT INTO `ai_model_provider` (`id`, `model_type`, `provider_code`, `name`, `fields`, `sort`, `creator`, `create_date`, `updater`, `update_date`) VALUES
('SYSTEM_RAG_local_knowledge', 'RAG', 'local_knowledge', '本地知识库', '[{"key": "max_chunk_chars", "type": "string", "label": "切片最大字符数", "default": "1200"}]', 2, 1, NOW(), 1, NOW());

DELETE FROM `ai_model_config` WHERE `id` = 'RAG_LocalKnowledge';
INSERT INTO `ai_model_config` (`id`, `model_type`, `model_code`, `model_name`, `is_default`, `is_enabled`, `config_json`, `doc_link`, `remark`, `sort`, `creator`, `create_date`, `updater`, `update_date`) VALUES
('RAG_LocalKnowledge', 'RAG', 'local_knowledge', '本地知识库', 0, 1, '{"type": "local_knowledge", "max_chunk_chars": 1200}', NULL, '本地知识库适配器：支持上传 txt/md/csv/xlsx/json/jsonl 和问答对文本，数据存储在 manager-api 数据库中，不依赖 RAGFlow。', 2, 1, NOW(), 1, NOW());

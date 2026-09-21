ALTER TABLE ai_rag_knowledge_chunk
    ADD COLUMN enabled TINYINT NOT NULL DEFAULT 1,
    ADD COLUMN edit_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_rag_knowledge_document
    ADD COLUMN content_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN index_dirty TINYINT NOT NULL DEFAULT 0;

CREATE TABLE ai_rag_knowledge_entry_revision (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    dataset_id VARCHAR(128) NOT NULL,
    document_id VARCHAR(128) NOT NULL,
    entry_id VARCHAR(36) NOT NULL,
    version BIGINT NOT NULL,
    content LONGTEXT NOT NULL,
    question LONGTEXT NULL,
    answer LONGTEXT NULL,
    enabled TINYINT NOT NULL,
    creator BIGINT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_entry_version (entry_id, version),
    KEY idx_revision_document (dataset_id, document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

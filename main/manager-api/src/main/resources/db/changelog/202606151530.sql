SET @idx_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_rag_knowledge_document' AND INDEX_NAME = 'idx_local_doc_dataset_created');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX `idx_local_doc_dataset_created` ON `ai_rag_knowledge_document` (`dataset_id`, `created_at`)', 'SELECT ''Index idx_local_doc_dataset_created already exists'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_rag_knowledge_chunk' AND INDEX_NAME = 'idx_local_chunk_dataset_document_sort');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX `idx_local_chunk_dataset_document_sort` ON `ai_rag_knowledge_chunk` (`dataset_id`, `document_id`, `sort_order`)', 'SELECT ''Index idx_local_chunk_dataset_document_sort already exists'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_rag_dataset' AND INDEX_NAME = 'idx_ai_rag_dataset_creator_status');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX `idx_ai_rag_dataset_creator_status` ON `ai_rag_dataset` (`creator`, `status`)', 'SELECT ''Index idx_ai_rag_dataset_creator_status already exists'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

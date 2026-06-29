SET @idx_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_rag_knowledge_chunk' AND INDEX_NAME = 'ft_local_chunk_retrieval');
SET @sql = IF(@idx_exists = 0, 'CREATE FULLTEXT INDEX `ft_local_chunk_retrieval` ON `ai_rag_knowledge_chunk` (`content`, `question`, `answer`)', 'SELECT ''Index ft_local_chunk_retrieval already exists'' AS msg');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

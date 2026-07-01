-- Add embedding_json column to ai_rag_knowledge_chunk table for local knowledge base vector search
ALTER TABLE ai_rag_knowledge_chunk
ADD COLUMN `embedding_json` mediumtext DEFAULT NULL COMMENT '文本嵌入向量（JSON格式），用于本地知识库向量相似度检索' AFTER `token_count`;

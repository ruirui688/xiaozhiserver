-- Knowledge editing retains only the current canonical chunk and concurrency counters.
-- Historical text is intentionally discarded. Recovery requires a pre-migration backup.
-- Before rolling back to an application using revisions, restore its compatible table.
DROP TABLE IF EXISTS ai_rag_knowledge_entry_revision;

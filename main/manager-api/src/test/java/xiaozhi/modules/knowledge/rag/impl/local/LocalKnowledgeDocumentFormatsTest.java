package xiaozhi.modules.knowledge.rag.impl.local;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LocalKnowledgeDocumentFormatsTest {
    @Test
    void acceptsLocalTextAndStructuredFormats() {
        assertTrue(LocalKnowledgeDocumentFormats.supports("faq.JSONL"));
        assertTrue(LocalKnowledgeDocumentFormats.supports("manual.md"));
        assertTrue(LocalKnowledgeDocumentFormats.supports("table.xlsx"));
    }

    @Test
    void rejectsBinaryFormatsWithoutALocalParser() {
        assertFalse(LocalKnowledgeDocumentFormats.supports("manual.pdf"));
        assertFalse(LocalKnowledgeDocumentFormats.supports("manual.docx"));
        assertFalse(LocalKnowledgeDocumentFormats.supports("no-extension"));
    }
}

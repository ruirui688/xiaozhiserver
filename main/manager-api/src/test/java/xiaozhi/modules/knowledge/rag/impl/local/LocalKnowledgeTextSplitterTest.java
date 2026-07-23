package xiaozhi.modules.knowledge.rag.impl.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class LocalKnowledgeTextSplitterTest {
    @Test
    void splitsLlmInputWithoutDroppingText() {
        LocalKnowledgeTextSplitter splitter = new LocalKnowledgeTextSplitter(10);
        String text = "第一部分内容。第二部分内容。第三部分。";

        List<String> sections = splitter.splitLlmSections(text);

        assertTrue(sections.size() >= 2);
        assertEquals(text, String.join("", sections));
        assertTrue(sections.stream().allMatch(section -> section.length() <= 10));
    }
}

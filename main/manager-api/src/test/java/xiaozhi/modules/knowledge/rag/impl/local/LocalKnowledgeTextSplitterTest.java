package xiaozhi.modules.knowledge.rag.impl.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class LocalKnowledgeTextSplitterTest {
    @Test
    void keepsShortParagraphsTogetherWithinLimit() {
        LocalKnowledgeTextSplitter splitter = new LocalKnowledgeTextSplitter(20, 100);

        assertEquals(List.of("第一段。\n\n第二段。"), splitter.splitChunks("第一段。\n\n第二段。"));
    }

    @Test
    void prefersChineseSentenceBoundaryForLongParagraph() {
        LocalKnowledgeTextSplitter splitter = new LocalKnowledgeTextSplitter(12, 100);

        List<String> chunks = splitter.splitChunks("这是第一句话。这里是第二句话。最后一句。");

        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.get(0).endsWith("。"));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= 12));
    }

    @Test
    void splitsLlmInputWithoutDroppingText() {
        LocalKnowledgeTextSplitter splitter = new LocalKnowledgeTextSplitter(10, 10);
        String text = "第一部分内容。第二部分内容。第三部分。";

        List<String> sections = splitter.splitLlmSections(text);

        assertTrue(sections.size() >= 2);
        assertEquals(text, String.join("", sections));
        assertTrue(sections.stream().allMatch(section -> section.length() <= 10));
    }
}

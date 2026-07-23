package xiaozhi.modules.knowledge.rag.impl.local;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

/**
 * Splits source text only to respect the LLM input window. These sections are
 * never persisted as knowledge chunks; final chunks must come from the LLM.
 */
public final class LocalKnowledgeTextSplitter {
    private static final List<String> SENTENCE_SEPARATORS =
            List.of("\n", "。", "！", "？", "；", ";", ".", "!", "?");

    private final int maxLlmInputChars;

    public LocalKnowledgeTextSplitter(int maxLlmInputChars) {
        this.maxLlmInputChars = maxLlmInputChars;
    }

    public List<String> splitLlmSections(String text) {
        String normalized = StringUtils.trimToEmpty(text);
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<String> sections = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int hardEnd = Math.min(start + maxLlmInputChars, normalized.length());
            int end = findSplitEnd(normalized, start, hardEnd, maxLlmInputChars);
            String section = normalized.substring(start, end).trim();
            if (!section.isEmpty()) {
                sections.add(section);
            }
            start = end;
        }
        return sections;
    }

    private int findSplitEnd(String text, int start, int hardEnd, int limit) {
        if (hardEnd >= text.length()) {
            return text.length();
        }
        int best = -1;
        for (String separator : SENTENCE_SEPARATORS) {
            int index = text.lastIndexOf(separator, hardEnd - 1);
            if (index >= start + Math.min(80, limit / 4)) {
                best = Math.max(best, index + separator.length());
            }
        }
        return best > start ? best : hardEnd;
    }

}

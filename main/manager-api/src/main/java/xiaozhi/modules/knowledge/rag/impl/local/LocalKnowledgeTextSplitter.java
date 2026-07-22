package xiaozhi.modules.knowledge.rag.impl.local;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

/**
 * Owns deterministic text boundaries for rule fallback and LLM input windows.
 */
public final class LocalKnowledgeTextSplitter {
    private static final List<String> SENTENCE_SEPARATORS =
            List.of("\n", "。", "！", "？", "；", ";", ".", "!", "?");

    private final int maxChunkChars;
    private final int maxLlmInputChars;

    public LocalKnowledgeTextSplitter(int maxChunkChars, int maxLlmInputChars) {
        this.maxChunkChars = maxChunkChars;
        this.maxLlmInputChars = maxLlmInputChars;
    }

    public List<String> splitChunks(String text) {
        return splitByParagraphs(text, maxChunkChars);
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

    private List<String> splitByParagraphs(String text, int limit) {
        String normalized = StringUtils.trimToEmpty(text);
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : normalized.split("(\\R\\s*\\R)+")) {
            String part = paragraph.trim();
            if (part.isEmpty()) {
                continue;
            }
            if (part.length() > limit) {
                flush(chunks, current);
                splitLongParagraph(chunks, part, limit);
                continue;
            }
            int nextLength = current.isEmpty() ? part.length() : current.length() + 2 + part.length();
            if (nextLength > limit) {
                flush(chunks, current);
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(part);
        }
        flush(chunks, current);
        return chunks;
    }

    private void splitLongParagraph(List<String> chunks, String text, int limit) {
        int start = 0;
        while (start < text.length()) {
            int hardEnd = Math.min(start + limit, text.length());
            int end = findSplitEnd(text, start, hardEnd, limit);
            String content = text.substring(start, end).trim();
            if (!content.isEmpty()) {
                chunks.add(content);
            }
            start = end;
        }
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

    private void flush(List<String> chunks, StringBuilder current) {
        String content = current.toString().trim();
        if (!content.isEmpty()) {
            chunks.add(content);
            current.setLength(0);
        }
    }
}

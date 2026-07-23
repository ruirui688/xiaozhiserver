package xiaozhi.modules.knowledge.rag.impl.local;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

/** Supported source formats for the in-process local parser. */
public final class LocalKnowledgeDocumentFormats {
    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of("txt", "md", "mdx", "csv", "xlsx", "xls", "json", "jsonl", "qa");
    private static final Set<String> GENERIC_CONTENT_TYPES =
            Set.of("application/octet-stream", "binary/octet-stream");
    private static final Map<String, Set<String>> CONTENT_TYPES = Map.of(
            "txt", Set.of("text/plain"),
            "md", Set.of("text/markdown", "text/plain", "text/x-markdown"),
            "mdx", Set.of("text/markdown", "text/plain", "text/mdx"),
            "csv", Set.of("text/csv", "text/plain", "application/csv", "application/vnd.ms-excel"),
            "xlsx", Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            "xls", Set.of("application/vnd.ms-excel", "application/x-msexcel"),
            "json", Set.of("application/json", "text/json", "text/plain"),
            "jsonl", Set.of("application/jsonl", "application/x-ndjson", "text/plain"),
            "qa", Set.of("text/plain"));

    private LocalKnowledgeDocumentFormats() {
    }

    public static boolean supports(String filename) {
        return SUPPORTED_EXTENSIONS.contains(extension(filename));
    }

    public static boolean supportsContentType(String filename, String contentType) {
        String extension = extension(filename);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            return false;
        }
        String normalized = StringUtils.substringBefore(
                StringUtils.lowerCase(StringUtils.trimToEmpty(contentType), Locale.ROOT), ";");
        if (StringUtils.isBlank(normalized) || GENERIC_CONTENT_TYPES.contains(normalized)) {
            return true;
        }
        return CONTENT_TYPES.getOrDefault(extension, Set.of()).contains(normalized);
    }

    private static String extension(String filename) {
        String value = StringUtils.trimToEmpty(filename);
        int dot = value.lastIndexOf('.');
        if (dot < 0 || dot == value.length() - 1) {
            return "";
        }
        return value.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}

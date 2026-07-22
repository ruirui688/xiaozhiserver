package xiaozhi.modules.knowledge.rag.impl.local;

import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

/** Supported source formats for the in-process local parser. */
public final class LocalKnowledgeDocumentFormats {
    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of("txt", "md", "mdx", "csv", "xlsx", "xls", "json", "jsonl", "qa");

    private LocalKnowledgeDocumentFormats() {
    }

    public static boolean supports(String filename) {
        String value = StringUtils.trimToEmpty(filename);
        int dot = value.lastIndexOf('.');
        if (dot < 0 || dot == value.length() - 1) {
            return false;
        }
        return SUPPORTED_EXTENSIONS.contains(value.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}

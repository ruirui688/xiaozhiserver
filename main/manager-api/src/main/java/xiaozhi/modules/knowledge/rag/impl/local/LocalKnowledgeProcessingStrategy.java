package xiaozhi.modules.knowledge.rag.impl.local;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

/** 本地知识库文档处理策略。 */
public enum LocalKnowledgeProcessingStrategy {
    AUTO("auto"),
    DIRECT("direct"),
    LLM_ENHANCED("llm_enhanced");

    private final String configValue;

    LocalKnowledgeProcessingStrategy(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public static LocalKnowledgeProcessingStrategy fromConfig(String value) {
        String normalized = StringUtils.lowerCase(StringUtils.trimToEmpty(value), Locale.ROOT);
        for (LocalKnowledgeProcessingStrategy strategy : values()) {
            if (strategy.configValue.equals(normalized)) {
                return strategy;
            }
        }
        // 已部署知识库没有该字段时保持原有“大模型语义切片”行为。
        return LLM_ENHANCED;
    }

    public static boolean isSupported(String value) {
        String normalized = StringUtils.lowerCase(StringUtils.trimToEmpty(value), Locale.ROOT);
        for (LocalKnowledgeProcessingStrategy strategy : values()) {
            if (strategy.configValue.equals(normalized)) {
                return true;
            }
        }
        return false;
    }
}

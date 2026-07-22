package xiaozhi.modules.agent.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import xiaozhi.modules.knowledge.entity.KnowledgeBaseEntity;

/** Builds a bounded, domain-neutral description for the knowledge search tool. */
final class KnowledgeToolDescriptionBuilder {
    static final int MAX_DESCRIPTION_CHARS = 640;

    private static final int MAX_DATASET_NAMES = 10;
    private static final int MAX_DATASET_DETAILS = 4;
    private static final int MAX_NAME_CHARS = 32;
    private static final int MAX_INSTRUCTION_CHARS = 120;
    private static final String QUERY_RULE =
            "question 只能依据用户当前问题和已经明确的上下文生成，不得擅自补充对象、品类或场景。";

    private KnowledgeToolDescriptionBuilder() {
    }

    static String build(Map<String, List<String>> instructionMap,
            List<KnowledgeBaseEntity> knowledgeBases) {
        List<KnowledgeBaseEntity> available = knowledgeBases == null
                ? List.of()
                : knowledgeBases.stream().filter(item -> item != null).toList();
        List<String> names = distinctNames(available);

        StringBuilder result = new StringBuilder("仅当用户问题明确属于知识库范围时调用。可查询知识库：");
        if (names.isEmpty()) {
            result.append("未命名知识库");
        } else {
            int visibleCount = Math.min(names.size(), MAX_DATASET_NAMES);
            result.append(String.join("、", names.subList(0, visibleCount)));
            if (names.size() > visibleCount) {
                result.append("等").append(names.size()).append("个知识库");
            }
        }
        result.append("。");

        appendDetails(result, instructionMap, available);
        appendWithinBudget(result, QUERY_RULE);
        return result.toString();
    }

    private static List<String> distinctNames(List<KnowledgeBaseEntity> knowledgeBases) {
        Set<String> names = new LinkedHashSet<>();
        for (KnowledgeBaseEntity knowledgeBase : knowledgeBases) {
            String name = normalizeAndLimit(knowledgeBase.getName(), MAX_NAME_CHARS);
            if (StringUtils.isNotBlank(name)) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

    private static void appendDetails(StringBuilder result, Map<String, List<String>> instructionMap,
            List<KnowledgeBaseEntity> knowledgeBases) {
        if (instructionMap == null || instructionMap.isEmpty()) {
            return;
        }
        int appended = 0;
        for (KnowledgeBaseEntity knowledgeBase : knowledgeBases) {
            if (appended >= MAX_DATASET_DETAILS) {
                break;
            }
            List<String> instructions = instructionMap.get(knowledgeBase.getDatasetId());
            if (instructions == null || instructions.isEmpty()) {
                continue;
            }
            List<String> normalized = instructions.stream()
                    .map(value -> normalizeAndLimit(value, MAX_INSTRUCTION_CHARS))
                    .filter(StringUtils::isNotBlank)
                    .toList();
            if (normalized.isEmpty()) {
                continue;
            }
            String detail = "【" + normalizeAndLimit(knowledgeBase.getName(), MAX_NAME_CHARS)
                    + "】" + String.join("；", normalized) + "。";
            int detailBudget = MAX_DESCRIPTION_CHARS - QUERY_RULE.length();
            if (!appendWithinBudget(result, detail, detailBudget)) {
                break;
            }
            appended++;
        }
    }

    private static boolean appendWithinBudget(StringBuilder result, String value) {
        return appendWithinBudget(result, value, MAX_DESCRIPTION_CHARS);
    }

    private static boolean appendWithinBudget(StringBuilder result, String value, int maxLength) {
        int remaining = maxLength - result.length();
        if (remaining <= 0) {
            return false;
        }
        if (value.length() <= remaining) {
            result.append(value);
            return true;
        }
        if (remaining > 1) {
            result.append(value, 0, remaining - 1).append('…');
        }
        return false;
    }

    private static String normalizeAndLimit(String value, int maxChars) {
        String normalized = StringUtils.normalizeSpace(StringUtils.defaultString(value));
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars - 1) + "…";
    }
}

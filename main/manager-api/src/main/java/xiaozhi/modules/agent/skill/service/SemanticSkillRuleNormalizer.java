package xiaozhi.modules.agent.skill.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.skill.dto.SemanticSkillTriggerRuleDTO;

public final class SemanticSkillRuleNormalizer {
    public static final String TYPE_PHRASE = "phrase";
    public static final String TYPE_ALL_KEYWORDS = "all_keywords";

    private SemanticSkillRuleNormalizer() {
    }

    public static List<SemanticSkillTriggerRuleDTO> normalize(List<SemanticSkillTriggerRuleDTO> rules) {
        if (rules == null || rules.isEmpty() || rules.size() > 20) {
            throw invalidRules();
        }

        Map<String, SemanticSkillTriggerRuleDTO> uniqueRules = new LinkedHashMap<>();
        for (SemanticSkillTriggerRuleDTO rule : rules) {
            SemanticSkillTriggerRuleDTO normalized = normalizeRule(rule);
            String termsKey = normalized.getTerms().stream()
                    .map(term -> term.toLowerCase(Locale.ROOT))
                    .sorted()
                    .collect(Collectors.joining("\u0000"));
            String key = normalized.getType() + "\u0000" + termsKey;
            uniqueRules.putIfAbsent(key, normalized);
        }
        return new ArrayList<>(uniqueRules.values());
    }

    private static SemanticSkillTriggerRuleDTO normalizeRule(SemanticSkillTriggerRuleDTO rule) {
        if (rule == null || StringUtils.isBlank(rule.getType()) || rule.getTerms() == null) {
            throw invalidRules();
        }
        String type = rule.getType().trim().toLowerCase(Locale.ROOT);
        if (!TYPE_PHRASE.equals(type) && !TYPE_ALL_KEYWORDS.equals(type)) {
            throw invalidRules();
        }

        Set<String> seen = new LinkedHashSet<>();
        List<String> normalizedTerms = new ArrayList<>();
        for (String rawTerm : rule.getTerms()) {
            if (StringUtils.isBlank(rawTerm)) {
                throw invalidRules();
            }
            String term = StringUtils.normalizeSpace(rawTerm);
            if (term.length() < 2 || term.length() > 100) {
                throw invalidRules();
            }
            if (seen.add(term.toLowerCase(Locale.ROOT))) {
                normalizedTerms.add(term);
            }
        }

        if (TYPE_PHRASE.equals(type) && normalizedTerms.size() != 1) {
            throw invalidRules();
        }
        if (TYPE_ALL_KEYWORDS.equals(type)
                && (normalizedTerms.size() < 2 || normalizedTerms.size() > 8)) {
            throw invalidRules();
        }

        SemanticSkillTriggerRuleDTO normalized = new SemanticSkillTriggerRuleDTO();
        normalized.setType(type);
        normalized.setTerms(normalizedTerms);
        return normalized;
    }

    private static RenException invalidRules() {
        return new RenException("技能触发规则无效");
    }
}

package xiaozhi.modules.agent.skill.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.skill.dto.SemanticSkillTriggerRuleDTO;

class SemanticSkillRuleNormalizerTest {

    @Test
    void normalizesAndDeduplicatesEquivalentKeywordRules() {
        SemanticSkillTriggerRuleDTO first = rule(" ALL_KEYWORDS ", " 惠易公司 ", "介绍");
        SemanticSkillTriggerRuleDTO duplicateWithDifferentOrder = rule("all_keywords", "介绍", "惠易公司");
        SemanticSkillTriggerRuleDTO phrase = rule("phrase", " 介绍   一号点位 ");

        List<SemanticSkillTriggerRuleDTO> normalized = SemanticSkillRuleNormalizer.normalize(
                List.of(first, duplicateWithDifferentOrder, phrase));

        assertEquals(2, normalized.size());
        assertEquals("all_keywords", normalized.get(0).getType());
        assertEquals(List.of("惠易公司", "介绍"), normalized.get(0).getTerms());
        assertEquals(List.of("介绍 一号点位"), normalized.get(1).getTerms());
    }

    @Test
    void rejectsKeywordRuleThatCollapsesToOneDistinctTerm() {
        SemanticSkillTriggerRuleDTO duplicateTerms = rule("all_keywords", "惠易公司", "惠易公司");

        assertThrows(RenException.class,
                () -> SemanticSkillRuleNormalizer.normalize(List.of(duplicateTerms)));
    }

    @Test
    void rejectsUnsupportedRuleTypeAndInvalidPhraseCardinality() {
        assertThrows(RenException.class,
                () -> SemanticSkillRuleNormalizer.normalize(List.of(rule("regex", "惠易公司"))));
        assertThrows(RenException.class,
                () -> SemanticSkillRuleNormalizer.normalize(List.of(rule("phrase", "惠易公司", "介绍"))));
    }

    private SemanticSkillTriggerRuleDTO rule(String type, String... terms) {
        SemanticSkillTriggerRuleDTO rule = new SemanticSkillTriggerRuleDTO();
        rule.setType(type);
        rule.setTerms(List.of(terms));
        return rule;
    }
}

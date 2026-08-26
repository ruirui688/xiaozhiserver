package xiaozhi.modules.memory.service.impl.recall;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import xiaozhi.modules.memory.entity.MemoryFactEntity;

/**
 * 面向低资源部署的长期记忆召回策略：只进行有界词法匹配，不调用向量或模型。
 */
public final class MemoryLexicalRecallPolicy {
    public static final int DEFAULT_LIMIT = 8;
    public static final int MAX_LIMIT = 20;
    public static final int CANDIDATE_LIMIT = 200;
    private static final int MAX_QUERY_TERMS = 24;
    private static final int CORE_FALLBACK_LIMIT = 2;
    private static final BigDecimal CORE_IMPORTANCE = new BigDecimal("0.85");
    private static final Set<String> STOP_WORDS = Set.of(
            "用户", "我的", "我想", "我有", "什么", "怎么", "如何", "是否", "可以", "现在", "这个", "那个");

    public RecallResult recall(String query, Integer requestedLimit, List<MemoryFactEntity> candidates) {
        int limit = requestedLimit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, requestedLimit));
        if (candidates == null || candidates.isEmpty()) {
            return new RecallResult(List.of(), 0, 0);
        }
        if (StringUtils.isBlank(query)) {
            return new RecallResult(candidates.stream().limit(limit).toList(), 0, 0);
        }

        Set<String> terms = tokenize(query);
        List<ScoredFact> relevant = candidates.stream()
                .map(fact -> new ScoredFact(fact, score(fact, terms)))
                .filter(item -> item.score > 0F)
                .sorted(Comparator.comparingDouble(ScoredFact::score).reversed()
                        .thenComparing(item -> importance(item.fact), Comparator.reverseOrder()))
                .toList();

        List<MemoryFactEntity> selected = new ArrayList<>();
        relevant.stream().limit(limit).map(ScoredFact::fact).forEach(selected::add);
        int relevantCount = selected.size();
        int remaining = limit - selected.size();
        int coreCount = 0;
        if (remaining > 0) {
            List<MemoryFactEntity> coreFacts = candidates.stream()
                    .filter(this::isCore)
                    .filter(fact -> !selected.contains(fact))
                    .limit(Math.min(remaining, CORE_FALLBACK_LIMIT))
                    .toList();
            selected.addAll(coreFacts);
            coreCount = coreFacts.size();
        }
        return new RecallResult(List.copyOf(selected), relevantCount, coreCount);
    }

    private float score(MemoryFactEntity fact, Set<String> terms) {
        if (terms.isEmpty()) {
            return 0F;
        }
        String content = normalize(StringUtils.defaultString(fact.getCategory()) + " "
                + StringUtils.defaultString(fact.getCanonicalText()));
        float hitWeight = 0F;
        float totalWeight = 0F;
        int hitCount = 0;
        for (String term : terms) {
            float weight = term.length() >= 4 ? 4F : term.length() == 3 ? 2F : 1F;
            totalWeight += weight;
            if (content.contains(term)) {
                hitWeight += weight;
                hitCount++;
            }
        }
        if (hitCount == 0 || totalWeight <= 0F) {
            return 0F;
        }
        float lexical = hitWeight / totalWeight;
        float importanceBoost = Math.min(0.15F, importance(fact).floatValue() * 0.15F);
        return Math.min(1F, lexical + importanceBoost);
    }

    private Set<String> tokenize(String query) {
        Set<String> terms = new LinkedHashSet<>();
        String normalized = normalize(query);
        for (String raw : normalized.split("[\\s,，。！？；;:：、]+")) {
            String term = StringUtils.trimToEmpty(raw);
            if (StringUtils.isBlank(term) || STOP_WORDS.contains(term)) {
                continue;
            }
            if (!containsCjk(term) || term.length() <= 12) {
                addTerm(terms, term);
            }
            if (containsCjk(term)) {
                addCjkNgrams(terms, term, 4, 8);
                addCjkNgrams(terms, term, 3, 8);
                addCjkNgrams(terms, term, 2, 8);
            }
            if (terms.size() >= MAX_QUERY_TERMS) {
                break;
            }
        }
        return terms;
    }

    private void addCjkNgrams(Set<String> terms, String text, int size, int maxGrams) {
        if (text.length() < size) {
            return;
        }
        int windows = text.length() - size + 1;
        int step = Math.max(1, (int) Math.ceil((double) windows / maxGrams));
        int added = 0;
        for (int i = 0; i < windows && terms.size() < MAX_QUERY_TERMS && added < maxGrams; i += step) {
            String gram = text.substring(i, i + size);
            if (!STOP_WORDS.contains(gram)) {
                addTerm(terms, gram);
                added++;
            }
        }
        if (terms.size() < MAX_QUERY_TERMS && added < maxGrams) {
            addTerm(terms, text.substring(text.length() - size));
        }
    }

    private void addTerm(Set<String> terms, String term) {
        if (terms.size() < MAX_QUERY_TERMS && !STOP_WORDS.contains(term)) {
            terms.add(term);
        }
    }

    private boolean isCore(MemoryFactEntity fact) {
        return "long_term".equalsIgnoreCase(fact.getMemoryTier())
                || importance(fact).compareTo(CORE_IMPORTANCE) >= 0;
    }

    private BigDecimal importance(MemoryFactEntity fact) {
        return fact.getImportance() == null ? BigDecimal.ZERO : fact.getImportance();
    }

    private boolean containsCjk(String text) {
        return text.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private String normalize(String text) {
        return Normalizer.normalize(StringUtils.defaultString(text), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    public record RecallResult(List<MemoryFactEntity> facts, int relevantCount, int coreCount) {
    }

    private record ScoredFact(MemoryFactEntity fact, float score) {
    }
}

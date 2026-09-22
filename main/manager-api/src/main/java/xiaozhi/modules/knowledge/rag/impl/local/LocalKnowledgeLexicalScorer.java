package xiaozhi.modules.knowledge.rag.impl.local;

import java.text.Normalizer;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import xiaozhi.modules.knowledge.entity.KnowledgeChunkEntity;

/**
 * 本地知识库的确定性词法召回策略。不调用向量服务或大模型。
 */
public final class LocalKnowledgeLexicalScorer {
    private static final int MAX_TERMS = 12;
    /**
     * 疑问词与口语填充词不承担主题信息，词法打分和 Lucene 词项匹配都必须跳过。
     *
     * <p>中文按二字切词，“有什么沙拉”会切出“有什/什么/么沙/沙拉”。如果不过滤疑问词，
     * 一条只命中“有什么/是什么”的切片会靠两个常见词把分数叠上去，压过真正命中
     * “沙拉”这条罕见主题词的切片——用户看到的就是“库里明明有，却查不到”。
     */
    static final Set<String> WEAK_CJK_TERMS = Set.of(
            "什么", "有什", "哪些", "有哪", "哪个", "怎么", "么样", "多少", "推荐", "可以",
            "一个", "一下", "这个", "那个", "现在", "适合");

    private final int matchLevel;

    public LocalKnowledgeLexicalScorer(int matchLevel) {
        this.matchLevel = Math.max(1, Math.min(5, matchLevel));
    }

    public Set<String> tokenize(String text) {
        Set<String> terms = new LinkedHashSet<>();
        String normalized = normalize(text);
        for (String rawTerm : normalized.split("[\\s,，。！？；;:：、]+")) {
            String term = StringUtils.trimToEmpty(rawTerm);
            if (StringUtils.isBlank(term)) {
                continue;
            }
            if (!containsCjk(term) || term.length() <= 12) {
                addTerm(terms, term);
            }
            if (containsCjk(term) && term.length() > 2) {
                addCjkNgrams(terms, term, 4, 4);
                if (matchLevel >= 2) {
                    addCjkNgrams(terms, term, 3, 4);
                }
                if (matchLevel >= 4) {
                    addCjkNgrams(terms, term, 2, 4);
                }
            }
            if (terms.size() >= MAX_TERMS) {
                break;
            }
        }
        return terms;
    }

    public List<String> selectCandidateTerms(Set<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return Collections.emptyList();
        }
        return terms.stream()
                .filter(StringUtils::isNotBlank)
                .filter(term -> term.length() >= 2 || containsNonCjk(term))
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .limit(MAX_TERMS)
                .toList();
    }

    public String buildFulltextQuery(List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return null;
        }
        String query = terms.stream()
                .filter(term -> term.length() >= 2)
                .map(term -> term.replaceAll("[+\\-<>()~*\"@]+", ""))
                .filter(StringUtils::isNotBlank)
                .limit(MAX_TERMS)
                .reduce((left, right) -> left + " " + right)
                .orElse("");
        return StringUtils.trimToNull(query);
    }

    public float score(String question, KnowledgeChunkEntity chunk, Set<String> terms) {
        String content = normalize(String.join("\n",
                StringUtils.defaultString(chunk.getContent()),
                StringUtils.defaultString(chunk.getQuestion()),
                StringUtils.defaultString(chunk.getAnswer())));
        String normalizedQuestion = normalize(question);
        if (StringUtils.isBlank(normalizedQuestion)) {
            return 1F;
        }
        if (content.contains(normalizedQuestion)) {
            return 1F;
        }
        if (terms == null || terms.isEmpty()) {
            return 0F;
        }
        float totalWeight = 0F;
        float hitWeight = 0F;
        int hitCount = 0;
        boolean strongHit = false;
        for (String term : terms) {
            float weight = termWeight(term);
            totalWeight += weight;
            if (content.contains(term)) {
                hitWeight += weight;
                hitCount++;
                strongHit |= term.length() >= 3 || containsNonCjk(term);
            }
        }
        if (hitCount == 0 || totalWeight <= 0F) {
            return 0F;
        }
        float result = hitWeight / totalWeight;
        if (strongHit) {
            result = Math.max(result, 0.35F);
        } else if (hitCount < 2 || result < 0.18F) {
            return 0F;
        }
        return Math.min(result, 0.99F);
    }

    private void addCjkNgrams(Set<String> terms, String text, int size, int maxGrams) {
        if (text.length() < size) {
            return;
        }
        int windows = text.length() - size + 1;
        int step = Math.max(1, (int) Math.ceil((double) windows / maxGrams));
        int added = 0;
        for (int i = 0; i < windows && terms.size() < MAX_TERMS && added < maxGrams; i += step) {
            String gram = text.substring(i, i + size);
            if (containsCjk(gram) && !WEAK_CJK_TERMS.contains(gram)) {
                addTerm(terms, gram);
                added++;
            }
        }
        if (terms.size() < MAX_TERMS && added < maxGrams) {
            addTerm(terms, text.substring(text.length() - size));
        }
    }

    private void addTerm(Set<String> terms, String term) {
        if (terms.size() < MAX_TERMS && !WEAK_CJK_TERMS.contains(term)) {
            terms.add(term);
        }
    }

    private float termWeight(String term) {
        return term.length() >= 4 ? 4F : term.length() == 3 ? 2F : 1F;
    }

    private String normalize(String text) {
        return Normalizer.normalize(StringUtils.defaultString(text), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    private boolean containsCjk(String text) {
        return text.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private boolean containsNonCjk(String text) {
        return text.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN);
    }
}

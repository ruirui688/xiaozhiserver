package xiaozhi.modules.knowledge.rag.impl.local;

import java.text.Normalizer;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import xiaozhi.modules.knowledge.entity.KnowledgeChunkEntity;

/**
 * 从文档自身的结构化切片里抽取“文档级词表”。
 *
 * <p>用户问的是品类（“有什么酒 / 有什么吃的”），而表格里记的是列头与取值
 * （“酒水名称：雪碧 …… 品类：软饮 / 洋酒 / 葡萄酒”“餐品名称：M3西冷牛扒 …… 分类：主食”）。
 * 逐行关键词匹配对不上这类问法：单行正文里往往既没有“酒”也没有“推荐”。因此把
 * 列头里的主题词（酒水名称 → 酒水）和品类取值（品类：洋酒 → 洋酒）汇总成文档词表，
 * 命中词表的切片按“整篇文档相关”加权，而不是只靠单行正文。
 *
 * <p>词表完全从切片内容推导，不需要人工为每个文档写介绍。文档名不参与词表，
 * 避免品牌词（库名）被重复放大。通用列头（售价、规格、备注等）也不参与，
 * 否则任何价格类提问都会把整张库存表拉进结果。
 */
final class LocalKnowledgeDocumentVocabulary {
    private static final Pattern LABEL_PATTERN = Pattern.compile(
            "(?:^|[\\n;；])\\s*([^\\n;；：:=，,]{1,12}?)\\s*[：:=]");
    private static final Pattern NAME_LABEL_PATTERN = Pattern.compile("^(.{1,10}?)(?:名称|品名)$");
    private static final Set<String> CATEGORY_LABELS = Set.of(
            "品类", "分类", "类别", "栏目", "类型", "套餐类型", "餐品分类", "酒水分类", "业态分类");
    private static final int MAX_TOKENS = 80;
    private static final int MAX_TOKEN_LENGTH = 12;

    private LocalKnowledgeDocumentVocabulary() {
    }

    static Set<String> tokens(Collection<KnowledgeChunkEntity> chunks) {
        Set<String> tokens = new LinkedHashSet<>();
        if (chunks == null) {
            return tokens;
        }
        for (KnowledgeChunkEntity chunk : chunks) {
            if (chunk == null || tokens.size() >= MAX_TOKENS) {
                break;
            }
            collect(StringUtils.defaultString(chunk.getContent()), tokens);
        }
        return tokens;
    }

    private static void collect(String content, Set<String> tokens) {
        Matcher matcher = LABEL_PATTERN.matcher(content);
        while (matcher.find() && tokens.size() < MAX_TOKENS) {
            String label = normalize(matcher.group(1));
            if (StringUtils.isBlank(label)) {
                continue;
            }
            Matcher nameLabel = NAME_LABEL_PATTERN.matcher(label);
            if (nameLabel.matches()) {
                add(tokens, nameLabel.group(1));
            }
            if (CATEGORY_LABELS.contains(label)) {
                for (String value : categoryValues(content, matcher.end())) {
                    add(tokens, value);
                }
            }
        }
    }

    private static Set<String> categoryValues(String content, int from) {
        Set<String> values = new LinkedHashSet<>();
        if (from >= content.length()) {
            return values;
        }
        int end = content.length();
        for (int index = from; index < content.length(); index++) {
            char current = content.charAt(index);
            if (current == '\n' || current == '；' || current == ';'
                    || current == '，' || current == ',') {
                end = index;
                break;
            }
        }
        for (String part : content.substring(from, end).split("[/、／|｜]")) {
            values.add(part);
        }
        return values;
    }

    private static void add(Set<String> tokens, String value) {
        String token = normalize(value);
        if (StringUtils.isBlank(token) || token.length() > MAX_TOKEN_LENGTH) {
            return;
        }
        if (token.length() < 2) {
            return;
        }
        boolean hasHan = token.codePoints()
                .anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
        if (!hasHan && token.length() < 3) {
            return;
        }
        if (tokens.size() < MAX_TOKENS) {
            tokens.add(token);
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(StringUtils.defaultString(value), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .trim();
    }
}

package xiaozhi.modules.memory.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import cn.hutool.json.JSONUtil;

/**
 * 记忆相似度工具类。
 * <p>
 * 提供余弦相似度计算、嵌入向量解析/编码、以及混合语义等价判断。
 * 所有方法均为纯 Java 实现，无外部依赖。
 * </p>
 */
public final class MemorySimilarityUtils {

    /** 向量余弦相似度阈值：高于此值认为两条记忆语义等价 */
    private static final double VECTOR_SIMILARITY_THRESHOLD = 0.85;

    /** Jaccard 3-gram 重叠阈值：向量不可用时使用的纯文本回退 */
    private static final double JACCARD_THRESHOLD = 0.80;

    private MemorySimilarityUtils() {
        // 工具类，禁止实例化
    }

    // ===================== 余弦相似度 =====================

    /**
     * 计算两个向量的余弦相似度。
     *
     * @param a 向量 A
     * @param b 向量 B
     * @return 余弦相似度 [-1, 1]，参数无效时返回 0
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0.0 ? 0.0 : dot / denom;
    }

    // ===================== 向量编解码 =====================

    /**
     * 将嵌入向量编码为 JSON 字符串存储。
     *
     * @param vector 浮点向量
     * @return JSON 数组字符串，例如 "[0.12, -0.34, 0.56]"
     */
    public static String encodeEmbedding(float[] vector) {
        if (vector == null || vector.length == 0) {
            return null;
        }
        return JSONUtil.toJsonStr(Arrays.asList(toDoubleArray(vector)));
    }

    /**
     * 将 JSON 字符串解析为嵌入向量。
     *
     * @param embeddingJson JSON 数组字符串
     * @return 浮点向量，解析失败返回 null
     */
    public static float[] parseEmbedding(String embeddingJson) {
        if (StringUtils.isBlank(embeddingJson)) {
            return null;
        }
        try {
            double[] doubles = JSONUtil.toBean(embeddingJson, double[].class);
            float[] result = new float[doubles.length];
            for (int i = 0; i < doubles.length; i++) {
                result[i] = (float) doubles[i];
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    // ===================== 混合语义等价判断 =====================

    /**
     * 混合语义等价判断：字符串包含 + 向量余弦相似度 + Jaccard 回退。
     *
     * @param text1   文本 1（已规范化的 canonical text）
     * @param text2   文本 2
     * @param emb1    文本 1 的嵌入向量（可为 null）
     * @param emb2    文本 2 的嵌入向量（可为 null）
     * @return true 如果两条记忆在语义上等价
     */
    public static boolean isSemanticallyEquivalent(String text1, String text2,
                                                    float[] emb1, float[] emb2) {
        if (StringUtils.isBlank(text1) || StringUtils.isBlank(text2)) {
            return false;
        }

        // 第一层：字符串包含判断（快速路径）
        String t1 = normalizeForCompare(text1);
        String t2 = normalizeForCompare(text2);
        if (t1.equals(t2) || t1.contains(t2) || t2.contains(t1)) {
            return true;
        }

        // 第二层：向量余弦相似度（最高精度）
        if (emb1 != null && emb2 != null) {
            double sim = cosineSimilarity(emb1, emb2);
            if (sim >= VECTOR_SIMILARITY_THRESHOLD) {
                return true;
            }
        }

        // 第三层：Jaccard 3-gram 重叠（无向量时的回退）
        double jaccard = jaccard3Gram(t1, t2);
        return jaccard >= JACCARD_THRESHOLD;
    }

    // ===================== Jaccard 3-gram =====================

    /**
     * 计算两个字符串的 Jaccard 3-gram 相似度。
     */
    static double jaccard3Gram(String a, String b) {
        if (a.length() < 3 && b.length() < 3) {
            return a.equals(b) ? 1.0 : 0.0;
        }
        Set<String> gramsA = build3Grams(a);
        Set<String> gramsB = build3Grams(b);
        if (gramsA.isEmpty() && gramsB.isEmpty()) {
            return 1.0;
        }
        Set<String> intersection = new HashSet<>(gramsA);
        intersection.retainAll(gramsB);
        Set<String> union = new HashSet<>(gramsA);
        union.addAll(gramsB);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private static Set<String> build3Grams(String text) {
        Set<String> grams = new HashSet<>();
        String s = text.replaceAll("\\s+", "");
        for (int i = 0; i + 3 <= s.length(); i++) {
            grams.add(s.substring(i, i + 3));
        }
        // 短文本：用整个文本作为一个 gram
        if (grams.isEmpty() && !s.isEmpty()) {
            grams.add(s);
        }
        return grams;
    }

    // ===================== 文本规范化 =====================

    /**
     * 规范化文本用于比较：去除空格、标点、常见代词前缀。
     */
    public static String normalizeForCompare(String text) {
        return StringUtils.defaultString(text)
                .replaceAll("[\\s，。！？、；：,.!?;:\"'“”'()（）《》<>\\[\\]【】]", "")
                .replace("用户的", "")
                .replace("用户", "")
                .replace("本人", "")
                .trim();
    }

    // ===================== 多维度重要性 =====================

    /**
     * 从维度 Map 计算加权复合重要性得分。
     * 权重分配：relevance 0.25, novelty 0.20, emotional_weight 0.15,
     * factual_density 0.15, actionability 0.10, personal_nature 0.15
     */
    public static double compositeImportance(java.util.Map<String, Double> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return 0.5;
        }
        double score = 0.0;
        score += 0.25 * getDim(dimensions, "relevance");
        score += 0.20 * getDim(dimensions, "novelty");
        score += 0.15 * getDim(dimensions, "emotional_weight");
        score += 0.15 * getDim(dimensions, "factual_density");
        score += 0.10 * getDim(dimensions, "actionability");
        score += 0.15 * getDim(dimensions, "personal_nature");
        return Math.max(0.0, Math.min(1.0, score));
    }

    private static double getDim(java.util.Map<String, Double> dims, String key) {
        Double v = dims.get(key);
        return v == null ? 0.5 : Math.max(0.0, Math.min(1.0, v));
    }

    // ===================== 辅助 =====================

    private static Double[] toDoubleArray(float[] arr) {
        Double[] result = new Double[arr.length];
        for (int i = 0; i < arr.length; i++) {
            result[i] = (double) arr[i];
        }
        return result;
    }
}

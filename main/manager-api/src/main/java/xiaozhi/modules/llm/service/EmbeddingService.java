package xiaozhi.modules.llm.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheLoader;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.llm.LlmClient;
import xiaozhi.common.llm.LlmException;

/**
 * 文本嵌入向量服务。
 * <p>
 * 包装 OpenAI 兼容的 embeddings API 调用，提供 LRU 内存缓存。
 * 嵌入向量存储为 float[] 以减少内存占用。
 * 调用失败时返回 null，由上层降级到字符串比较。
 * </p>
 */
@Slf4j
@Component
public class EmbeddingService {

    private final LlmClient llmClient;

    /** LRU 缓存：最多 500 条嵌入，30 分钟过期 */
    private final Cache<String, float[]> cache;

    public EmbeddingService(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.cache = CacheBuilder.newBuilder()
                .maximumSize(500)
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .build();
    }

    /**
     * 通过模型配置 ID 获取文本嵌入向量（带缓存）。
     *
     * @param text    待向量化的文本
     * @param baseUrl API 基础地址（例如 https://api.deepseek.com/v1）
     * @param apiKey  API 密钥
     * @param modelName 嵌入模型名称（例如 text-embedding-3-small）
     * @return 嵌入向量，失败时返回 null
     */
    public float[] getEmbedding(String text, String baseUrl, String apiKey, String modelName) {
        if (StringUtils.isBlank(text) || StringUtils.isBlank(baseUrl)
                || StringUtils.isBlank(apiKey) || StringUtils.isBlank(modelName)) {
            return null;
        }

        String cacheKey = buildCacheKey(baseUrl, modelName, text);
        try {
            return cache.get(cacheKey, () -> fetchEmbedding(text, baseUrl, apiKey, modelName));
        } catch (ExecutionException e) {
            log.warn("Embedding 缓存加载失败: {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("Embedding 获取异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 API 获取嵌入向量（不经过缓存）。
     */
    private float[] fetchEmbedding(String text, String baseUrl, String apiKey, String modelName) {
        String endpoint = buildEmbeddingEndpoint(baseUrl);
        try {
            List<Double> vector = llmClient.embedding(endpoint, apiKey, modelName, text);
            if (vector == null || vector.isEmpty()) {
                return null;
            }
            float[] result = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                result[i] = vector.get(i).floatValue();
            }
            return result;
        } catch (LlmException e) {
            log.warn("Embedding API 调用失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 拼接 embeddings 端点 URL。
     * 例如 baseUrl=https://api.deepseek.com/v1 → endpoint=https://api.deepseek.com/v1/embeddings
     */
    private String buildEmbeddingEndpoint(String baseUrl) {
        String normalized = StringUtils.removeEnd(baseUrl.trim(), "/");
        if (normalized.endsWith("/v1")) {
            return normalized + "/embeddings";
        }
        // 如果 baseUrl 已经包含了完整路径，替换末尾的 chat/completions
        if (normalized.contains("/chat/completions")) {
            return normalized.replace("/chat/completions", "/embeddings");
        }
        return normalized + "/embeddings";
    }

    /**
     * 构建缓存键：模型配置哈希 + 文本哈希。
     */
    private String buildCacheKey(String baseUrl, String modelName, String text) {
        String input = baseUrl + "|" + modelName + "|" + text;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}

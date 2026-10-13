package xiaozhi.modules.knowledge.rag.impl.local;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.knowledge.dto.document.RetrievalDTO;

/** One policy for management tests and conversation retrieval; limits do not select ranking rules. */
public record LocalKnowledgeRetrievalPolicy(int matchLevel, int resultLimit, int candidateLimit, float threshold) {
    public static LocalKnowledgeRetrievalPolicy defaults() {
        return new LocalKnowledgeRetrievalPolicy(4, 10, 200, 0.05F);
    }

    public static LocalKnowledgeRetrievalPolicy from(Map<String, Object> config, ObjectMapper mapper) {
        Map<String, Object> root = config == null ? Map.of() : config;
        var parser = new LocalKnowledgeConfigParser(root, mapper);
        Map<String, Object> nested = parser.getMap("retrieval_config");
        Map<String, Object> effective = new HashMap<>(root);
        effective.putAll(nested);
        // Dataset settings win over model defaults, including the legacy semantic_* aliases.
        for (String suffix : new String[]{"level", "top_k", "threshold"}) {
            String current = "keyword_" + suffix;
            String legacy = "semantic_" + suffix;
            Object value = nested.get(current);
            if (value == null) value = nested.get(legacy);
            if (value == null) value = root.get(current);
            if (value == null) value = root.get(legacy);
            if (value != null) effective.put(current, value);
        }
        int candidates = "exhibition".equalsIgnoreCase(parser.getString(effective, "mode", "normal")) ? 250 : 200;
        return new LocalKnowledgeRetrievalPolicy(
                parser.getInt(effective, "keyword_level", 4, 1, 5),
                parser.getInt(effective, "keyword_top_k", 10, 1, 50),
                parser.getInt(effective, "candidate_limit", candidates, 100, 800),
                parser.getFloat(effective, "keyword_threshold", 0.05F, 0F, 1F));
    }

    public LocalKnowledgeRetrievalEngine.Options resolve(RetrievalDTO.TestReq request) {
        int limit = positive(request.getTopK())
                ? LocalKnowledgeConfigParser.clamp(request.getTopK(), 1, 50) : resultLimit;
        // Only an explicitly supplied page size limits results; absent does not mean another cap of 10.
        if (positive(request.getPageSize())) limit = Math.min(limit, request.getPageSize());
        float cutoff = request.getSimilarityThreshold() != null && Float.isFinite(request.getSimilarityThreshold())
                ? request.getSimilarityThreshold() : threshold;
        return new LocalKnowledgeRetrievalEngine.Options(matchLevel, limit, candidateLimit, cutoff);
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }
}

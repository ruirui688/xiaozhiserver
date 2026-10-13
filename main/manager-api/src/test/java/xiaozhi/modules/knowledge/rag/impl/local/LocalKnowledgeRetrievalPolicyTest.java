package xiaozhi.modules.knowledge.rag.impl.local;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.knowledge.dto.document.RetrievalDTO;
import xiaozhi.modules.knowledge.rag.impl.LocalKnowledgeAdapter;

class LocalKnowledgeRetrievalPolicyTest {
    private LocalKnowledgeRetrievalPolicy policy(Map<String, Object> settings) {
        return LocalKnowledgeRetrievalPolicy.from(settings, new ObjectMapper());
    }

    @Test
    void configuredTwentyResultsAreNotSilentlyCappedToTen() {
        var policy = policy(Map.of("retrieval_config", Map.of("keyword_top_k", 20)));
        var engine = mock(LocalKnowledgeRetrievalEngine.class);
        var adapter = new LocalKnowledgeAdapter();
        ReflectionTestUtils.setField(adapter, "retrievalEngine", engine);
        ReflectionTestUtils.setField(adapter, "retrievalPolicy", policy);
        var request = RetrievalDTO.TestReq.builder().question("可乐").build();
        adapter.retrievalTest(request);
        verify(engine).retrieve(eq(request), eq(new LocalKnowledgeRetrievalEngine.Options(4, 20, 200, 0.05F)));
    }

    @Test
    void nestedLegacySettingsOverrideRootModelDefaultsAndNewNamesWinInSameLayer() {
        var settings = policy(Map.of("keyword_top_k", 5, "keyword_threshold", 0.9,
                "retrieval_config", "{\"semantic_top_k\":25,\"keyword_top_k\":30,\"semantic_threshold\":0.1}"));
        assertEquals(30, settings.resultLimit());
        assertEquals(0.1F, settings.threshold());
        assertEquals(25, policy(Map.of("keyword_top_k", 5,
                "retrieval_config", Map.of("semantic_top_k", 25))).resultLimit());
        assertEquals(25, policy(Map.of("semantic_top_k", 25)).resultLimit());
    }

    @Test
    void requestLimitsDoNotChangeCandidateSearchAndThresholdOverrideIsHonored() {
        var settings = policy(Map.of("retrieval_config", Map.of("candidate_limit", 350)));
        var small = settings.resolve(RetrievalDTO.TestReq.builder().topK(5).build());
        var large = settings.resolve(RetrievalDTO.TestReq.builder().topK(40).build());
        assertEquals(350, small.candidateLimit());
        assertEquals(small.candidateLimit(), large.candidateLimit());
        var paged = settings.resolve(RetrievalDTO.TestReq.builder().topK(40).pageSize(12).similarityThreshold(1F).build());
        assertEquals(12, paged.resultLimit());
        assertEquals(1F, paged.threshold());
    }

    @Test
    void malformedAndUnboundedNumbersCannotDisableFilteringOrExpandSearchWithoutLimit() {
        assertEquals(LocalKnowledgeRetrievalPolicy.defaults(), policy(Map.of("retrieval_config", "null")));
        var settings = policy(Map.of("retrieval_config", Map.of(
                "keyword_threshold", "NaN", "keyword_top_k", 999, "candidate_limit", 999999)));
        assertEquals(0.05F, settings.threshold());
        assertEquals(50, settings.resultLimit());
        assertEquals(800, settings.candidateLimit());
        assertEquals(0.05F, settings.resolve(RetrievalDTO.TestReq.builder().similarityThreshold(Float.NaN).build()).threshold());
        assertEquals(0.05F, new LocalKnowledgeRetrievalEngine.Options(4, 10, 200, Float.NaN).threshold());
    }
}

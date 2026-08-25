package xiaozhi.modules.knowledge.rag.impl.local;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.modules.knowledge.dao.DocumentDao;
import xiaozhi.modules.knowledge.dao.KnowledgeChunkDao;
import xiaozhi.modules.knowledge.entity.DocumentEntity;
import xiaozhi.modules.knowledge.rag.impl.LocalKnowledgeAdapter;
import xiaozhi.common.exception.RenException;

class LocalKnowledgeAdapterContractTest {
    @Test
    void reportsSynchronousDocumentParsing() {
        assertTrue(new LocalKnowledgeAdapter().isDocumentParsingSynchronous());
    }

    @Test
    void keepsLlmBudgetsServerManaged() {
        LocalKnowledgeAdapter adapter = new LocalKnowledgeAdapter();

        assertFalse(adapter.getSupportedConfig().containsKey("llm_max_input_chars"));
        assertFalse(adapter.getSupportedConfig().containsKey("llm_max_tokens"));
        assertFalse(adapter.getDefaultConfig().containsKey("llm_max_input_chars"));
        assertFalse(adapter.getDefaultConfig().containsKey("llm_max_tokens"));
    }

    @Test
    void acceptsParseOnlyWhenUploadAlreadyProducedChunks() {
        LocalKnowledgeAdapter adapter = new LocalKnowledgeAdapter();
        DocumentDao documentDao = mock(DocumentDao.class);
        KnowledgeChunkDao chunkDao = mock(KnowledgeChunkDao.class);
        when(documentDao.selectOne(any())).thenReturn(new DocumentEntity());
        when(chunkDao.selectCount(any())).thenReturn(2L);
        ReflectionTestUtils.setField(adapter, "documentDao", documentDao);
        ReflectionTestUtils.setField(adapter, "chunkDao", chunkDao);

        assertTrue(adapter.parseDocuments("dataset-1", List.of("document-1")));
    }

    @Test
    void rejectsParseWhenUploadDidNotProduceChunks() {
        LocalKnowledgeAdapter adapter = new LocalKnowledgeAdapter();
        DocumentDao documentDao = mock(DocumentDao.class);
        KnowledgeChunkDao chunkDao = mock(KnowledgeChunkDao.class);
        when(documentDao.selectOne(any())).thenReturn(new DocumentEntity());
        when(chunkDao.selectCount(any())).thenReturn(0L);
        ReflectionTestUtils.setField(adapter, "documentDao", documentDao);
        ReflectionTestUtils.setField(adapter, "chunkDao", chunkDao);

        RenException error = assertThrows(RenException.class,
                () -> adapter.parseDocuments("dataset-1", List.of("document-1")));
        assertTrue(error.getMessage().contains("请重新上传原文件"));
    }

    @Test
    void rejectsSemanticChunkingWithoutManagedLlmInsteadOfFallingBackToRules() {
        LocalKnowledgeAdapter adapter = new LocalKnowledgeAdapter();
        ReflectionTestUtils.setField(adapter, "llmModelId", "");

        RenException error = assertThrows(RenException.class,
                () -> ReflectionTestUtils.invokeMethod(adapter, "chunkDocumentWithLlm", "manual.txt", "正文内容"));

        assertTrue(error.getMessage().contains("必须配置大模型语义切片模型"));
    }

    @Test
    void failsTheWholeSemanticChunkingRequestWhenTheModelCallFails() {
        LocalKnowledgeAdapter adapter = new LocalKnowledgeAdapter();
        RestTemplate restTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(adapter, "llmModelId", "llm-1");
        ReflectionTestUtils.setField(adapter, "llmBaseUrl", "https://example.invalid/v1");
        ReflectionTestUtils.setField(adapter, "llmModel", "semantic-chunker");
        ReflectionTestUtils.setField(adapter, "llmApiKey", "");
        ReflectionTestUtils.setField(adapter, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(adapter, "llmRestTemplate", restTemplate);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new IllegalStateException("upstream unavailable"));

        RenException error = assertThrows(RenException.class,
                () -> ReflectionTestUtils.invokeMethod(adapter, "chunkDocumentWithLlm", "manual.txt", "正文内容"));

        assertTrue(error.getMessage().contains("未生成规则切片"));
    }

    @Test
    void rejectsModelChunksThatDoNotExplicitlyProvideContent() {
        LocalKnowledgeAdapter adapter = new LocalKnowledgeAdapter();
        ReflectionTestUtils.setField(adapter, "objectMapper", new ObjectMapper());

        RenException error = assertThrows(RenException.class,
                () -> ReflectionTestUtils.invokeMethod(adapter, "parseChunkedDocument",
                        "[{\"question\":\"问题\",\"answer\":\"答案\"}]"));

        assertTrue(error.getMessage().contains("question、answer、content字符串字段"));
    }

    @Test
    void forwardsDeepSeekThinkingDisabledForSemanticChunking() {
        LocalKnowledgeAdapter adapter = new LocalKnowledgeAdapter();
        RestTemplate restTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(adapter, "llmBaseUrl", "https://api.deepseek.com");
        ReflectionTestUtils.setField(adapter, "llmModel", "deepseek-v4-flash");
        ReflectionTestUtils.setField(adapter, "llmThinking", "disabled");
        ReflectionTestUtils.setField(adapter, "llmReasoningEffort", "high");
        ReflectionTestUtils.setField(adapter, "llmMaxTokens", 4000);
        ReflectionTestUtils.setField(adapter, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(adapter, "llmRestTemplate", restTemplate);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"choices\":[{\"message\":{\"content\":\"[{\\\"question\\\":\\\"q\\\",\\\"answer\\\":\\\"a\\\",\\\"content\\\":\\\"c\\\"}]\"}}]}"));

        ReflectionTestUtils.invokeMethod(adapter, "chunkSectionWithLlm", "manual.txt", "正文", 1, 1);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        org.mockito.Mockito.verify(restTemplate).exchange(
                anyString(), eq(HttpMethod.POST), entityCaptor.capture(), eq(String.class));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> body = (java.util.Map<String, Object>) entityCaptor.getValue().getBody();
        assertTrue(java.util.Map.of("type", "disabled").equals(body.get("thinking")));
        assertTrue(java.util.Map.of("type", "json_object").equals(body.get("response_format")));
        assertTrue(body.containsKey("temperature"));
        assertTrue(!body.containsKey("reasoning_effort"));
    }

    @Test
    void acceptsJsonObjectChunkEnvelopeRequiredByDeepSeekJsonMode() {
        LocalKnowledgeAdapter adapter = new LocalKnowledgeAdapter();
        ReflectionTestUtils.setField(adapter, "objectMapper", new ObjectMapper());

        @SuppressWarnings("unchecked")
        List<Object> chunks = ReflectionTestUtils.invokeMethod(adapter, "parseChunkedDocument",
                "{\"chunks\":[{\"question\":\"问题\",\"answer\":\"答案\",\"content\":\"正文\"}]}");

        assertTrue(chunks != null && chunks.size() == 1);
    }

    @Test
    void retriesTruncatedJsonWithSmallerInputSections() {
        LocalKnowledgeAdapter adapter = new LocalKnowledgeAdapter();
        RestTemplate restTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(adapter, "llmModelId", "llm-1");
        ReflectionTestUtils.setField(adapter, "llmBaseUrl", "https://api.deepseek.com");
        ReflectionTestUtils.setField(adapter, "llmModel", "deepseek-v4-flash");
        ReflectionTestUtils.setField(adapter, "llmApiKey", "test-key");
        ReflectionTestUtils.setField(adapter, "llmThinking", "disabled");
        ReflectionTestUtils.setField(adapter, "llmMaxInputChars", 4000);
        ReflectionTestUtils.setField(adapter, "llmMaxTokens", 4000);
        ReflectionTestUtils.setField(adapter, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(adapter, "llmRestTemplate", restTemplate);
        String truncated = "{\"choices\":[{\"message\":{\"content\":\"{\\\"chunks\\\":[{\"}}]}";
        String complete = "{\"choices\":[{\"message\":{\"content\":\"{\\\"chunks\\\":[{\\\"question\\\":\\\"q\\\",\\\"answer\\\":\\\"a\\\",\\\"content\\\":\\\"c\\\"}]}\"}}]}";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(truncated), ResponseEntity.ok(truncated),
                        ResponseEntity.ok(complete), ResponseEntity.ok(complete));

        @SuppressWarnings("unchecked")
        List<Object> chunks = ReflectionTestUtils.invokeMethod(adapter, "chunkDocumentWithLlm",
                "large.xlsx", "正文。".repeat(800));

        assertTrue(chunks != null && chunks.size() == 2);
        org.mockito.Mockito.verify(restTemplate, org.mockito.Mockito.times(4)).exchange(
                anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
    }
}

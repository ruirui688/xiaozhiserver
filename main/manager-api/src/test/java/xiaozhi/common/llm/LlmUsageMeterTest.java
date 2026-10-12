package xiaozhi.common.llm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import xiaozhi.modules.billing.dto.Usage;
import xiaozhi.modules.billing.service.BillingUsageRecorder;
import xiaozhi.modules.billing.service.BillingUsageRecorder.InternalUsage;

class LlmUsageMeterTest {
    private static final String ENDPOINT = "https://api.deepseek.com/chat/completions";

    @Test
    void capturesProviderUsageAndWorkspaceWithoutPersistingText() {
        var recorder = mock(BillingUsageRecorder.class);
        var client = new RestTemplate();
        var server = MockRestServiceServer.createServer(client);
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("""
                {"id":"request-1","choices":[{"message":{"content":"private response"}}],
                 "usage":{"prompt_tokens":30,"completion_tokens":7,"total_tokens":37,
                          "prompt_cache_hit_tokens":20,"prompt_cache_miss_tokens":10}}
                """, MediaType.APPLICATION_JSON));
        var meter = new LlmUsageMeter(recorder, executor());
        try (var ignored = LlmUsageContext.open(
                LlmUsageContext.Identity.agent("workspace-a", "agent-a", "MEMORY"))) {
            meter.exchange(client, ENDPOINT, new HttpEntity<>(Map.of("prompt", "private prompt")),
                    String.class, "model-1", "LLM");
        }
        var event = ArgumentCaptor.forClass(InternalUsage.class);
        verify(recorder).recordInternal(event.capture());
        var value = event.getValue();
        assertEquals("workspace-a", value.getWorkspaceId());
        assertEquals("agent-a", value.getAgentId());
        assertNull(value.getDeviceId());
        assertEquals("MEMORY", value.getPurpose());
        assertEquals("deepseek", value.getProvider());
        assertEquals("request-1", value.getProviderRequestId());
        assertTrue(value.getUsageComplete());
        assertEquals(new BigDecimal("30"), metric(value.getUsage(), "input_tokens"));
        assertEquals(new BigDecimal("20"), metric(value.getUsage(), "cached_input_tokens"));
        assertFalse(value.toString().contains("private"));
        assertNull(LlmUsageContext.current());
        server.verify();
    }

    @Test
    void failedAttemptAndRetryHaveSeparateIdentitiesAndMissingUsageIsUnknown() {
        var recorder = mock(BillingUsageRecorder.class);
        var client = new RestTemplate();
        var server = MockRestServiceServer.createServer(client);
        server.expect(requestTo(ENDPOINT)).andRespond(withServerError());
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));
        var meter = new LlmUsageMeter(recorder, executor());
        assertThrows(RuntimeException.class, () -> meter.exchange(
                client, ENDPOINT, HttpEntity.EMPTY, String.class, "model-1", "LLM"));
        meter.exchange(client, ENDPOINT, HttpEntity.EMPTY, String.class, "model-1", "LLM");
        var event = ArgumentCaptor.forClass(InternalUsage.class);
        verify(recorder, times(2)).recordInternal(event.capture());
        var attempts = event.getAllValues();
        assertNotEquals(attempts.get(0).getEventId(), attempts.get(1).getEventId());
        assertEquals("FAILED", attempts.get(0).getStatus());
        assertEquals("SUCCESS", attempts.get(1).getStatus());
        attempts.forEach(value -> {
            assertFalse(value.getUsageComplete());
            assertTrue(value.getUsage().isEmpty());
            assertNull(value.getWorkspaceId());
        });
        server.verify();
    }

    @Test
    void recorderFailureDoesNotRetryOrFailSuccessfulUpstreamRequest() {
        var recorder = mock(BillingUsageRecorder.class);
        doThrow(new IllegalStateException("storage failure")).when(recorder).recordInternal(any());
        var client = new RestTemplate();
        var server = MockRestServiceServer.createServer(client);
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertTrue(new LlmUsageMeter(recorder, executor()).exchange(client, ENDPOINT,
                HttpEntity.EMPTY, String.class, "model-1", "LLM").getStatusCode().is2xxSuccessful());
        server.verify();
    }

    @Test
    void contextsRestoreOnFailureAndDoNotLeakIntoOtherWorker() throws Exception {
        var outer = LlmUsageContext.Identity.agent("workspace-a", "agent-a", "MEMORY");
        try (var scope = LlmUsageContext.open(outer); var executor = Executors.newSingleThreadExecutor()) {
            assertNull(executor.submit(LlmUsageContext::current).get());
            assertThrows(IllegalStateException.class, () -> LlmUsageContext.call(
                    LlmUsageContext.Identity.platform("test-model", "MODEL_TEST"), () -> {
                        throw new IllegalStateException("failure");
                    }));
            assertEquals(outer, LlmUsageContext.current());
            try (var model = LlmUsageContext.model("embedding-1")) {
                assertEquals("workspace-a", LlmUsageContext.current().workspaceId());
                assertEquals("embedding-1", LlmUsageContext.current().modelId());
            }
            assertEquals(outer, LlmUsageContext.current());
        }
        assertNull(LlmUsageContext.current());
    }

    @Test
    void rejectsInvalidQuantitiesAndKeepsValidZeroDistinctFromMissing() {
        var usage = LlmUsageMeter.parseUsage(Map.of("usage", Map.of(
                "prompt_tokens", 0, "completion_tokens", -3, "total_tokens", "secret-value")), "LLM");
        assertEquals(1, usage.size());
        assertEquals(BigDecimal.ZERO, metric(usage, "input_tokens"));
        assertEquals("other", LlmUsageMeter.provider("https://api.deepseek.com.attacker.invalid/chat"));
    }

    private PlatformJavaExecutor executor() {
        var executor = mock(PlatformJavaExecutor.class);
        when(executor.prepare(any(), any(), any(), any())).thenAnswer(invocation ->
            new PlatformJavaExecutor.Call(ENDPOINT, invocation.getArgument(1), null));
        return executor;
    }

    private BigDecimal metric(List<Usage> usage, String name) {
        return usage.stream().filter(value -> name.equals(value.getMetric()))
                .findFirst().orElseThrow().getQuantity();
    }
}

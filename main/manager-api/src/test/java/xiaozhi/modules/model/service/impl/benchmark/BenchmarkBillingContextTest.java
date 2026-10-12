package xiaozhi.modules.model.service.impl.benchmark;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import cn.hutool.json.JSONObject;
import xiaozhi.common.llm.LlmUsageContext;
import xiaozhi.modules.billing.service.BillingContextService;

class BenchmarkBillingContextTest {
    @Test
    void realDeviceBenchmarkReplacesCustomerScopeAndIncludesJudgeAndRuntimeModels() {
        var service = mock(BillingContextService.class);
        var issued = Map.<String, Object>of("context_id", "platform-context", "purpose", "BENCHMARK");
        when(service.issuePlatform(anyCollection(), eq("BENCHMARK"))).thenAnswer(call -> {
            Collection<String> ids = call.getArgument(0);
            assertEquals(Set.of("candidate", "judge", "runtime-tts"), Set.copyOf(ids));
            return issued;
        });
        var runtime = new JSONObject().set("model_config", Map.of("runtime-tts", Map.of()))
                .set("billing_context", Map.of("workspace_id", "customer-space"));
        var payload = new JSONObject()
                .set("models", List.of(new JSONObject().set("id", "candidate")))
                .set("judge_config", new JSONObject().set("model_id", "judge"))
                .set("agent_context", new JSONObject().set("runtime_config", runtime)
                        .set("device_context", Map.of("device_id", "real-device")));

        new BenchmarkBillingContext(service).attach(payload, "BENCHMARK");

        assertEquals("BENCHMARK", payload.getStr("billing_purpose"));
        var scope = payload.getJSONObject("agent_context").getJSONObject("runtime_config");
        assertEquals("BENCHMARK", scope.getStr("_billing_purpose"));
        assertEquals("platform-context", scope.getJSONObject("billing_context").getStr("context_id"));
        assertFalse(scope.getJSONObject("billing_context").containsKey("workspace_id"));
        verify(service).issuePlatform(anyCollection(), eq("BENCHMARK"));
    }

    @Test
    void singleModelTestUsesPersistedIdentityInsteadOfSyntheticCandidateId() {
        var service = mock(BillingContextService.class);
        when(service.issuePlatform(anyCollection(), eq("MODEL_TEST")))
                .thenReturn(Map.of("context_id", "test-context"));
        var payload = new JSONObject().set("models", List.of(
                new JSONObject().set("id", "single-asr-test")));
        try (var scope = LlmUsageContext.open(LlmUsageContext.Identity.platform("saved-asr", "MODEL_TEST"))) {
            new BenchmarkBillingContext(service).attach(payload, "MODEL_TEST");
        }
        assertEquals("saved-asr", payload.getJSONArray("models").getJSONObject(0).getStr("id"));
        verify(service).issuePlatform(Set.of("saved-asr"), "MODEL_TEST");
        assertNull(LlmUsageContext.current());
    }

    @Test
    void emptyPayloadDoesNotIssueUselessCapability() {
        var service = mock(BillingContextService.class);
        new BenchmarkBillingContext(service).attach(new JSONObject(), "BENCHMARK");
        verifyNoInteractions(service);
    }
}

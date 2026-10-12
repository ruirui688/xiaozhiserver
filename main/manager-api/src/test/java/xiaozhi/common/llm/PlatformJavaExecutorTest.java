package xiaozhi.common.llm;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import xiaozhi.modules.platform.service.*;
import xiaozhi.modules.platform.dto.PlatformRequests.*;
import xiaozhi.common.exception.RenException;

class PlatformJavaExecutorTest {
    @Test void executionUsesAdmittedEndpointModelAndOnlyAdmittedCredential() {
        var admission=mock(PlatformAdmissionService.class);var services=mock(PlatformServiceAccountService.class);var outbox=mock(PlatformSettlementOutbox.class);
        when(admission.admitVerified(any(),any())).thenReturn(Map.of("reservationId","reservation-test","permitToken","permit-test","runtimeConfig",Map.of("base_url","https://api.deepseek.com/v1","model_name","approved-model"),"credentials",Map.of("api_key","fake-admitted-key")));
        when(admission.start(any())).thenReturn(Map.of("started",true));
        var executor=new PlatformJavaExecutor(admission,services,outbox);var headers=new HttpHeaders();headers.setBearerAuth("fake-old-key");headers.set("x-custom-secret","old");
        var call=executor.prepare(new LlmUsageContext.Identity("workspace","agent",null,null,"model","MEMORY",1L),new HttpEntity<>(Map.of("model","old-model","messages",List.of(Map.of("role","user","content","fixture"))),headers),"model","LLM");
        assertEquals("https://api.deepseek.com/v1/chat/completions",call.endpoint());assertEquals("Bearer fake-admitted-key",call.request().getHeaders().getFirst("Authorization"));assertFalse(call.request().getHeaders().containsKey("x-custom-secret"));
        assertEquals("approved-model",((Map<?,?>)call.request().getBody()).get("model"));
        var ordered=inOrder(outbox,admission);ordered.verify(admission).admitVerified(any(),any());ordered.verify(outbox).store(any());ordered.verify(admission).start(any());
    }
    @Test void unsupportedEmbeddingAndMissingIdentityNeverReachAdmissionOrNetwork() {
        var admission=mock(PlatformAdmissionService.class);var executor=new PlatformJavaExecutor(admission,mock(PlatformServiceAccountService.class),mock(PlatformSettlementOutbox.class));
        assertThrows(RenException.class,()->executor.prepare(null,HttpEntity.EMPTY,"model","LLM"));
        assertThrows(RenException.class,()->executor.prepare(LlmUsageContext.Identity.platform("model","MODEL_TEST"),HttpEntity.EMPTY,"model","EMBEDDING"));verifyNoInteractions(admission);
    }
}

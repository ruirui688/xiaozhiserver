package xiaozhi.common.llm;

import static org.mockito.Mockito.*;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

/** Parser/provider tests inject the metering boundary; they never enable production bypasses. */
public final class MeteredHttpTestSupport {
    private MeteredHttpTestSupport() {}
    @SuppressWarnings({"unchecked","rawtypes"})
    public static void install(Object target) {
        LlmUsageMeter meter=mock(LlmUsageMeter.class);
        when(meter.exchange(any(),any(),any(),any(),any(),any())).thenAnswer(call->{
            RestTemplate client=call.getArgument(0);
            if(!mockingDetails(client).isMock())throw new AssertionError("This fixture requires a mocked HTTP client");
            return client.exchange((String)call.getArgument(1),HttpMethod.POST,(HttpEntity<?>)call.getArgument(2),(Class)call.getArgument(3));
        });
        ReflectionTestUtils.setField(target,"usageMeter",meter);
    }
}

package xiaozhi.modules.model.service.impl.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import cn.hutool.json.JSONObject;
import xiaozhi.common.constant.Constant;
import xiaozhi.modules.sys.service.SysParamsService;

class PythonBenchmarkClientTest {

    @Test
    void professionalBenchmarkSendsServerSecretBearerHeader() {
        SysParamsService sysParamsService = mock(SysParamsService.class);
        when(sysParamsService.getValue(Constant.SERVER_SECRET, true)).thenReturn("server-test-secret");
        Environment environment = mock(Environment.class);
        when(environment.getProperty("xiaozhi.server.http-base-url")).thenReturn("http://xiaozhi-server:8003");
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(
                eq("http://xiaozhi-server:8003/benchmark/models"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"results\":[]}"));
        BenchmarkRequestAuth auth = new BenchmarkRequestAuth(sysParamsService);
        PythonBenchmarkClient client = new PythonBenchmarkClient(sysParamsService, environment, auth) {
            @Override
            protected RestTemplate createRestTemplate(JdkClientHttpRequestFactory factory) {
                return restTemplate;
            }
        };

        client.runBenchmark(new JSONObject().set("models", java.util.List.of()));

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("http://xiaozhi-server:8003/benchmark/models"),
                eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(String.class));
        assertEquals("Bearer server-test-secret",
                entityCaptor.getValue().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    }
}

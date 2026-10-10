package xiaozhi.modules.llm.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import com.sun.net.httpserver.HttpServer;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.hutool.json.JSONUtil;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;

class OpenAIStyleLLMServiceImplTest {
    @Test
    void stalledLocalResponseTimesOutWithoutHoldingWorkerIndefinitely() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            try { release.await(2, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            RestTemplate client = OpenAIStyleLLMServiceImpl.boundedRestTemplate(100, 50);
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(ResourceAccessException.class,
                    () -> client.getForObject("http://127.0.0.1:" + server.getAddress().getPort() + "/", String.class)));
        } finally { release.countDown(); server.stop(0); }
    }

    @Test
    void providerErrorsDoNotLogResponseBodyOrExceptionMessage() {
        var service = new OpenAIStyleLLMServiceImpl();
        var models = mock(ModelConfigService.class);
        var model = new ModelConfigEntity();
        model.setConfigJson(JSONUtil.parseObj("{\"base_url\":\"https://example.invalid/v1\",\"api_key\":\"test-placeholder\"}"));
        when(models.getEnabledModelsByType("LLM")).thenReturn(List.of(model));
        when(models.getModelByIdFromCache("model")).thenReturn(model);
        ReflectionTestUtils.setField(service, "modelConfigService", models);
        var client = mock(RestTemplate.class);
        when(client.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "private exception text",
                        null, "private upstream body".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        ReflectionTestUtils.setField(service, "restTemplate", client);
        Logger logger = (Logger) LoggerFactory.getLogger(OpenAIStyleLLMServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start(); logger.addAppender(appender);
        try {
            service.generateJson("private conversation", "model");
            assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains("private")));
            assertTrue(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null));
            verify(client, times(2)).exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}

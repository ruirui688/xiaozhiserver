package xiaozhi.modules.config.support;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.test.util.ReflectionTestUtils;

import xiaozhi.modules.agent.service.AgentPluginMappingService;
import xiaozhi.modules.sys.dao.SysParamsDao;
import xiaozhi.modules.sys.redis.SysParamsRedis;
import xiaozhi.modules.sys.service.impl.SysParamsServiceImpl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.Appender;

class ConfigLatencyTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(ConfigLatency.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        RequestContextHolder.resetRequestAttributes();
        logger.detachAppender(appender);
        appender.stop();
    }

    private MockHttpServletResponse bind(String header) {
        var request = new MockHttpServletRequest();
        if (header != null) {
            request.addHeader(ConfigLatency.HEADER, header);
        }
        var response = new MockHttpServletResponse();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request, response));
        return response;
    }

    @Test
    void propagatesValidatedTraceThroughNestedStagesAndClearsScope() {
        var response = bind("ABCDEF0123456789ABCDEF0123456789");
        assertEquals("result", ConfigLatency.request("config.agent_models",
                () -> ConfigLatency.measure("config.models.device_lookup", () -> "result")));
        assertEquals("abcdef0123456789abcdef0123456789", response.getHeader(ConfigLatency.HEADER));
        assertEquals(2, appender.list.size());
        for (var event : appender.list) {
            assertTrue(event.getFormattedMessage().contains("trace_id=" + response.getHeader(ConfigLatency.HEADER)));
            assertTrue(event.getFormattedMessage().contains("status=ok"));
        }
        ConfigLatency.measure("outside_scope", () -> null);
        assertEquals(2, appender.list.size());
    }

    @Test
    void rejectsUntrustedHeaderAndDoesNotLogPayloadOrExceptionMessage() {
        for (String header : List.of("Bearer private-secret", "a".repeat(33), "bad\nheader")) {
            var response = bind(header);
            var error = new IllegalStateException("sensitive-user-memory");
            assertSame(error, assertThrows(IllegalStateException.class,
                    () -> ConfigLatency.request("memory.query", () -> { throw error; })));
            assertTrue(response.getHeader(ConfigLatency.HEADER).matches("[a-f0-9]{32}"));
            ConfigLatency.measure("outside_scope", () -> null);
        }
        assertEquals(3, appender.list.size());
        for (var event : appender.list) {
            String line = event.getFormattedMessage();
            assertTrue(line.contains("status=error error_type=IllegalStateException"));
            assertFalse(line.contains("sensitive-user-memory"));
            assertFalse(line.contains("private-secret"));
            assertNull(event.getThrowableProxy());
        }
    }

    @Test
    void missingHeaderGeneratesTraceAndConcurrentRequestsStayIsolated() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = List.of("1".repeat(32), "2".repeat(32));
            var futures = tasks.stream().map(trace -> executor.submit(() -> {
                var response = bind(trace);
                try {
                    ConfigLatency.request("memory.query", () -> ConfigLatency.measure("memory.sql.facts", () -> null));
                    return response.getHeader(ConfigLatency.HEADER);
                } finally {
                    RequestContextHolder.resetRequestAttributes();
                }
            })).toList();
            assertEquals(tasks.get(0), futures.get(0).get());
            assertEquals(tasks.get(1), futures.get(1).get());
        }
        var response = bind(null);
        ConfigLatency.request("config.correct_words", () -> null);
        assertTrue(response.getHeader(ConfigLatency.HEADER).matches("[a-f0-9]{32}"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedLogSinkPreservesResultsAndOriginalErrors() {
        Appender<ILoggingEvent> broken = mock(Appender.class);
        doThrow(new IllegalStateException("sink unavailable")).when(broken).doAppend(any());
        logger.addAppender(broken);
        try {
            bind(null);
            assertEquals("result", ConfigLatency.request("memory.query", () -> "result"));
            var businessError = new IllegalArgumentException("private details");
            assertSame(businessError, assertThrows(IllegalArgumentException.class,
                    () -> ConfigLatency.request("memory.query", () -> { throw businessError; })));
            int before = appender.list.size();
            ConfigLatency.measure("outside_scope", () -> null);
            assertEquals(before, appender.list.size());
        } finally {
            logger.detachAppender(broken);
        }
    }

    @Test
    void failedResponseHeaderDoesNotLeakScopeOrChangeBusinessResult() {
        var response = mock(MockHttpServletResponse.class);
        doThrow(new IllegalStateException("response committed")).when(response).setHeader(any(), any());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(), response));
        assertEquals("result", ConfigLatency.request("memory.query", () -> "result"));
        ConfigLatency.measure("outside_scope", () -> null);
        assertEquals(1, appender.list.size());
    }

    @Test
    void paramsCacheHitAndDatabaseFallbackKeepExistingSemanticsWithoutLoggingValues() {
        var redis = mock(SysParamsRedis.class);
        var dao = mock(SysParamsDao.class);
        var service = new SysParamsServiceImpl(redis, mock(AgentPluginMappingService.class));
        ReflectionTestUtils.setField(service, "baseDao", dao);
        when(redis.get("private-key")).thenReturn("private-value", null);
        when(dao.getValueByCode("private-key")).thenReturn("db-private-value");
        bind(null);
        assertEquals("private-value", ConfigLatency.request("memory.query", () -> service.getValue("private-key", true)));
        verifyNoInteractions(dao);
        assertEquals("db-private-value", ConfigLatency.request("memory.query", () -> service.getValue("private-key", true)));
        verify(dao).getValueByCode("private-key");
        verify(redis).set("private-key", "db-private-value");
        var lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertEquals(2, lines.stream().filter(line -> line.contains("stage=params.cache_read ")).count());
        assertEquals(1, lines.stream().filter(line -> line.contains("stage=params.db_fallback ")).count());
        assertEquals(1, lines.stream().filter(line -> line.contains("stage=params.cache_write ")).count());
        assertTrue(lines.stream().noneMatch(line -> line.contains("private-key") || line.contains("private-value")));
    }
}

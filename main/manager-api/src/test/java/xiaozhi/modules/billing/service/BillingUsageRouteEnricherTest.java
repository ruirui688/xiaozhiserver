package xiaozhi.modules.billing.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import xiaozhi.common.exception.RenException;

class BillingUsageRouteEnricherTest {
    JdbcTemplate jdbc;
    BillingSupport json;
    BillingUsageRouteEnricher routes;

    @BeforeEach void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE ai_platform_reservation(id VARCHAR(64),attempt_id VARCHAR(128),logical_call_id VARCHAR(128),"
                + "context_id VARCHAR(64),model_id VARCHAR(64),service_type VARCHAR(32),workspace_id VARCHAR(64),"
                + "route_json TEXT,credential_id VARCHAR(64),credential_version BIGINT)");
        json = new BillingSupport(new ObjectMapper());
        routes = new BillingUsageRouteEnricher(jdbc, json);
        var route = Map.of("agentId", "agent-a", "deviceId", "device-a", "provider", "DEEPSEEK",
                "channelId", "channel-a", "channelVersion", 7, "accountRef", "platform-account-a");
        jdbc.update("INSERT INTO ai_platform_reservation VALUES('reservation-a','attempt-a','logical-a','context-a',"
                + "'model-a','LLM','workspace-a',?,'credential-a',3)", json.json(route));
    }

    @Test void derivesFrozenChannelAndAccountWithoutModifyingInputOrReadingSecrets() {
        var event = event();
        var before = new LinkedHashMap<>(event);
        var metadata = routes.metadata(event, null);
        assertEquals("reservation-a", metadata.get("reservation_id"));
        assertEquals("channel-a", metadata.get("channel_id"));
        assertEquals(7, metadata.get("channel_version"));
        assertEquals("platform-account-a", metadata.get("upstream_account_ref"));
        assertEquals("credential-a", metadata.get("credential_id"));
        assertEquals(3L, metadata.get("credential_version"));
        assertEquals(before, event);
        assertFalse(json.json(metadata).contains("secret"));
    }

    @Test void rejectsCrossWorkspaceModelContextAndDeviceReservationReuse() {
        for (String field : new String[]{"workspace_id", "model_id", "service_type", "context_id",
                "logical_call_id", "agent_id", "device_id", "provider"}) {
            var event = event(); event.put(field, "forged-value");
            assertEquals("INVALID_USAGE_ROUTE", assertThrows(RenException.class,
                    () -> routes.metadata(event, null), field).getMsg());
        }
        assertThrows(RenException.class, () -> routes.metadata(event(), "another-reservation"));
    }

    @Test void internalCallsMustIdentifyTheExactReservationAndOriginalAttempt() {
        var event = event(); event.remove("context_id");
        assertEquals("reservation-a", routes.metadata(event, "reservation-a").get("reservation_id"));
        event.put("attempt_id", "unknown-attempt");
        assertThrows(RenException.class, () -> routes.metadata(event, "reservation-a"));
        event.remove("attempt_id");
        assertThrows(RenException.class, () -> routes.metadata(event, "reservation-a"));
    }

    @Test void historicalAndRejectedAttemptsRemainUnattributed() {
        assertTrue(routes.metadata(Map.of("event_id", "historical-event"), null).isEmpty());
        var event = event(); event.put("attempt_id", "not-admitted");
        assertTrue(routes.metadata(event, null).isEmpty());
        jdbc.update("UPDATE ai_platform_reservation SET route_json=?", json.json(Map.of("agentId", "agent-a", "deviceId", "device-a")));
        var legacy = routes.metadata(event(), null);
        assertEquals("reservation-a", legacy.get("reservation_id"));
        assertNull(legacy.get("channel_id"));
        assertNull(legacy.get("upstream_account_ref"));
    }

    private Map<String, Object> event() {
        return new LinkedHashMap<>(Map.of("attempt_id", "attempt-a", "logical_call_id", "logical-a",
                "context_id", "context-a", "model_id", "model-a", "service_type", "LLM",
                "workspace_id", "workspace-a", "agent_id", "agent-a", "device_id", "device-a", "provider", "DEEPSEEK"));
    }
}

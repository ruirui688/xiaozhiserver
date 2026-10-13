package xiaozhi.modules.billing.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import xiaozhi.common.exception.RenException;

/** Derives upstream attribution only from the immutable, server-authorized reservation. */
@Service
@RequiredArgsConstructor
public class BillingUsageRouteEnricher {
    private final JdbcTemplate jdbc;
    private final BillingSupport support;

    public Map<String, Object> metadata(Map<String, Object> event, String reservationId) {
        Object attempt = event.get("attempt_id");
        if (attempt == null || attempt.toString().isBlank()) {
            if (reservationId != null) throw invalid();
            return Map.of(); // Historical events have no platform reservation.
        }
        var reservations = jdbc.queryForList("SELECT id,attempt_id,logical_call_id,context_id,model_id,service_type,"
                + "workspace_id,route_json,credential_id,credential_version FROM ai_platform_reservation WHERE attempt_id=?", attempt);
        if (reservations.isEmpty()) {
            if (reservationId != null) throw invalid();
            return Map.of(); // Calls rejected before admission may still report an observed attempt.
        }
        Map<String, Object> reservation = reservations.getFirst();
        if (reservationId != null && !reservationId.equals(reservation.get("id"))) throw invalid();
        for (String field : new String[]{"model_id", "service_type", "workspace_id"})
            if (!Objects.equals(event.get(field), reservation.get(field))) throw invalid();
        for (String field : new String[]{"context_id", "logical_call_id"})
            if (event.get(field) != null && !Objects.equals(event.get(field), reservation.get(field))) throw invalid();
        Map<String, Object> route = support.object(reservation.get("route_json").toString());
        if (!Objects.equals(event.get("agent_id"), route.get("agentId"))
                || !Objects.equals(event.get("device_id"), route.get("deviceId"))) throw invalid();
        if (route.get("provider") != null && !Objects.equals(event.get("provider"), route.get("provider"))) throw invalid();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reservation_id", reservation.get("id"));
        result.put("credential_id", reservation.get("credential_id"));
        result.put("credential_version", reservation.get("credential_version"));
        result.put("channel_id", route.get("channelId"));
        result.put("channel_version", route.get("channelVersion"));
        result.put("upstream_account_ref", route.get("accountRef"));
        return result;
    }

    private static RenException invalid() { return new RenException("INVALID_USAGE_ROUTE"); }
}

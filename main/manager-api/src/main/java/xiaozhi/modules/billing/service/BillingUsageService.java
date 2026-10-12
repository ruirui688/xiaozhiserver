package xiaozhi.modules.billing.service;

import java.time.Instant;
import java.util.*;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.billing.dto.*;

@Service @RequiredArgsConstructor
public class BillingUsageService implements BillingUsageRecorder {
    private final JdbcTemplate jdbc;
    private final BillingSupport support;
    private final BillingContextService contexts;
    private final TransactionTemplate transactions;
    private final Validator validator;
    private final BillingInternalOutbox outbox;

    public Map<String, Object> accept(UsageBatchRequest request) {
        List<String> accepted = new ArrayList<>();
        List<Map<String, String>> rejected = new ArrayList<>();
        for (UsageEventRequest event : request.getEvents()) {
            try {
                if (!validator.validate(event).isEmpty()) throw new RenException("INVALID_EVENT");
                Map<String, Object> snapshot = contexts.verify(event.getContextId(), event.getContextToken(), event.getModelId(), event.getStartedAt());
                Map<?, ?> model = (Map<?, ?>) ((Map<?, ?>) snapshot.get("models")).get(event.getModelId());
                String service = BillingSupport.service(event.getServiceType());
                if (!Objects.equals(service, model.get("service_type"))
                        && !(service.equals("REALTIME") && Objects.equals(model.get("service_type"), "LLM")))
                    throw new RenException("MODEL_SERVICE_MISMATCH");
                Map<String, Object> row = base(event.getEventId(), event.getStartedAt(), event.getEndedAt(), event.getStatus(), event.getUsageComplete());
                scope(row, snapshot);
                row.put("context_id", event.getContextId()); row.put("model_id", event.getModelId());
                row.put("model_name", model.get("name")); row.put("provider", model.get("provider")); row.put("service_type", service);
                // A platform capability cannot be relabeled as customer conversation traffic.
                row.put("purpose", "CONVERSATION".equals(snapshot.get("purpose")) ? event.getPurpose() : snapshot.get("purpose"));
                row.put("session_id", event.getSessionId()); row.put("turn_id", event.getTurnId());
                row.put("provider_request_id", event.getProviderRequestId()); row.put("attempt_id", event.getAttemptId());
                row.put("logical_call_id", event.getLogicalCallId()); row.put("sentence_id", event.getSentenceId());
                save(row, event.getUsage()); accepted.add(event.getEventId());
            } catch (RenException e) {
                String code = Set.of("INVALID_CONTEXT", "INVALID_CALL_TIME", "MODEL_OUTSIDE_CONTEXT", "MODEL_SERVICE_MISMATCH",
                        "EVENT_ID_CONFLICT", "INVALID_EVENT", "INVALID_USAGE").contains(e.getMsg()) ? e.getMsg() : "INVALID_EVENT";
                rejected.add(Map.of("eventId", event.getEventId(), "reasonCode", code));
            }
            // Storage failures propagate as HTTP errors: the sender must retain its durable outbox.
        }
        return Map.of("accepted", accepted, "rejected", rejected);
    }

    @Override
    public void recordInternal(InternalUsage event) {
        try { persistInternal(event); }
        catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException e) { outbox.store(event); }
    }

    public void persistInternal(InternalUsage event) {
        Map<String, Object> row = base(event.getEventId(), event.getStartedAt(), event.getEndedAt(), event.getStatus(), event.getUsageComplete());
        row.put("workspace_id", event.getWorkspaceId()); row.put("agent_id", event.getAgentId()); row.put("device_id", event.getDeviceId());
        // Scope is captured by trusted Java callers before dispatch, not recovered from a MAC at completion.
        row.put("model_id", event.getModelId()==null || event.getModelId().isBlank() ? "UNKNOWN" : event.getModelId()); row.put("provider", event.getProvider() == null ? "UNKNOWN" : BillingSupport.provider(event.getProvider(), ""));
        row.put("service_type", BillingSupport.service(event.getServiceType())); row.put("purpose", event.getPurpose());
        row.put("session_id", event.getSessionId()); row.put("turn_id", event.getTurnId()); row.put("provider_request_id", event.getProviderRequestId());
        if (event.getAgentId() != null) {
            var agents = jdbc.queryForList("SELECT agent_name,workspace_id FROM ai_agent WHERE id=?", event.getAgentId());
            if (!agents.isEmpty()) {
                if (!Objects.equals(event.getWorkspaceId(), agents.getFirst().get("workspace_id"))) throw new RenException("INVALID_EVENT");
                row.put("agent_name", agents.getFirst().get("agent_name"));
            }
        }
        save(row, event.getUsage() == null ? List.of() : event.getUsage());
    }

    private Map<String, Object> base(String id, Instant start, Instant end, String status, Boolean complete) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{8,64}") || start == null || end == null || end.isBefore(start)
                || end.isAfter(Instant.now().plusSeconds(300)) || !Set.of("SUCCESS", "FAILED", "CANCELLED", "UNKNOWN").contains(status))
            throw new RenException("INVALID_EVENT");
        Map<String, Object> row = new TreeMap<>();
        row.put("event_id", id); row.put("started_at", BillingSupport.time(start)); row.put("ended_at", BillingSupport.time(end));
        row.put("status", status); row.put("usage_complete", Boolean.TRUE.equals(complete) ? 1 : 0);
        return row;
    }

    private void scope(Map<String, Object> row, Map<String, Object> snapshot) {
        for (String key : List.of("workspace_id", "agent_id", "agent_name", "device_id", "device_name", "device_board")) row.put(key, snapshot.get(key));
    }

    private void save(Map<String, Object> row, List<Usage> usage) {
        if(usage.isEmpty())row.put("usage_complete",0);
        List<Map<String, Object>> metrics = new ArrayList<>(); Set<String> unique = new HashSet<>();
        for (Usage item : usage) {
            if (!validator.validate(item).isEmpty()) throw new RenException("INVALID_USAGE");
            String source = "LOCAL".equals(item.getSource()) ? "OBSERVED" : item.getSource();
            String key = item.getMetric() + ":" + item.getUnit() + ":" + source;
            if (!unique.add(key)) throw new RenException("INVALID_USAGE");
            metrics.add(new TreeMap<>(Map.of("metric", item.getMetric(), "unit", item.getUnit(), "source", source,
                    "quantity", item.getQuantity().stripTrailingZeros())));
        }
        metrics.sort(Comparator.comparing(support::json));
        String digest = BillingSupport.hash(support.json(Map.of("event", row, "usage", metrics)));
        row.put("payload_hash", digest); row.put("received_at", BillingSupport.time(Instant.now()));
        TransactionTemplate ledger = new TransactionTemplate(Objects.requireNonNull(transactions.getTransactionManager()));
        ledger.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        ledger.executeWithoutResult(tx -> {
            try { insert(jdbc, "ai_billing_usage_event", row); }
            catch (DuplicateKeyException e) {
                String previous = jdbc.queryForObject("SELECT payload_hash FROM ai_billing_usage_event WHERE event_id=?", String.class, row.get("event_id"));
                if (!digest.equals(previous)) throw new RenException("EVENT_ID_CONFLICT");
                return;
            }
            for (Map<String, Object> metric : metrics) {
                metric.put("event_id", row.get("event_id")); insert(jdbc, "ai_billing_usage_metric", metric);
            }
        });
    }

    static void insert(JdbcTemplate jdbc, String table, Map<String, Object> row) {
        String columns = String.join(",", row.keySet());
        String values = String.join(",", row.keySet().stream().map(key -> ":" + key).toList());
        new NamedParameterJdbcTemplate(jdbc).update("INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ")", row);
    }
}

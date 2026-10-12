package xiaozhi.modules.billing.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.entity.ModelConfigEntity;

/** Immutable scope capabilities. Delayed reports never resolve the device's current agent. */
@Service @RequiredArgsConstructor
public class BillingContextService {
    private final JdbcTemplate jdbc;
    private final BillingSupport support;
    private final ModelConfigDao models;
    @org.springframework.beans.factory.annotation.Autowired
    private xiaozhi.modules.platform.service.PlatformServiceAccountService platformServices;
    private static final SecureRandom RANDOM = new SecureRandom();

    public void attach(Map<String, Object> runtime, AgentEntity agent, DeviceEntity device) {
        if (agent == null || device == null || agent.getWorkspaceId() == null
                || !Objects.equals(agent.getId(), device.getAgentId())
                || !Objects.equals(agent.getWorkspaceId(), device.getWorkspaceId()))
            throw new RenException("设备计量归属无效");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("workspace_id", agent.getWorkspaceId());
        snapshot.put("agent_id", agent.getId()); snapshot.put("agent_name", agent.getAgentName());
        snapshot.put("device_id", device.getId()); snapshot.put("device_name", device.getAlias());
        snapshot.put("device_board", device.getBoard()); snapshot.put("config_version", device.getConfigVersion());
        Object configured = runtime.get("model_config");
        List<String> ids = configured instanceof Map<?, ?> map ? map.keySet().stream().map(String::valueOf).toList() : List.of();
        runtime.put("billing_context", issue(snapshot, ids, "CONVERSATION"));
    }

    public Map<String, Object> issuePlatform(Collection<String> modelIds, String purpose) {
        if (!Set.of("MODEL_TEST", "BENCHMARK", "MODEL_ASSISTANT").contains(purpose))
            throw new RenException("平台调用用途无效");
        if(platformServices==null)throw new RenException("PLATFORM_SERVICE_BUDGET_REQUIRED");
        Set<String> services=new HashSet<>();for(String model:modelIds)services.add(platformServices.resolve(model,purpose));
        if(services.size()!=1)throw new RenException("PLATFORM_SERVICE_BUDGET_REQUIRED");
        Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("platform_service_id",services.iterator().next());
        return issue(snapshot, modelIds, purpose);
    }

    private Map<String, Object> issue(Map<String, Object> snapshot, Collection<String> ids, String purpose) {
        Map<String, Object> selected = new LinkedHashMap<>();
        for (String id : new LinkedHashSet<>(ids)) {
            if (id == null || id.isBlank()) continue;
            ModelConfigEntity model = models.selectById(id);
            if (model == null) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", model.getModelName());
            entry.put("model_name", model.getUpstreamModelId());
            entry.put("upstream_model_id", model.getUpstreamModelId());
            entry.put("provider", BillingSupport.provider(model.getVendorName(), model.getAdapterCode()));
            entry.put("service_type", BillingSupport.service(model.getModelType()));
            selected.put(id, entry);
        }
        snapshot.put("models", selected); snapshot.put("purpose", purpose);
        String id = UUID.randomUUID().toString();
        byte[] secret = new byte[32]; RANDOM.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        jdbc.update("INSERT INTO ai_billing_context (id,token_hash,workspace_id,agent_id,device_id,purpose,snapshot_json,issued_at) VALUES (?,?,?,?,?,?,?,?)",
                id, BillingSupport.hash(token), snapshot.get("workspace_id"), snapshot.get("agent_id"), snapshot.get("device_id"), purpose,
                support.json(snapshot), BillingSupport.time(Instant.now()));
        Map<String, Object> issued = new LinkedHashMap<>(snapshot);
        issued.put("_platform_managed", true);
        issued.put("context_id", id); issued.put("context_token", token);
        return issued;
    }

    public Map<String, Object> verify(String id, String token, String modelId, Instant startedAt) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT token_hash,snapshot_json,issued_at FROM ai_billing_context WHERE id=?", id);
        if (rows.isEmpty() || token == null) throw new RenException("INVALID_CONTEXT");
        Map<String, Object> row = rows.getFirst();
        if (!MessageDigest.isEqual(BillingSupport.hash(token).getBytes(StandardCharsets.US_ASCII),
                row.get("token_hash").toString().getBytes(StandardCharsets.US_ASCII))) throw new RenException("INVALID_CONTEXT");
        Instant issuedAt = BillingSupport.databaseTime(row.get("issued_at")).toInstant(java.time.ZoneOffset.UTC);
        if (startedAt == null || startedAt.isBefore(issuedAt.minusSeconds(300)) || startedAt.isAfter(Instant.now().plusSeconds(300)))
            throw new RenException("INVALID_CALL_TIME");
        Map<String, Object> snapshot = support.object(row.get("snapshot_json").toString());
        if (!(snapshot.get("models") instanceof Map<?, ?> allowed) || !allowed.containsKey(modelId)) throw new RenException("MODEL_OUTSIDE_CONTEXT");
        return snapshot;
    }
}

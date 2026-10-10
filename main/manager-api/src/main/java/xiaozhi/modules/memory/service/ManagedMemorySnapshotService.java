package xiaozhi.modules.memory.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.config.support.ConfigLatency;
import xiaozhi.modules.memory.dao.MemoryFactDao;
import xiaozhi.modules.memory.dao.MemoryForgetTombstoneDao;
import xiaozhi.modules.memory.entity.MemoryFactEntity;
import xiaozhi.modules.memory.entity.MemoryForgetTombstoneEntity;
import xiaozhi.modules.memory.utils.MemoryHelper;
import xiaozhi.modules.memory.utils.MemorySpeakerUtils;

/** Bounded, query-independent snapshot of authoritative facts; never persisted or shared across agents. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ManagedMemorySnapshotService {
    private static final int MAX_SPEAKERS = 16;
    private static final int MAX_TOTAL_CHARS = 16000;
    private final MemoryFactDao factDao;
    private final MemoryForgetTombstoneDao tombstoneDao;
    @org.springframework.beans.factory.annotation.Autowired
    private MemoryPolicyService policyService;

    public void attach(Map<String, Object> config, String agentId, String deviceId) {
        config.remove("managed_memory_snapshot");
        if (policyService != null) config.put("memory_policy", policyService.get(agentId));
        if (!managed(config) || StringUtils.isBlank(deviceId)) {
            return;
        }
        try {
            config.put("managed_memory_snapshot", build(agentId, deviceId, configuredSpeakers(config)));
        } catch (RuntimeException error) {
            // Do not retain a previous payload or fall back to unscoped legacy summaries.
            config.put("managed_memory_snapshot", Map.of("schema_version", 1, "status", "unavailable",
                    "agent_id", agentId, "device_id", deviceId));
            log.warn("managed_memory_snapshot status=unavailable error_type={}", error.getClass().getSimpleName());
        }
    }

    private boolean managed(Map<String, Object> config) {
        if (!(config.get("selected_module") instanceof Map<?, ?> selected)
                || !(config.get("model_config") instanceof Map<?, ?> models)
                || !(models.get(selected.get("Memory")) instanceof Map<?, ?> model)
                || !(model.get("typed_config") instanceof Map<?, ?> memory)) {
            return false;
        }
        return "managed_memory".equals(memory.get("type"));
    }

    private List<String> configuredSpeakers(Map<String, Object> config) {
        if (!(config.get("voiceprint") instanceof Map<?, ?> voiceprint)
                || !(voiceprint.get("speakers") instanceof List<?> speakers)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : speakers) {
            if (item instanceof Map<?, ?> speaker && speaker.get("speaker_id") instanceof String id
                    && !id.isBlank()) {
                result.add(id);
            }
        }
        return result;
    }

    // The speaker list is produced by ConfigService's bound-agent voiceprint configuration, never by request input.
    public Map<String, Object> build(String agentId, String deviceId, List<String> configuredSpeakers) {
        long issuedAt = System.currentTimeMillis();
        var policy = policyService == null ? new xiaozhi.modules.memory.dto.MemoryPolicyDTO() : policyService.get(agentId);
        long expiresAt = issuedAt + 30_000;
        Set<String> allowed = new LinkedHashSet<>(configuredSpeakers.stream().distinct().limit(MAX_SPEAKERS).toList());
        QueryWrapper<MemoryFactEntity> query = new QueryWrapper<>();
        query.eq("agent_id", agentId).eq("status", MemoryHelper.STATUS_ACTIVE)
                .and(w -> w.isNull("expires_at").or().gt("expires_at", new Date(issuedAt)))
                .and(w -> {
                    w.isNull("payload").or().apply("JSON_EXTRACT(payload, '$.speaker_id') IS NULL")
                            .or().apply("JSON_UNQUOTE(JSON_EXTRACT(payload, '$.speaker_id')) = ''");
                    for (String id : allowed) {
                        w.or().apply("JSON_UNQUOTE(JSON_EXTRACT(payload, '$.speaker_id')) = {0}", id);
                    }
                }).orderByDesc("importance", "last_seen_at").orderByAsc("memory_id").last("LIMIT 200");
        List<String> identities = new ArrayList<>(allowed);
        identities.add("");
        List<MemoryFactEntity> facts = ConfigLatency.measure("memory.snapshot.facts", () -> policyService == null
                ? factDao.selectList(query) : factDao.selectSnapshotCandidates(agentId,
                        "DEVICE".equals(policy.getMemoryScope()) ? deviceId : null, identities, 200 / identities.size()));
        List<MemoryForgetTombstoneEntity> tombstones = ConfigLatency.measure("memory.snapshot.tombstones", () -> tombstoneDao.selectList(
                new QueryWrapper<MemoryForgetTombstoneEntity>().eq("agent_id", agentId)
                        .eq("status", MemoryHelper.STATUS_ACTIVE).orderByDesc("created_at")));
        StringBuilder shared = new StringBuilder();
        Map<String, StringBuilder> privateParts = new LinkedHashMap<>();
        int total = 0;
        boolean truncated = configuredSpeakers.stream().distinct().count() > MAX_SPEAKERS || facts.size() >= 200;
        if (policyService != null && facts.stream().collect(java.util.stream.Collectors.groupingBy(
                MemorySpeakerUtils::payloadSpeakerId, java.util.stream.Collectors.counting()))
                .values().stream().anyMatch(count -> count >= 200 / identities.size())) truncated = true;
        for (MemoryFactEntity fact : facts) {
            if (!agentId.equals(fact.getAgentId()) || !MemoryHelper.STATUS_ACTIVE.equals(fact.getStatus())
                    || (fact.getExpiresAt() != null && fact.getExpiresAt().getTime() <= issuedAt)
                    || StringUtils.isBlank(fact.getCanonicalText())) {
                continue;
            }
            // Malformed attribution must not be reinterpreted as shared memory.
            if (StringUtils.isNotBlank(fact.getPayload())) {
                try {
                    var payload = cn.hutool.json.JSONUtil.parseObj(fact.getPayload());
                    if (payload.containsKey("speaker_id") && !(payload.get("speaker_id") instanceof String)) {
                        continue;
                    }
                } catch (RuntimeException invalidPayload) {
                    continue;
                }
            }
            String speaker = MemorySpeakerUtils.payloadSpeakerId(fact).trim();
            if ("DEVICE".equals(policy.getMemoryScope()) && !deviceId.equals(fact.getScopeId())) continue;
            if (tombstones.stream().anyMatch(rule -> MemoryGovernanceService.matches(rule, deviceId, speaker,
                    fact.getCanonicalText(), "DEVICE".equals(policy.getMemoryScope())))) continue;
            if (!speaker.isEmpty() && !allowed.contains(speaker)) {
                continue;
            }
            StringBuilder target = speaker.isEmpty() ? shared : privateParts.computeIfAbsent(speaker, key -> new StringBuilder());
            String text = "- " + fact.getCanonicalText().strip().replaceAll("\\R+", " ") + "\n";
            int limit = Math.min(policy.getContextMaxChars(), MAX_TOTAL_CHARS / identities.size());
            int room = Math.min(limit - target.length(), MAX_TOTAL_CHARS - total);
            if (text.length() > room) {
                truncated = true;
                continue; // Keep complete facts; do not truncate a negation or identity qualifier.
            }
            target.append(text);
            total += text.length();
            if (fact.getExpiresAt() != null) {
                expiresAt = Math.min(expiresAt, fact.getExpiresAt().getTime());
            }
        }
        Map<String, String> speakers = new LinkedHashMap<>();
        privateParts.forEach((key, value) -> { if (!value.isEmpty()) speakers.put(key, value.toString()); });
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("agent_id", agentId);
        content.put("device_id", deviceId);
        content.put("shared_context", shared.toString());
        content.put("speaker_contexts", speakers);
        content.put("truncated", truncated);
        content.put("context_max_chars", policy.getContextMaxChars());
        content.put("context_max_items", policy.getContextMaxItems());
        String revision;
        try {
            revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JsonUtils.toJsonString(content).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        content.put("schema_version", 1);
        content.put("status", "ready");
        content.put("revision", revision);
        content.put("issued_at_ms", issuedAt);
        content.put("expires_at_ms", expiresAt);
        return content;
    }

    /** The same complete-line budget used by the Python runtime, including HTML escaping. */
    public static String selectedContext(Map<String, Object> snapshot, String speakerId) {
        Map<?, ?> speakers = (Map<?, ?>) snapshot.getOrDefault("speaker_contexts", Map.of());
        String selectedSpeaker = StringUtils.defaultString(speakerId);
        String privateContext = String.valueOf(speakers.containsKey(selectedSpeaker) ? speakers.get(selectedSpeaker) : "");
        String text = privateContext + snapshot.getOrDefault("shared_context", "");
        int maxChars = ((Number) snapshot.getOrDefault("context_max_chars", 4000)).intValue();
        int maxItems = ((Number) snapshot.getOrDefault("context_max_items", 30)).intValue();
        StringBuilder result = new StringBuilder();
        int chars = 0, items = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (String line : text.split("\\R")) {
            if (!line.startsWith("- ")) continue;
            if (!seen.add(line)) continue;
            int cost = escapedLength(line) + (items > 0 ? 1 : 0);
            if (items >= maxItems || chars + cost > maxChars) continue;
            if (items > 0) result.append('\n');
            result.append(line);
            chars += cost;
            items++;
        }
        return result.toString();
    }

    public static int escapedLength(String value) {
        String escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return escaped.codePointCount(0, escaped.length());
    }

}

package xiaozhi.modules.memory.service;

import java.text.Normalizer;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import xiaozhi.modules.memory.dao.MemoryForgetTombstoneDao;
import xiaozhi.modules.memory.dao.MemoryRevisionDao;
import xiaozhi.modules.memory.dto.MemoryFactDTO;
import xiaozhi.modules.memory.dto.MemoryPolicyDTO;
import xiaozhi.modules.memory.dto.MemorySessionMessageDTO;
import xiaozhi.modules.memory.entity.MemoryFactEntity;
import xiaozhi.modules.memory.entity.MemoryForgetTombstoneEntity;
import xiaozhi.modules.memory.entity.MemoryRevisionEntity;
import xiaozhi.modules.memory.utils.MemoryHelper;
import xiaozhi.modules.memory.utils.MemorySpeakerUtils;

/** Deterministic write/read boundaries, independent of model instructions. */
@Service
@RequiredArgsConstructor
public class MemoryGovernanceService {
    private final MemoryPolicyService policies;
    private final MemoryForgetTombstoneDao tombstones;
    private final MemoryRevisionDao revisions;

    public boolean inScope(String agentId, String deviceId, String factScope) {
        return !"DEVICE".equals(policies.get(agentId).getMemoryScope()) || StringUtils.equals(deviceId, factScope);
    }

    public Date expiresAt(String agentId) {
        int days = policies.get(agentId).getRetentionDays();
        return days == 0 ? null : new Date(System.currentTimeMillis() + days * 86_400_000L);
    }

    public static int activeChars(MemoryFactEntity fact) {
        return fact != null && MemoryHelper.STATUS_ACTIVE.equals(fact.getStatus())
                && (fact.getExpiresAt() == null || fact.getExpiresAt().after(new Date()))
                ? MemoryHelper.characterCount(fact.getCanonicalText()) : 0;
    }

    public void editIdentity(String agentId, MemoryFactEntity fact, MemoryFactDTO dto) {
        JSONObject payload = MemorySpeakerUtils.parseObject(fact.getPayload());
        if (dto.getVisibility() != null) {
            if (!List.of("shared", "private").contains(dto.getVisibility())) {
                throw new IllegalArgumentException("记忆可见性必须为 shared 或 private");
            }
            if ("private".equals(dto.getVisibility())) {
                if (StringUtils.isBlank(dto.getSpeakerId())) throw new IllegalArgumentException("私有记忆必须选择人物");
                policies.requireSpeaker(agentId, dto.getSpeakerId());
                payload.set("speaker_id", dto.getSpeakerId());
            } else {
                payload.remove("speaker_id");
                payload.remove("speaker_name");
            }
        } else if (dto.getSpeakerId() != null) {
            throw new IllegalArgumentException("修改人物时必须明确 shared/private");
        }
        String device = dto.getDeviceId() == null ? fact.getScopeId() : dto.getDeviceId();
        if (dto.getDeviceId() != null && StringUtils.isNotBlank(device)) policies.requireDevice(agentId, device);
        if ("DEVICE".equals(policies.get(agentId).getMemoryScope())) policies.requireDevice(agentId, device);
        fact.setScopeId(StringUtils.defaultIfBlank(device, agentId));
        fact.setMacAddress(StringUtils.defaultIfBlank(device, agentId));
        fact.setPayload(payload.toString());
    }

    public boolean hasUserEvidence(JSONObject action, List<MemorySessionMessageDTO> messages, String speakerId) {
        if (!"user".equals(action.getStr("source_role"))) return false;
        String quote = StringUtils.trimToEmpty(action.getStr("evidence_quote"));
        if (quote.isEmpty()) return false;
        return messages.stream().filter(message -> message != null && !Boolean.FALSE.equals(message.getMemoryEligible())
                        && "user".equals(message.getRole()))
                .anyMatch(message -> StringUtils.equals(StringUtils.defaultString(speakerId), speaker(message))
                        && MemorySpeakerUtils.contentFromChatPayload(message.getContent()).contains(quote));
    }

    public static String speaker(MemorySessionMessageDTO message) {
        String encoded = MemorySpeakerUtils.fromChatContent(message.getContent()).id();
        if (message.getSpeakerId() != null && StringUtils.isNotBlank(encoded)
                && !message.getSpeakerId().equals(encoded)) throw new IllegalArgumentException("消息人物元数据冲突");
        return message.getSpeakerId() == null ? encoded : message.getSpeakerId();
    }

    public boolean blocked(String agentId, String deviceId, String speakerId, String text) {
        return rules(agentId).stream().anyMatch(rule -> matches(rule, deviceId, speakerId, text,
                "DEVICE".equals(policies.get(agentId).getMemoryScope())));
    }

    public List<MemoryForgetTombstoneEntity> rules(String agentId) {
        return tombstones.selectList(new QueryWrapper<MemoryForgetTombstoneEntity>()
                .eq("agent_id", agentId).eq("status", MemoryHelper.STATUS_ACTIVE));
    }

    public static boolean matches(MemoryForgetTombstoneEntity rule, String deviceId, String speakerId,
                                  String text, boolean deviceScoped) {
        if (deviceScoped && !StringUtils.equals(deviceId, rule.getScopeId())) return false;
        if (!StringUtils.equals(StringUtils.defaultString(speakerId), StringUtils.defaultString(rule.getSpeakerId()))) return false;
        String needle = normalized(rule.getPatternText());
        String haystack = normalized(text);
        return !needle.isEmpty() && ("exact".equals(rule.getMatchType()) ? haystack.equals(needle) : haystack.contains(needle));
    }

    public void forgetFact(MemoryFactEntity fact, String actorType, String actorId) {
        String speaker = MemorySpeakerUtils.payloadSpeakerId(fact);
        if (rules(fact.getAgentId()).stream().anyMatch(rule -> StringUtils.equals(rule.getScopeId(), fact.getScopeId())
                && StringUtils.equals(StringUtils.defaultString(rule.getSpeakerId()), speaker)
                && normalized(rule.getPatternText()).equals(normalized(fact.getCanonicalText())))) return;
        MemoryForgetTombstoneEntity rule = new MemoryForgetTombstoneEntity();
        rule.setTombstoneId(MemoryHelper.uuid());
        rule.setAgentId(fact.getAgentId());
        rule.setWorkspaceId(fact.getWorkspaceId());
        rule.setUserId(fact.getUserId());
        rule.setScopeType("device");
        rule.setScopeId(fact.getScopeId());
        rule.setSpeakerId(speaker);
        rule.setMatchType("exact");
        rule.setPatternText(fact.getCanonicalText());
        rule.setReason("显式删除：阻止相同事实再次写入");
        rule.setStatus("active");
        rule.setCreatedAt(new Date());
        rule.setUpdatedAt(new Date());
        tombstones.insert(rule);
        MemoryRevisionEntity revision = new MemoryRevisionEntity();
        revision.setRevisionId(MemoryHelper.uuid());
        revision.setMemoryId(fact.getMemoryId());
        revision.setAgentId(fact.getAgentId());
        revision.setWorkspaceId(fact.getWorkspaceId());
        revision.setRevisionAction("forget_boundary");
        revision.setActorType(actorType);
        revision.setActorId(actorId);
        revision.setReason("记录人物及设备遗忘边界");
        revision.setCreatedAt(new Date());
        revisions.insert(revision);
    }

    private static String normalized(String value) {
        return Normalizer.normalize(StringUtils.defaultString(value), Normalizer.Form.NFKC)
                .replaceAll("[\\p{Punct}\\s，。！？、；：]+", "").toLowerCase(Locale.ROOT);
    }
}

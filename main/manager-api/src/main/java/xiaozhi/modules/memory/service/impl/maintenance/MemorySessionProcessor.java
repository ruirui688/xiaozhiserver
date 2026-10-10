package xiaozhi.modules.memory.service.impl.maintenance;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.llm.service.LLMService;
import xiaozhi.modules.memory.dao.MemoryCandidateDao;
import xiaozhi.modules.memory.dao.MemoryRevisionDao;
import xiaozhi.modules.memory.dto.MemoryPolicyDTO;
import xiaozhi.modules.memory.dto.MemorySessionMessageDTO;
import xiaozhi.modules.memory.entity.MemoryCandidateEntity;
import xiaozhi.modules.memory.entity.MemoryRevisionEntity;
import xiaozhi.modules.memory.prompt.PowerMemPrompts;
import xiaozhi.modules.memory.service.MemoryGovernanceService;
import xiaozhi.modules.memory.service.MemoryPolicyService;
import xiaozhi.modules.memory.service.MemoryRuntimeRefresh;
import xiaozhi.modules.memory.utils.MemoryHelper;
import xiaozhi.modules.memory.utils.MemorySpeakerUtils;

/** Extract, validate and atomically apply one durable conversation segment. */
@Component
@RequiredArgsConstructor
public class MemorySessionProcessor {
    private final MemoryPolicyService policies;
    private final MemoryGovernanceService governance;
    private final AgentDao agents;
    private final MemoryCandidateDao candidates;
    private final MemoryRevisionDao revisions;
    private final LLMService llm;
    private final MemoryMaintenanceOutputParser parser;
    private final MemorySpeakerConversationBuilder conversations;
    private final TransactionTemplate transactions;
    private final MemoryRuntimeRefresh refresh;

    public int process(AgentEntity agent, String sessionId, List<MemorySessionMessageDTO> messages,
                       JSONObject memoryConfig, Supplier<String> existingContext,
                       Function<JSONObject, Boolean> apply, Function<JSONObject, MemoryCandidateEntity> candidate) {
        if (agent == null || StringUtils.isBlank(sessionId) || messages == null || messages.isEmpty()) return 0;
        Integer completed = processedResult(agent.getId(), sessionId);
        if (completed != null) return completed;
        MemoryPolicyDTO policy = policies == null ? new MemoryPolicyDTO() : policies.get(agent.getId());
        if ("OFF".equals(policy.getMaintenanceMode()) || !"managed_memory".equalsIgnoreCase(memoryConfig.getStr("type"))) return 0;
        List<MemorySessionMessageDTO> eligible = messages.stream()
                .filter(message -> message != null && !Boolean.FALSE.equals(message.getMemoryEligible()))
                .filter(message -> !"user".equals(message.getRole()) || !"IGNORE".equals(policy.getUnknownSpeakerPolicy())
                        || StringUtils.isNotBlank(MemoryGovernanceService.speaker(message))).toList();
        if (eligible.stream().noneMatch(message -> "user".equals(message.getRole()))) return 0;
        if (policies != null) eligible.stream().filter(message -> "user".equals(message.getRole()))
                .forEach(message -> policies.requireSpeaker(agent.getId(), MemoryGovernanceService.speaker(message)));
        String modelId = StringUtils.defaultIfBlank(memoryConfig.getStr(MemoryHelper.MAINTENANCE_LLM_FIELD),
                memoryConfig.getStr("memory_maintenance_llm"));
        if (StringUtils.isBlank(modelId)) throw new IllegalStateException("主动记忆未配置维护模型");
        var conversation = conversations.fromMessages(eligible);
        String prompt = PowerMemPrompts.SESSION_ACTIONS_PROMPT.replace("{existing_memories}", existingContext.get())
                .replace("{conversation}", conversation.text());
        String output = llm.generateJson(prompt, modelId);
        JSONArray actions = parser.parseActionsResult(output);
        if (actions == null && StringUtils.isNotBlank(output)) {
            actions = parser.parseActionsResult(llm.generateJson(
                    PowerMemPrompts.JSON_REPAIR_PROMPT.replace("{raw_output}", output), modelId));
        }
        if (actions == null) throw new IllegalStateException("主动记忆模型未返回可解析动作");
        List<JSONObject> validated = new ArrayList<>();
        for (Object item : actions) {
            JSONObject action = MemoryHelper.toJsonObject(item);
            if (action == null || "NONE".equals(MemoryHelper.normalizeAction(action.getStr("action")))) continue;
            var speaker = conversation.speakers().resolve(action);
            if (speaker == null) throw new IllegalStateException("主动记忆动作人物归属不明确");
            if (governance != null && !governance.hasUserEvidence(action, eligible, speaker.id()))
                throw new IllegalStateException("主动记忆动作缺少对应人物的真实用户证据");
            MemorySpeakerUtils.applyInternalIdentity(action, speaker);
            validated.add(action);
        }
        Integer changed = transactions.execute(status -> {
            agents.selectOne(new QueryWrapper<AgentEntity>().eq("id", agent.getId()).last("FOR UPDATE"));
            xiaozhi.modules.memory.service.processing.MemorySessionTaskContext.checkBeforeCommit();
            Integer previous = processedResult(agent.getId(), sessionId);
            if (previous != null) return previous;
            int count = 0;
            for (JSONObject action : validated) {
                if ("REVIEW".equals(policy.getMaintenanceMode())) {
                    MemoryCandidateEntity proposed = candidate.apply(action);
                    if (proposed == null) throw new IllegalStateException("候选记忆结构无效");
                    candidates.insert(proposed);
                } else if (!Boolean.TRUE.equals(apply.apply(action))) {
                    throw new IllegalStateException("主动记忆动作被容量、遗忘或归属规则拒绝，未提交该段");
                }
                count++;
            }
            MemoryRevisionEntity revision = new MemoryRevisionEntity();
            revision.setRevisionId(MemoryHelper.uuid());
            revision.setAgentId(agent.getId());
            revision.setWorkspaceId(agent.getWorkspaceId());
            revision.setRevisionAction("session_memory_processed");
            revision.setAfterPayload(JsonUtils.toJsonString(Map.of("sessionId", sessionId, "factsChanged", count,
                    "maintenanceMode", policy.getMaintenanceMode())));
            revision.setActorType("system");
            revision.setActorId(sessionId);
            revision.setReason("会话段已通过人物、证据和容量校验");
            revision.setCreatedAt(new Date());
            revisions.insert(revision);
            refresh.changed(agent.getId());
            return count;
        });
        return changed == null ? 0 : changed;
    }

    private Integer processedResult(String agentId, String sessionId) {
        QueryWrapper<MemoryRevisionEntity> query = new QueryWrapper<MemoryRevisionEntity>().eq("agent_id", agentId)
                .eq("revision_action", "session_memory_processed").eq("actor_id", sessionId);
        if (revisions.selectCount(query) == 0) return null;
        MemoryRevisionEntity revision = revisions.selectOne(query.orderByDesc("created_at").last("LIMIT 1"));
        return revision == null ? 0 : MemoryHelper.parsePayload(revision.getAfterPayload()).getInt("factsChanged", 0);
    }
}

package xiaozhi.modules.agent.runtime;

import java.util.List;

public record AgentRuntimeConfigRefreshEvent(List<String> agentIds, String reason) {
    public AgentRuntimeConfigRefreshEvent {
        agentIds = agentIds == null ? List.of() : agentIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        reason = reason == null || reason.isBlank() ? "config_updated" : reason;
    }

    public static AgentRuntimeConfigRefreshEvent forAgent(String agentId, String reason) {
        return new AgentRuntimeConfigRefreshEvent(List.of(agentId), reason);
    }
}

package xiaozhi.modules.person.runtime;

import java.util.List;

public record AgentFaceReleaseEvent(List<String> agentIds) {
    public AgentFaceReleaseEvent {
        agentIds = agentIds == null ? List.of() : agentIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
    }
}

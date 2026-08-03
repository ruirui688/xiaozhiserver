package xiaozhi.modules.agent.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import xiaozhi.modules.agent.entity.AgentEntity;

/**
 * 归一化智能体的火山 ASR 热词候选列表和当前生效项。
 *
 * 火山识别协议一次请求只允许生效一张词表，因此候选列表用于管理和快速切换，
 * {@code asrHotwordId} 始终保存实际下发给识别服务的单个 ID。
 */
public final class AgentAsrHotwordSelection {
    static final int MAX_CANDIDATES = 10;

    private AgentAsrHotwordSelection() {
    }

    public static void apply(
            AgentEntity entity,
            String requestedActiveId,
            List<String> requestedCandidateIds) {
        boolean activeProvided = requestedActiveId != null;
        boolean candidatesProvided = requestedCandidateIds != null;
        if (!activeProvided && !candidatesProvided) {
            return;
        }

        String activeId = activeProvided
                ? StringUtils.trimToNull(requestedActiveId)
                : StringUtils.trimToNull(entity.getAsrHotwordId());
        List<String> candidateIds = candidatesProvided
                ? normalize(requestedCandidateIds)
                : normalize(entity.getAsrHotwordIds());

        if (candidatesProvided && !activeProvided && !candidateIds.contains(activeId)) {
            activeId = null;
        }
        if (activeId != null && !candidateIds.contains(activeId)) {
            candidateIds.add(0, activeId);
        }
        if (candidateIds.size() > MAX_CANDIDATES) {
            candidateIds = new ArrayList<>(candidateIds.subList(0, MAX_CANDIDATES));
        }
        if (activeId == null || !candidateIds.contains(activeId)) {
            activeId = candidateIds.isEmpty() ? null : candidateIds.get(0);
        }

        entity.setAsrHotwordId(activeId);
        entity.setAsrHotwordIds(candidateIds.isEmpty() ? null : candidateIds);
    }

    static List<String> normalize(List<String> candidateIds) {
        Set<String> normalized = new LinkedHashSet<>();
        if (candidateIds != null) {
            for (String candidateId : candidateIds) {
                String trimmed = StringUtils.trimToNull(candidateId);
                if (trimmed != null) {
                    normalized.add(trimmed);
                }
            }
        }
        return new ArrayList<>(normalized);
    }
}

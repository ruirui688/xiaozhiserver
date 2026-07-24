package xiaozhi.modules.agent.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.dao.AiAgentChatHistoryDao;
import xiaozhi.modules.agent.dto.AgentStorageUsageDTO;
import xiaozhi.modules.agent.entity.AgentChatHistoryEntity;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.workspace.dao.WorkspaceDao;
import xiaozhi.modules.workspace.vo.WorkspaceAgentQuotaVO;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceAgentQuotaService {
    static final int DEFAULT_AGENT_LIMIT = 10;
    static final long DEFAULT_STORAGE_LIMIT_MB = 200L;
    private static final long BYTES_PER_MB = 1024L * 1024L;
    private static final int EVICTION_BATCH_SIZE = 100;

    private final SysParamsService sysParamsService;
    private final WorkspaceDao workspaceDao;
    private final AgentDao agentDao;
    private final AiAgentChatHistoryDao chatHistoryDao;
    private final AgentChatAudioCleanupService audioCleanupService;

    public int getAgentLimit() {
        return readPositiveInt(Constant.WORKSPACE_AGENT_MAX_COUNT, DEFAULT_AGENT_LIMIT);
    }

    public long getAgentStorageLimitBytes() {
        long limitMb = readPositiveLong(Constant.WORKSPACE_AGENT_STORAGE_LIMIT_MB, DEFAULT_STORAGE_LIMIT_MB);
        try {
            return Math.multiplyExact(limitMb, BYTES_PER_MB);
        } catch (ArithmeticException error) {
            log.warn("智能体存储额度参数过大，使用默认值: paramCode={}",
                    Constant.WORKSPACE_AGENT_STORAGE_LIMIT_MB);
            return DEFAULT_STORAGE_LIMIT_MB * BYTES_PER_MB;
        }
    }

    public void validateParameterChange(String paramCode, String paramValue) {
        if (!Constant.WORKSPACE_AGENT_MAX_COUNT.equals(paramCode)
                && !Constant.WORKSPACE_AGENT_STORAGE_LIMIT_MB.equals(paramCode)) {
            return;
        }
        if (StringUtils.isBlank(paramValue) || !StringUtils.isNumeric(paramValue.trim())) {
            throw new RenException("工作空间配额必须是正整数");
        }
        long value;
        try {
            value = Long.parseLong(paramValue.trim());
        } catch (NumberFormatException error) {
            throw new RenException("工作空间配额数值过大");
        }
        if (value <= 0) {
            throw new RenException("工作空间配额必须大于0");
        }
        if (Constant.WORKSPACE_AGENT_MAX_COUNT.equals(paramCode) && value > Integer.MAX_VALUE) {
            throw new RenException("工作空间智能体数量上限过大");
        }
        if (Constant.WORKSPACE_AGENT_STORAGE_LIMIT_MB.equals(paramCode)) {
            try {
                Math.multiplyExact(value, BYTES_PER_MB);
            } catch (ArithmeticException error) {
                throw new RenException("单智能体存储容量上限过大");
            }
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireCanCreateAgent(String workspaceId) {
        if (StringUtils.isBlank(workspaceId) || workspaceDao.lockByIdForAgentQuota(workspaceId) == null) {
            throw new RenException("当前工作空间不存在");
        }
        long count = countAgents(workspaceId);
        int limit = getAgentLimit();
        if (count >= limit) {
            throw new RenException("当前工作空间最多可创建%d个智能体，请删除不再使用的智能体或在参数管理中调整上限"
                    .formatted(limit));
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public StorageDecision evaluateStorage(String agentId, long baseBytes, long audioBytes) {
        if (StringUtils.isBlank(agentId) || agentDao.lockByIdForStorageQuota(agentId) == null) {
            throw new RenException("智能体不存在");
        }
        long usedBytes = currentStorageBytes(agentId);
        long limitBytes = getAgentStorageLimitBytes();
        long normalizedBase = Math.max(baseBytes, 0L);
        long normalizedAudio = Math.max(audioBytes, 0L);
        if (normalizedBase > limitBytes) {
            return new StorageDecision(false, false, 0L, usedBytes, limitBytes);
        }

        boolean storeAudio = normalizedAudio > 0L && normalizedAudio <= limitBytes - normalizedBase;
        long recordBytes = normalizedBase + (storeAudio ? normalizedAudio : 0L);
        long remainingUsage = evictOldestUntilFits(agentId, usedBytes, limitBytes - recordBytes);
        if (remainingUsage > limitBytes - recordBytes) {
            log.warn("智能体最旧聊天记录淘汰后仍无法满足容量限制: agentId={}, usedBytes={}, limitBytes={}",
                    agentId, remainingUsage, limitBytes);
            return new StorageDecision(false, false, 0L, remainingUsage, limitBytes);
        }
        return new StorageDecision(true, storeAudio, recordBytes, remainingUsage, limitBytes);
    }

    public Map<String, Long> getStorageUsageByAgentIds(List<String> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return Map.of();
        }
        return chatHistoryDao.getStorageUsageByAgentIds(agentIds).stream()
                .collect(Collectors.toMap(
                        AgentStorageUsageDTO::getAgentId,
                        usage -> usage.getStorageBytes() == null ? 0L : usage.getStorageBytes(),
                        Long::sum));
    }

    public WorkspaceAgentQuotaVO getWorkspaceQuota(String workspaceId) {
        return WorkspaceAgentQuotaVO.builder()
                .workspaceId(workspaceId)
                .agentCount(countAgents(workspaceId))
                .agentLimit(getAgentLimit())
                .agentStorageLimitBytes(getAgentStorageLimitBytes())
                .build();
    }

    private long countAgents(String workspaceId) {
        return agentDao.selectCount(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getWorkspaceId, workspaceId));
    }

    private long currentStorageBytes(String agentId) {
        Long value = chatHistoryDao.getStorageUsageBytes(agentId);
        return value == null ? 0L : Math.max(value, 0L);
    }

    private long evictOldestUntilFits(String agentId, long usedBytes, long maxExistingBytes) {
        if (usedBytes <= maxExistingBytes) {
            return usedBytes;
        }

        long originalUsedBytes = usedBytes;
        int evictedRecords = 0;
        List<String> audioIds = new ArrayList<>();
        while (usedBytes > maxExistingBytes) {
            List<AgentChatHistoryEntity> oldest = chatHistoryDao.getOldestForStorageEviction(
                    agentId, EVICTION_BATCH_SIZE);
            if (oldest == null || oldest.isEmpty()) {
                break;
            }

            long bytesToFree = usedBytes - maxExistingBytes;
            long selectedBytes = 0L;
            List<Long> historyIds = new ArrayList<>();
            for (AgentChatHistoryEntity history : oldest) {
                historyIds.add(history.getId());
                if (StringUtils.isNotBlank(history.getAudioId())) {
                    audioIds.add(history.getAudioId());
                }
                selectedBytes += Math.max(history.getStorageBytes() == null ? 0L : history.getStorageBytes(), 0L);
                if (selectedBytes >= bytesToFree) {
                    break;
                }
            }

            int deleted = chatHistoryDao.deleteByStorageEvictionIds(historyIds);
            if (deleted <= 0) {
                break;
            }
            evictedRecords += deleted;
            usedBytes = currentStorageBytes(agentId);
        }

        audioCleanupService.scheduleAfterCommit(audioIds);
        if (evictedRecords > 0) {
            log.info("智能体聊天容量滚动淘汰完成: agentId={}, records={}, freedBytes={}",
                    agentId, evictedRecords, Math.max(originalUsedBytes - usedBytes, 0L));
        }
        return usedBytes;
    }

    private int readPositiveInt(String paramCode, int fallback) {
        long value = readPositiveLong(paramCode, fallback);
        return value <= Integer.MAX_VALUE ? (int) value : fallback;
    }

    private long readPositiveLong(String paramCode, long fallback) {
        String rawValue = sysParamsService.getValue(paramCode, true);
        if (StringUtils.isNotBlank(rawValue)) {
            try {
                long value = Long.parseLong(rawValue.trim());
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // 统一使用安全默认值，并只记录参数名。
            }
        }
        log.warn("工作空间配额参数无效，使用默认值: paramCode={}", paramCode);
        return fallback;
    }

    public record StorageDecision(
            boolean storeRecord,
            boolean storeAudio,
            long recordBytes,
            long usedBytes,
            long limitBytes) {
    }
}

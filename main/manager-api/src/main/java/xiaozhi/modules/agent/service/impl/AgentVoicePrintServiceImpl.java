package xiaozhi.modules.agent.service.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.ConvertUtils;
import xiaozhi.modules.agent.dao.AgentVoicePrintDao;
import xiaozhi.modules.agent.dto.AgentVoicePrintSaveDTO;
import xiaozhi.modules.agent.dto.AgentVoicePrintUpdateDTO;
import xiaozhi.modules.agent.dto.IdentifyVoicePrintResponse;
import xiaozhi.modules.agent.entity.AgentVoicePrintEntity;
import xiaozhi.modules.agent.service.AgentChatAudioService;
import xiaozhi.modules.agent.service.AgentChatHistoryService;
import xiaozhi.modules.agent.service.AgentVoicePrintService;
import xiaozhi.modules.agent.voiceprint.VoiceprintApiClient;
import xiaozhi.modules.agent.vo.AgentVoicePrintVO;

/**
 * 智能体声纹管理。
 */
@Service
@Slf4j
public class AgentVoicePrintServiceImpl extends ServiceImpl<AgentVoicePrintDao, AgentVoicePrintEntity>
        implements AgentVoicePrintService {
    public static final int MAX_VOICEPRINTS_PER_AGENT = 5;
    private static final double DUPLICATE_RECOGNITION_THRESHOLD = 0.5;

    private final AgentChatAudioService agentChatAudioService;
    private final AgentChatHistoryService agentChatHistoryService;
    private final TransactionTemplate transactionTemplate;
    private final VoiceprintApiClient voiceprintApiClient;

    public AgentVoicePrintServiceImpl(
            AgentChatAudioService agentChatAudioService,
            AgentChatHistoryService agentChatHistoryService,
            TransactionTemplate transactionTemplate,
            VoiceprintApiClient voiceprintApiClient) {
        this.agentChatAudioService = agentChatAudioService;
        this.agentChatHistoryService = agentChatHistoryService;
        this.transactionTemplate = transactionTemplate;
        this.voiceprintApiClient = voiceprintApiClient;
    }

    @Override
    public boolean insert(AgentVoicePrintSaveDTO dto) {
        ensureVoiceprintCapacity(dto.getAgentId());

        ByteArrayResource resource = getVoicePrintAudioWAV(dto.getAgentId(), dto.getAudioId());
        IdentifyVoicePrintResponse response = identifyVoicePrint(dto.getAgentId(), resource);
        rejectDuplicateVoiceprint(response, null);

        AgentVoicePrintEntity entity = ConvertUtils.sourceToTarget(dto, AgentVoicePrintEntity.class);
        entity.setRemoteUserId(dto.getAgentId());

        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            try {
                // 并发创建时在事务内再检查一次；远程服务还会原子执行最终的 5 条上限。
                ensureVoiceprintCapacity(dto.getAgentId());
                if (baseMapper.insert(entity) != 1) {
                    status.setRollbackOnly();
                    return false;
                }
                voiceprintApiClient.registerVoiceprint(
                        entity.getRemoteUserId(), entity.getId(), resource);
                return true;
            } catch (RenException e) {
                status.setRollbackOnly();
                throw e;
            } catch (Exception e) {
                status.setRollbackOnly();
                log.error("保存声纹失败: agentId={}, errorType={}", dto.getAgentId(), e.getClass().getSimpleName());
                throw new RenException(ErrorCode.VOICE_PRINT_SAVE_ERROR, e);
            }
        }));
    }

    @Override
    public boolean delete(Long userId, String voicePrintId) {
        AgentVoicePrintEntity existing = baseMapper.selectById(voicePrintId);
        if (existing == null) {
            return false;
        }

        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            try {
                int row = baseMapper.delete(new LambdaQueryWrapper<AgentVoicePrintEntity>()
                        .eq(AgentVoicePrintEntity::getId, voicePrintId)
                        .eq(AgentVoicePrintEntity::getAgentId, existing.getAgentId()));
                if (row != 1) {
                    status.setRollbackOnly();
                    return false;
                }

                // 远程删除失败时回滚本地记录，避免远程孤儿声纹继续占用 5 条额度。
                voiceprintApiClient.deleteVoiceprint(remoteUserId(existing), voicePrintId);
                return true;
            } catch (RenException e) {
                status.setRollbackOnly();
                throw e;
            } catch (Exception e) {
                status.setRollbackOnly();
                log.error("删除声纹失败: id={}, errorType={}", voicePrintId, e.getClass().getSimpleName());
                throw new RenException(ErrorCode.VOICEPRINT_DELETE_ERROR, e);
            }
        }));
    }

    @Override
    public List<AgentVoicePrintVO> list(Long userId, String agentId) {
        List<AgentVoicePrintEntity> list = baseMapper.selectList(
                new LambdaQueryWrapper<AgentVoicePrintEntity>()
                        .eq(AgentVoicePrintEntity::getAgentId, agentId)
                        .orderByAsc(AgentVoicePrintEntity::getCreateDate));
        return ConvertUtils.sourceToTarget(list, AgentVoicePrintVO.class);
    }

    @Override
    public boolean update(Long userId, AgentVoicePrintUpdateDTO dto) {
        AgentVoicePrintEntity existing = baseMapper.selectById(dto.getId());
        if (existing == null) {
            return false;
        }

        String audioId = dto.getAudioId();
        ByteArrayResource resource = null;
        if (StringUtils.isNotBlank(audioId) && !audioId.equals(existing.getAudioId())) {
            resource = getVoicePrintAudioWAV(existing.getAgentId(), audioId);
            IdentifyVoicePrintResponse response = identifyVoicePrint(existing.getAgentId(), resource);
            rejectDuplicateVoiceprint(response, dto.getId());
        }

        ByteArrayResource updatedResource = resource;
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            try {
                AgentVoicePrintEntity entity = ConvertUtils.sourceToTarget(dto, AgentVoicePrintEntity.class);
                entity.setAgentId(existing.getAgentId());
                entity.setRemoteUserId(
                        updatedResource == null ? remoteUserId(existing) : existing.getAgentId());
                if (baseMapper.updateById(entity) != 1) {
                    status.setRollbackOnly();
                    return false;
                }

                if (updatedResource != null) {
                    // 同一个 user_id + speaker_id 重复注册会覆盖声纹，无需先删除再注册。
                    voiceprintApiClient.registerVoiceprint(
                            entity.getRemoteUserId(), entity.getId(), updatedResource);
                    String previousRemoteUserId = remoteUserId(existing);
                    if (!previousRemoteUserId.equals(entity.getRemoteUserId())) {
                        voiceprintApiClient.deleteVoiceprint(previousRemoteUserId, entity.getId());
                    }
                }
                return true;
            } catch (RenException e) {
                status.setRollbackOnly();
                throw e;
            } catch (Exception e) {
                status.setRollbackOnly();
                log.error("修改声纹失败: id={}, errorType={}", dto.getId(), e.getClass().getSimpleName());
                throw new RenException(ErrorCode.VOICEPRINT_UPDATE_ADMIN_ERROR, e);
            }
        }));
    }

    @Override
    public String getAgentIdByVoicePrintId(String voicePrintId) {
        AgentVoicePrintEntity entity = baseMapper.selectById(voicePrintId);
        return entity == null ? null : entity.getAgentId();
    }

    private void ensureVoiceprintCapacity(String agentId) {
        Long count = baseMapper.selectCount(new LambdaQueryWrapper<AgentVoicePrintEntity>()
                .eq(AgentVoicePrintEntity::getAgentId, agentId));
        if (count != null && count >= MAX_VOICEPRINTS_PER_AGENT) {
            throw new RenException(ErrorCode.AGENT_VOICEPRINT_LIMIT_REACHED);
        }
    }

    private void rejectDuplicateVoiceprint(IdentifyVoicePrintResponse response, String allowedVoiceprintId) {
        if (response == null
                || response.getScore() == null
                || !Double.isFinite(response.getScore())
                || response.getScore() <= DUPLICATE_RECOGNITION_THRESHOLD
                || StringUtils.isBlank(response.getSpeakerId())
                || response.getSpeakerId().equals(allowedVoiceprintId)) {
            return;
        }

        AgentVoicePrintEntity existingVoicePrint = baseMapper.selectById(response.getSpeakerId());
        String existingUserName = existingVoicePrint != null
                ? existingVoicePrint.getSourceName()
                : "未知用户";
        int errorCode = allowedVoiceprintId == null
                ? ErrorCode.VOICEPRINT_ALREADY_REGISTERED
                : ErrorCode.VOICEPRINT_UPDATE_NOT_ALLOWED;
        throw new RenException(errorCode, existingUserName);
    }

    private IdentifyVoicePrintResponse identifyVoicePrint(String agentId, ByteArrayResource resource) {
        List<AgentVoicePrintEntity> voiceprints = baseMapper.selectList(
                new LambdaQueryWrapper<AgentVoicePrintEntity>()
                        .select(AgentVoicePrintEntity::getId, AgentVoicePrintEntity::getRemoteUserId)
                        .eq(AgentVoicePrintEntity::getAgentId, agentId));
        if (voiceprints.isEmpty()) {
            return null;
        }

        Map<String, List<String>> candidatesByRemoteUser = new LinkedHashMap<>();
        for (AgentVoicePrintEntity voiceprint : voiceprints) {
            candidatesByRemoteUser.computeIfAbsent(remoteUserId(voiceprint), ignored -> new java.util.ArrayList<>())
                    .add(voiceprint.getId());
        }

        IdentifyVoicePrintResponse best = null;
        for (Map.Entry<String, List<String>> entry : candidatesByRemoteUser.entrySet()) {
            IdentifyVoicePrintResponse current = voiceprintApiClient.identifyVoiceprint(
                    entry.getKey(), entry.getValue(), resource);
            if (current != null
                    && current.getScore() != null
                    && Double.isFinite(current.getScore())
                    && (best == null || best.getScore() == null || current.getScore() > best.getScore())) {
                best = current;
            }
        }
        return best;
    }

    private String remoteUserId(AgentVoicePrintEntity entity) {
        return StringUtils.defaultIfBlank(entity.getRemoteUserId(), VoiceprintApiClient.LEGACY_USER_ID);
    }

    private ByteArrayResource getVoicePrintAudioWAV(String agentId, String audioId) {
        if (!agentChatHistoryService.isAudioOwnedByAgent(audioId, agentId)) {
            throw new RenException(ErrorCode.VOICEPRINT_AUDIO_NOT_BELONG_AGENT);
        }
        byte[] audio = agentChatAudioService.getAudio(audioId);
        if (audio == null || audio.length == 0) {
            throw new RenException(ErrorCode.VOICEPRINT_AUDIO_EMPTY);
        }
        return new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return "voiceprint.wav";
            }
        };
    }
}

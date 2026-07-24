package xiaozhi.modules.agent.service.biz.impl;

import java.util.Base64;
import java.util.Date;
import java.util.Objects;
import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.redis.RedisKeys;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.modules.agent.dto.AgentChatHistoryReportDTO;
import xiaozhi.modules.agent.entity.AgentChatHistoryEntity;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.service.AgentChatAudioService;
import xiaozhi.modules.agent.service.AgentChatHistoryService;
import xiaozhi.modules.agent.service.AgentChatSummaryService;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.service.biz.AgentChatHistoryBizService;
import xiaozhi.modules.agent.service.impl.WorkspaceAgentQuotaService;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceService;

/**
 * {@link AgentChatHistoryBizService} impl
 *
 * @author Goody
 * @version 1.0, 2025/4/30
 * @since 1.0.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AgentChatHistoryBizServiceImpl implements AgentChatHistoryBizService {
    private final AgentService agentService;
    private final AgentChatHistoryService agentChatHistoryService;
    private final AgentChatAudioService agentChatAudioService;
    private final AgentChatSummaryService agentChatSummaryService;
    private final RedisUtils redisUtils;
    private final DeviceService deviceService;
    private final WorkspaceAgentQuotaService workspaceAgentQuotaService;

    /**
     * 处理聊天记录上报，包括文件上传和相关信息记录
     *
     * @param report 包含聊天上报所需信息的输入对象
     * @return 上传结果，true表示成功，false表示失败
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean report(AgentChatHistoryReportDTO report) {
        String macAddress = report.getMacAddress();
        Byte chatType = report.getChatType();
        Long reportTimeMillis = null != report.getReportTime() ? report.getReportTime()
                : System.currentTimeMillis();
        log.info("小智设备聊天上报请求: macAddress={}, type={} reportTime={}", macAddress, chatType, reportTimeMillis);

        // 根据设备MAC地址查询对应的默认智能体，判断是否需要上报
        AgentEntity agentEntity = agentService.getDefaultAgentByMacAddress(macAddress);
        if (agentEntity == null) {
            return Boolean.FALSE;
        }

        Integer chatHistoryConf = agentEntity.getChatHistoryConf();
        String agentId = agentEntity.getId();

        if (Objects.equals(chatHistoryConf, Constant.ChatHistoryConfEnum.RECORD_TEXT.getCode())
                || Objects.equals(chatHistoryConf, Constant.ChatHistoryConfEnum.RECORD_TEXT_AUDIO.getCode())) {
            saveChatHistory(report, agentEntity, reportTimeMillis,
                    Objects.equals(chatHistoryConf, Constant.ChatHistoryConfEnum.RECORD_TEXT_AUDIO.getCode()));
        }

        // 更新设备最后对话时间
        redisUtils.set(RedisKeys.getAgentDeviceLastConnectedAtById(agentId), new Date());

        // 更新设备最后连接时间
        DeviceEntity device = deviceService.getDeviceByMacAddress(macAddress);
        if (device != null) {
            deviceService.updateDeviceConnectionInfo(agentId, device.getId(), null);
        } else {
            log.warn("聊天记录上报时，未找到mac地址为 {} 的设备", macAddress);
        }

        return Boolean.TRUE;
    }

    private void saveChatHistory(AgentChatHistoryReportDTO report, AgentEntity agent, Long reportTime,
            boolean audioEnabled) {
        String metricsJson = report.getMetrics() == null ? null : JsonUtils.toJsonString(report.getMetrics());
        long baseBytes = utf8Length(report.getContent()) + utf8Length(metricsJson);
        byte[] audioData = audioEnabled ? decodeAudio(report.getAudioBase64()) : null;
        long audioBytes = audioData == null ? 0L : audioData.length;
        WorkspaceAgentQuotaService.StorageDecision decision = workspaceAgentQuotaService.evaluateStorage(
                agent.getId(), baseBytes, audioBytes);

        if (!decision.storeRecord()) {
            log.warn("单条聊天文本超过智能体存储上限，无法完整保存: agentId={}, usedBytes={}, limitBytes={}",
                    agent.getId(), decision.usedBytes(), decision.limitBytes());
            return;
        }

        String audioId = null;
        if (decision.storeAudio()) {
            try {
                audioId = agentChatAudioService.saveAudio(audioData);
            } catch (RuntimeException error) {
                log.error("聊天记录音频保存失败，仅保存文本: agentId={}, errorType={}",
                        agent.getId(), error.getClass().getSimpleName());
            }
        } else if (audioBytes > 0L) {
            log.warn("智能体聊天记录音频超出存储上限，仅保存文本: agentId={}, usedBytes={}, limitBytes={}",
                    agent.getId(), decision.usedBytes(), decision.limitBytes());
        }

        // 构建聊天记录实体
        AgentChatHistoryEntity entity = AgentChatHistoryEntity.builder()
                .macAddress(report.getMacAddress())
                .agentId(agent.getId())
                .workspaceId(agent.getWorkspaceId())
                .sessionId(report.getSessionId())
                .chatType(report.getChatType())
                .content(report.getContent())
                .audioId(audioId)
                .metricsJson(metricsJson)
                .storageBytes(baseBytes + (audioId == null ? 0L : audioBytes))
                .createdAt(new Date(reportTime))
                // NOTE(haotian): 2025/5/26 updateAt可以不设置，重点是createAt，而且这样可以看到上报延迟
                .build();

        // 保存数据
        agentChatHistoryService.save(entity);

        log.info("设备 {} 对应智能体 {} 上报成功", report.getMacAddress(), agent.getId());
    }

    private byte[] decodeAudio(String audioBase64) {
        if (audioBase64 == null || audioBase64.isEmpty()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(audioBase64);
        } catch (IllegalArgumentException error) {
            log.warn("聊天记录音频Base64格式无效，仅保存文本");
            return null;
        }
    }

    private long utf8Length(String value) {
        return value == null ? 0L : value.getBytes(StandardCharsets.UTF_8).length;
    }
}

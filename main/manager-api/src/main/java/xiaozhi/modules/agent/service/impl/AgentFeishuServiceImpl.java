package xiaozhi.modules.agent.service.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.security.CredentialCipher;
import xiaozhi.modules.agent.dao.AgentFeishuConfigDao;
import xiaozhi.modules.agent.dto.AgentFeishuConfigSaveDTO;
import xiaozhi.modules.agent.dto.FeishuRuntimeActionDTO;
import xiaozhi.modules.agent.entity.AgentFeishuConfigEntity;
import xiaozhi.modules.agent.feishu.FeishuOpenApiClient;
import xiaozhi.modules.agent.service.AgentFeishuService;
import xiaozhi.modules.agent.vo.AgentFeishuConfigVO;
import xiaozhi.modules.agent.vo.FeishuChatVO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceService;

@Service
@RequiredArgsConstructor
public class AgentFeishuServiceImpl implements AgentFeishuService {
    private static final String CIPHER_SCOPE_PREFIX = "agent-feishu:";

    private final AgentFeishuConfigDao configDao;
    private final CredentialCipher credentialCipher;
    private final FeishuOpenApiClient feishuClient;
    private final DeviceService deviceService;

    @Override
    public AgentFeishuConfigVO getConfig(String agentId) {
        AgentFeishuConfigEntity entity = find(agentId);
        if (entity == null) {
            return AgentFeishuConfigVO.builder().build();
        }
        return AgentFeishuConfigVO.builder()
                .appId(entity.getAppId())
                .secretConfigured(StringUtils.isNotBlank(entity.getAppSecretCiphertext()))
                .defaultChatId(entity.getDefaultChatId())
                .calendarId(entity.getCalendarId())
                .taskAssigneeOpenId(entity.getTaskAssigneeOpenId())
                .enabled(Boolean.TRUE.equals(entity.getEnabled()))
                .meetingReportEnabled(Boolean.TRUE.equals(entity.getMeetingReportEnabled()))
                .messageSendEnabled(Boolean.TRUE.equals(entity.getMessageSendEnabled()))
                .messageReadEnabled(Boolean.TRUE.equals(entity.getMessageReadEnabled()))
                .taskReadEnabled(Boolean.TRUE.equals(entity.getTaskReadEnabled()))
                .taskCreateEnabled(Boolean.TRUE.equals(entity.getTaskCreateEnabled()))
                .calendarCreateEnabled(Boolean.TRUE.equals(entity.getCalendarCreateEnabled()))
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveConfig(String agentId, AgentFeishuConfigSaveDTO dto, Long operatorId) {
        AgentFeishuConfigEntity entity = find(agentId);
        boolean creating = entity == null;
        if (creating) {
            entity = new AgentFeishuConfigEntity();
            entity.setAgentId(agentId);
            entity.setCreator(operatorId);
        }

        String appId = StringUtils.trim(dto.getAppId());
        String appSecret = StringUtils.trimToNull(dto.getAppSecret());
        if (appSecret != null) {
            entity.setAppSecretCiphertext(credentialCipher.encrypt(scope(agentId), appSecret));
        } else if (creating || StringUtils.isBlank(entity.getAppSecretCiphertext())) {
            throw new RenException("首次绑定飞书时必须填写App Secret");
        } else if (!StringUtils.equals(appId, entity.getAppId())) {
            throw new RenException("修改App ID时必须同时填写对应的App Secret");
        }

        entity.setAppId(appId);
        entity.setDefaultChatId(StringUtils.trimToNull(dto.getDefaultChatId()));
        entity.setCalendarId(StringUtils.trimToNull(dto.getCalendarId()));
        entity.setTaskAssigneeOpenId(StringUtils.trimToNull(dto.getTaskAssigneeOpenId()));
        entity.setEnabled(defaultTrue(dto.getEnabled()));
        entity.setMeetingReportEnabled(defaultTrue(dto.getMeetingReportEnabled()));
        entity.setMessageSendEnabled(Boolean.TRUE.equals(dto.getMessageSendEnabled()));
        entity.setMessageReadEnabled(Boolean.TRUE.equals(dto.getMessageReadEnabled()));
        entity.setTaskReadEnabled(Boolean.TRUE.equals(dto.getTaskReadEnabled()));
        entity.setTaskCreateEnabled(Boolean.TRUE.equals(dto.getTaskCreateEnabled()));
        entity.setCalendarCreateEnabled(Boolean.TRUE.equals(dto.getCalendarCreateEnabled()));
        entity.setUpdater(operatorId);
        if (creating) {
            configDao.insert(entity);
        } else {
            configDao.updateById(entity);
        }
    }

    @Override
    public void deleteConfig(String agentId) {
        configDao.delete(new LambdaQueryWrapper<AgentFeishuConfigEntity>()
                .eq(AgentFeishuConfigEntity::getAgentId, agentId));
    }

    @Override
    public List<FeishuChatVO> testConnection(String agentId, AgentFeishuConfigSaveDTO dto) {
        Credentials credentials = resolveTestCredentials(agentId, dto);
        return feishuClient.listChats(credentials.appId(), credentials.appSecret());
    }

    @Override
    public List<FeishuChatVO> listChats(String agentId) {
        ConfigContext context = requireEnabled(agentId);
        return feishuClient.listChats(context.entity().getAppId(), context.appSecret());
    }

    @Override
    public Map<String, Object> sendTestMessage(String agentId) {
        ConfigContext context = requireEnabled(agentId);
        return feishuClient.sendText(
                context.entity().getAppId(),
                context.appSecret(),
                context.entity().getDefaultChatId(),
                "小智智能体飞书连接测试成功。");
    }

    @Override
    public Map<String, Object> executeRuntimeAction(String agentId, FeishuRuntimeActionDTO dto) {
        validateDeviceBinding(agentId, dto.getMacAddress());
        ConfigContext context = requireEnabled(agentId);
        AgentFeishuConfigEntity config = context.entity();
        String action = StringUtils.defaultString(dto.getAction()).trim().toLowerCase(Locale.ROOT);
        return switch (action) {
            case "send_message" -> sendMessage(context, dto);
            case "publish_meeting_report" -> publishMeetingReport(context, dto);
            case "list_messages" -> listMessages(context);
            case "list_tasks" -> listTasks(context);
            case "create_task" -> createTask(context, dto);
            case "create_calendar_event" -> createCalendarEvent(context, dto);
            default -> throw new RenException("不支持的飞书动作: " + action);
        };
    }

    @Override
    public Map<String, Object> getRuntimePluginConfig(String agentId) {
        AgentFeishuConfigEntity config = find(agentId);
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())) {
            return Map.of();
        }
        boolean sendMessage = Boolean.TRUE.equals(config.getMessageSendEnabled());
        boolean listMessages = Boolean.TRUE.equals(config.getMessageReadEnabled());
        boolean listTasks = Boolean.TRUE.equals(config.getTaskReadEnabled());
        boolean createTask = Boolean.TRUE.equals(config.getTaskCreateEnabled());
        boolean createCalendarEvent = Boolean.TRUE.equals(config.getCalendarCreateEnabled());
        if (!sendMessage && !listMessages && !listTasks && !createTask && !createCalendarEvent) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", true);
        result.put("send_message", sendMessage);
        result.put("list_messages", listMessages);
        result.put("list_tasks", listTasks);
        result.put("create_task", createTask);
        result.put("create_calendar_event", createCalendarEvent);
        return result;
    }

    private Map<String, Object> publishMeetingReport(ConfigContext context, FeishuRuntimeActionDTO dto) {
        AgentFeishuConfigEntity config = context.entity();
        if (!Boolean.TRUE.equals(config.getMeetingReportEnabled())) {
            return Map.of("skipped", true, "reason", "meeting_report_disabled");
        }
        return feishuClient.sendMeetingReport(
                config.getAppId(),
                context.appSecret(),
                config.getDefaultChatId(),
                StringUtils.defaultIfBlank(dto.getTitle(), "会议纪要"),
                dto.getContent());
    }

    private Map<String, Object> sendMessage(ConfigContext context, FeishuRuntimeActionDTO dto) {
        AgentFeishuConfigEntity config = context.entity();
        if (!Boolean.TRUE.equals(config.getMessageSendEnabled())) {
            throw new RenException("当前智能体未开启飞书群消息发送");
        }
        return feishuClient.sendText(
                config.getAppId(), context.appSecret(), config.getDefaultChatId(), dto.getContent());
    }

    private Map<String, Object> listMessages(ConfigContext context) {
        AgentFeishuConfigEntity config = context.entity();
        if (!Boolean.TRUE.equals(config.getMessageReadEnabled())) {
            throw new RenException("当前智能体未开启飞书群消息读取");
        }
        return Map.of(
                "messages",
                feishuClient.listMessages(
                        config.getAppId(), context.appSecret(), config.getDefaultChatId(), 10));
    }

    private Map<String, Object> createTask(ConfigContext context, FeishuRuntimeActionDTO dto) {
        AgentFeishuConfigEntity config = context.entity();
        if (!Boolean.TRUE.equals(config.getTaskCreateEnabled())) {
            throw new RenException("当前智能体未开启飞书任务创建");
        }
        return feishuClient.createTask(
                config.getAppId(),
                context.appSecret(),
                dto.getTitle(),
                dto.getDescription(),
                dto.getDueTimestamp(),
                config.getTaskAssigneeOpenId());
    }

    private Map<String, Object> listTasks(ConfigContext context) {
        AgentFeishuConfigEntity config = context.entity();
        if (!Boolean.TRUE.equals(config.getTaskReadEnabled())) {
            throw new RenException("当前智能体未开启飞书任务读取");
        }
        return Map.of(
                "tasks",
                feishuClient.listTasks(config.getAppId(), context.appSecret(), 20));
    }

    private Map<String, Object> createCalendarEvent(ConfigContext context, FeishuRuntimeActionDTO dto) {
        AgentFeishuConfigEntity config = context.entity();
        if (!Boolean.TRUE.equals(config.getCalendarCreateEnabled())) {
            throw new RenException("当前智能体未开启飞书日程创建");
        }
        return feishuClient.createCalendarEvent(
                config.getAppId(),
                context.appSecret(),
                config.getCalendarId(),
                dto.getTitle(),
                dto.getDescription(),
                dto.getStartTimestamp(),
                dto.getEndTimestamp(),
                dto.getTimezone());
    }

    private Credentials resolveTestCredentials(String agentId, AgentFeishuConfigSaveDTO dto) {
        AgentFeishuConfigEntity existing = find(agentId);
        String appId = StringUtils.trim(dto.getAppId());
        String appSecret = StringUtils.trimToNull(dto.getAppSecret());
        if (appSecret == null) {
            if (existing == null || !StringUtils.equals(appId, existing.getAppId())) {
                throw new RenException("测试新飞书应用时必须填写App Secret");
            }
            appSecret = decrypt(existing);
        }
        return new Credentials(appId, appSecret);
    }

    private ConfigContext requireEnabled(String agentId) {
        AgentFeishuConfigEntity entity = find(agentId);
        if (entity == null || !Boolean.TRUE.equals(entity.getEnabled())) {
            throw new RenException("当前智能体尚未启用飞书集成");
        }
        return new ConfigContext(entity, decrypt(entity));
    }

    private AgentFeishuConfigEntity find(String agentId) {
        return configDao.selectOne(new LambdaQueryWrapper<AgentFeishuConfigEntity>()
                .eq(AgentFeishuConfigEntity::getAgentId, agentId)
                .last("limit 1"));
    }

    private void validateDeviceBinding(String agentId, String macAddress) {
        DeviceEntity device = deviceService.getDeviceByMacAddress(macAddress);
        if (device == null || !StringUtils.equals(agentId, device.getAgentId())) {
            throw new RenException("设备与智能体绑定关系无效");
        }
    }

    private String decrypt(AgentFeishuConfigEntity entity) {
        return credentialCipher.decrypt(scope(entity.getAgentId()), entity.getAppSecretCiphertext());
    }

    private String scope(String agentId) {
        return CIPHER_SCOPE_PREFIX + agentId;
    }

    private boolean defaultTrue(Boolean value) {
        return value == null || Boolean.TRUE.equals(value);
    }

    private record Credentials(String appId, String appSecret) {
    }

    private record ConfigContext(AgentFeishuConfigEntity entity, String appSecret) {
    }
}

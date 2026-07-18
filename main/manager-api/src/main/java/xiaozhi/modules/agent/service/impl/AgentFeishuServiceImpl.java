package xiaozhi.modules.agent.service.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.security.CredentialCipher;
import xiaozhi.modules.agent.dao.AgentFeishuConfigDao;
import xiaozhi.modules.agent.dto.AgentFeishuConfigSaveDTO;
import xiaozhi.modules.agent.dto.FeishuRuntimeActionDTO;
import xiaozhi.modules.agent.entity.AgentFeishuConfigEntity;
import xiaozhi.modules.agent.feishu.FeishuOpenApiClient;
import xiaozhi.modules.agent.feishu.FeishuDeviceAnnouncementClient;
import xiaozhi.modules.agent.service.AgentFeishuService;
import xiaozhi.modules.agent.vo.AgentFeishuConfigVO;
import xiaozhi.modules.agent.vo.FeishuChatVO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.service.DeviceService;

@Service
@RequiredArgsConstructor
public class AgentFeishuServiceImpl implements AgentFeishuService {
    private static final String CIPHER_SCOPE_PREFIX = "agent-feishu:";
    private static final String EVENT_TOKEN_SCOPE_PREFIX = "agent-feishu-event:";
    private static final long EVENT_DEDUP_TTL_MILLIS = 10 * 60 * 1000L;
    private static final int EVENT_RATE_LIMIT_PER_MINUTE = 120;

    private final AgentFeishuConfigDao configDao;
    private final CredentialCipher credentialCipher;
    private final FeishuOpenApiClient feishuClient;
    private final FeishuDeviceAnnouncementClient announcementClient;
    private final DeviceService deviceService;
    private final ObjectMapper objectMapper;
    private final Map<String, Long> handledEventIds = new ConcurrentHashMap<>();
    private final Map<String, EventRateWindow> eventRateWindows = new ConcurrentHashMap<>();

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
                .messageBroadcastEnabled(Boolean.TRUE.equals(entity.getMessageBroadcastEnabled()))
                .eventVerificationTokenConfigured(
                        StringUtils.isNotBlank(entity.getEventVerificationTokenCiphertext()))
                .taskReadEnabled(Boolean.TRUE.equals(entity.getTaskReadEnabled()))
                .taskCreateEnabled(Boolean.TRUE.equals(entity.getTaskCreateEnabled()))
                .calendarCreateEnabled(Boolean.TRUE.equals(entity.getCalendarCreateEnabled()))
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveConfig(String agentId, AgentFeishuConfigSaveDTO dto, Long operatorId) {
        validateGroupConfiguration(dto);
        AgentFeishuConfigEntity entity = find(agentId);
        boolean creating = entity == null;
        if (creating) {
            entity = new AgentFeishuConfigEntity();
            entity.setAgentId(agentId);
            entity.setCreator(operatorId);
        }

        String appId = StringUtils.trim(dto.getAppId());
        String appSecret = StringUtils.trimToNull(dto.getAppSecret());
        boolean appChanged = !creating && !StringUtils.equals(appId, entity.getAppId());
        if (appSecret != null) {
            entity.setAppSecretCiphertext(credentialCipher.encrypt(scope(agentId), appSecret));
        } else if (creating || StringUtils.isBlank(entity.getAppSecretCiphertext())) {
            throw new RenException("首次绑定飞书时必须填写App Secret");
        } else if (appChanged) {
            throw new RenException("修改App ID时必须同时填写对应的App Secret");
        }

        entity.setAppId(appId);
        entity.setDefaultChatId(StringUtils.trimToNull(dto.getDefaultChatId()));
        entity.setCalendarId(StringUtils.trimToNull(dto.getCalendarId()));
        entity.setTaskAssigneeOpenId(StringUtils.trimToNull(dto.getTaskAssigneeOpenId()));
        entity.setEnabled(Boolean.TRUE.equals(dto.getEnabled()));
        entity.setMeetingReportEnabled(Boolean.TRUE.equals(dto.getMeetingReportEnabled()));
        entity.setMessageSendEnabled(Boolean.TRUE.equals(dto.getMessageSendEnabled()));
        entity.setMessageReadEnabled(Boolean.TRUE.equals(dto.getMessageReadEnabled()));
        entity.setMessageBroadcastEnabled(Boolean.TRUE.equals(dto.getMessageBroadcastEnabled()));
        String eventToken = StringUtils.trimToNull(dto.getEventVerificationToken());
        if (eventToken != null) {
            entity.setEventVerificationTokenCiphertext(
                    credentialCipher.encrypt(eventScope(agentId), eventToken));
        } else if (Boolean.TRUE.equals(dto.getMessageBroadcastEnabled())
                && (appChanged || StringUtils.isBlank(entity.getEventVerificationTokenCiphertext()))) {
            throw new RenException("开启群消息到达播报或修改App ID时，必须填写飞书事件订阅Verification Token");
        } else if (appChanged) {
            entity.setEventVerificationTokenCiphertext(null);
        }
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
        ConfigContext context = requireConfigured(agentId);
        return feishuClient.listChats(context.entity().getAppId(), context.appSecret());
    }

    @Override
    public Map<String, Object> sendTestMessage(String agentId) {
        ConfigContext context = requireConfigured(agentId);
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

    @Override
    public Map<String, Object> handleEvent(String agentId, Map<String, Object> payload) {
        enforceEventRateLimit(agentId);
        AgentFeishuConfigEntity config = find(agentId);
        if (config == null || StringUtils.isBlank(config.getEventVerificationTokenCiphertext())) {
            throw new RenException("飞书事件订阅尚未配置");
        }
        Map<String, Object> header = map(payload.get("header"));
        String receivedToken = StringUtils.defaultIfBlank(
                text(payload.get("token")), text(header.get("token")));
        String expectedToken = credentialCipher.decrypt(
                eventScope(agentId), config.getEventVerificationTokenCiphertext());
        if (!constantTimeEquals(receivedToken, expectedToken)) {
            throw new RenException("飞书事件订阅Verification Token校验失败");
        }

        String challenge = text(payload.get("challenge"));
        if (StringUtils.isNotBlank(challenge)) {
            return Map.of("challenge", challenge);
        }
        if (!Boolean.TRUE.equals(config.getEnabled())
                || !Boolean.TRUE.equals(config.getMessageBroadcastEnabled())) {
            return Map.of("code", 0, "ignored", true, "reason", "broadcast_disabled");
        }
        if (!StringUtils.equals("im.message.receive_v1", text(header.get("event_type")))) {
            return Map.of("code", 0, "ignored", true, "reason", "unsupported_event");
        }
        String eventAppId = text(header.get("app_id"));
        if (StringUtils.isNotBlank(eventAppId) && !StringUtils.equals(eventAppId, config.getAppId())) {
            throw new RenException("飞书事件App ID与当前智能体绑定不一致");
        }

        String eventId = text(header.get("event_id"));
        if (alreadyHandled(eventId)) {
            return Map.of("code", 0, "duplicate", true);
        }
        Map<String, Object> event = map(payload.get("event"));
        Map<String, Object> sender = map(event.get("sender"));
        if (StringUtils.equalsIgnoreCase("app", text(sender.get("sender_type")))) {
            return Map.of("code", 0, "ignored", true, "reason", "bot_message");
        }
        Map<String, Object> message = map(event.get("message"));
        if (!StringUtils.equals(config.getDefaultChatId(), text(message.get("chat_id")))) {
            return Map.of("code", 0, "ignored", true, "reason", "other_chat");
        }
        if (!StringUtils.equals("text", text(message.get("message_type")))) {
            return Map.of("code", 0, "ignored", true, "reason", "non_text_message");
        }
        String content = extractTextMessage(text(message.get("content")));
        if (StringUtils.isBlank(content)) {
            return Map.of("code", 0, "ignored", true, "reason", "empty_message");
        }
        announcementClient.broadcast(agentId, content);
        return Map.of("code", 0, "accepted", true);
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
        ConfigContext context = requireConfigured(agentId);
        if (!Boolean.TRUE.equals(context.entity().getEnabled())) {
            throw new RenException("当前智能体尚未启用飞书集成");
        }
        return context;
    }

    private ConfigContext requireConfigured(String agentId) {
        AgentFeishuConfigEntity entity = find(agentId);
        if (entity == null) {
            throw new RenException("当前智能体尚未配置飞书集成");
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

    private void validateGroupConfiguration(AgentFeishuConfigSaveDTO dto) {
        boolean groupActionEnabled = Boolean.TRUE.equals(dto.getMeetingReportEnabled())
                || Boolean.TRUE.equals(dto.getMessageSendEnabled())
                || Boolean.TRUE.equals(dto.getMessageReadEnabled())
                || Boolean.TRUE.equals(dto.getMessageBroadcastEnabled());
        if (groupActionEnabled && StringUtils.isBlank(dto.getDefaultChatId())) {
            throw new RenException("开启会议纪要或群消息能力前，请明确选择默认飞书群聊");
        }
    }

    private String eventScope(String agentId) {
        return EVENT_TOKEN_SCOPE_PREFIX + agentId;
    }

    private String extractTextMessage(String rawContent) {
        try {
            Map<String, Object> content = objectMapper.readValue(
                    rawContent, new TypeReference<Map<String, Object>>() {});
            String text = text(content.get("text"));
            text = text.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            return StringUtils.abbreviate(text, 301);
        } catch (Exception error) {
            return "";
        }
    }

    private boolean alreadyHandled(String eventId) {
        if (StringUtils.isBlank(eventId)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (handledEventIds.size() > 1000) {
            handledEventIds.entrySet().removeIf(
                    entry -> now - entry.getValue() > EVENT_DEDUP_TTL_MILLIS);
        }
        return handledEventIds.putIfAbsent(eventId, now) != null;
    }

    private boolean constantTimeEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(
                StringUtils.defaultString(left).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                StringUtils.defaultString(right).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void enforceEventRateLimit(String agentId) {
        long now = System.currentTimeMillis();
        EventRateWindow window = eventRateWindows.computeIfAbsent(
                agentId, ignored -> new EventRateWindow(now));
        synchronized (window) {
            if (now - window.startedAt >= 60_000L) {
                window.startedAt = now;
                window.count = 0;
            }
            window.count += 1;
            if (window.count > EVENT_RATE_LIMIT_PER_MINUTE) {
                throw new RenException("飞书事件回调请求过于频繁");
            }
        }
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record Credentials(String appId, String appSecret) {
    }

    private record ConfigContext(AgentFeishuConfigEntity entity, String appSecret) {
    }

    private static final class EventRateWindow {
        private long startedAt;
        private int count;

        private EventRateWindow(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}

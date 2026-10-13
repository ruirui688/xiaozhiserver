package xiaozhi.modules.reminder.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.reminder.dao.ReminderDao;
import xiaozhi.modules.reminder.dto.ReminderRuntimeActionDTO;
import xiaozhi.modules.reminder.dto.ReminderSaveDTO;
import xiaozhi.modules.reminder.entity.ReminderEntity;
import xiaozhi.modules.reminder.service.ReminderScopeService;
import xiaozhi.modules.reminder.service.ReminderService;
import xiaozhi.modules.reminder.vo.ReminderDeviceVO;
import xiaozhi.modules.reminder.vo.ReminderVO;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

@Service
@RequiredArgsConstructor
public class ReminderServiceImpl implements ReminderService {
    static final String PENDING = "PENDING";
    static final String DELIVERED = "DELIVERED";
    static final String CANCELLED = "CANCELLED";
    static final String MISSED = "MISSED";
    static final String HISTORY = "HISTORY";
    private static final Set<String> STATUSES = Set.of(PENDING, DELIVERED, CANCELLED, MISSED);
    private static final Set<String> HISTORY_STATUSES = Set.of(DELIVERED, CANCELLED, MISSED);
    private static final int MAX_PENDING_PER_DEVICE = 100;

    private final ReminderDao reminderDao;
    private final DeviceDao deviceDao;
    private final AgentDao agentDao;
    private final ReminderScopeService scopeService;
    private final WorkspacePermissionService workspacePermissionService;

    @Override
    public List<ReminderVO> list(String status) {
        String workspaceId = workspacePermissionService.currentWorkspaceId();
        workspacePermissionService.requireRead(workspaceId);
        String normalizedStatus = normalizeListFilter(status);
        QueryWrapper<ReminderEntity> query = new QueryWrapper<ReminderEntity>()
                .eq("workspace_id", workspaceId);
        if (HISTORY.equals(normalizedStatus)) {
            query.in("status", HISTORY_STATUSES)
                    .orderByDesc("remind_at")
                    .orderByDesc("id");
        } else {
            query.eq("status", normalizedStatus);
            if (PENDING.equals(normalizedStatus)) {
                query.orderByAsc("remind_at")
                        .orderByAsc("id");
            } else {
                query.orderByDesc("remind_at")
                        .orderByDesc("id");
            }
        }
        query.last("LIMIT 500");
        List<ReminderEntity> reminders = reminderDao.selectList(query);
        Map<String, DeviceEntity> devices = devicesById(
                reminders.stream().map(ReminderEntity::getDeviceId).collect(Collectors.toSet()),
                workspaceId);
        return reminders.stream().map(reminder -> toVO(reminder, devices.get(reminder.getDeviceId()))).toList();
    }

    @Override
    public List<ReminderDeviceVO> listDevices() {
        String workspaceId = workspacePermissionService.currentWorkspaceId();
        workspacePermissionService.requireRead(workspaceId);
        List<DeviceEntity> devices = deviceDao.selectList(new LambdaQueryWrapper<DeviceEntity>()
                .eq(DeviceEntity::getWorkspaceId, workspaceId)
                .isNotNull(DeviceEntity::getAgentId)
                .ne(DeviceEntity::getAgentId, "")
                .orderByDesc(DeviceEntity::getLastConnectedAt));
        Map<String, AgentEntity> agents = agentsById(
                devices.stream().map(DeviceEntity::getAgentId).collect(Collectors.toSet()),
                workspaceId);
        return devices.stream().map(device -> {
            AgentEntity agent = agents.get(device.getAgentId());
            return ReminderDeviceVO.builder()
                    .deviceId(device.getId())
                    .deviceName(deviceName(device))
                    .agentId(device.getAgentId())
                    .agentName(agent == null ? null : agent.getAgentName())
                    .lastConnectedAt(device.getLastConnectedAt())
                    .build();
        }).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReminderVO create(ReminderSaveDTO dto) {
        String workspaceId = workspacePermissionService.currentWorkspaceId();
        workspacePermissionService.requireWrite(workspaceId);
        DeviceEntity device = scopeService.requireWorkspaceDeviceForUpdate(dto.getDeviceId(), workspaceId);
        ensurePendingCapacity(device.getId());
        ReminderTimeSupport.ParsedTime parsed = parseAndValidate(dto.getRemindAt(), dto.getTimezone());
        String title = normalizedTitle(dto.getTitle());

        ReminderEntity entity = new ReminderEntity();
        applyTarget(entity, workspaceId, device, title, "日程提醒：" + title, parsed);
        entity.setStatus(PENDING);
        entity.setSource("MINI_PROGRAM");
        entity.setAttemptCount(0);
        reminderDao.insert(entity);
        return toVO(entity, device);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReminderVO update(String id, ReminderSaveDTO dto) {
        String workspaceId = workspacePermissionService.currentWorkspaceId();
        workspacePermissionService.requireWrite(workspaceId);
        ReminderEntity entity = requireWorkspaceReminder(id, workspaceId);
        requireMutable(entity);
        if (StringUtils.isNotBlank(entity.getSourceRequestId())
                && !Objects.equals(entity.getDeviceId(), dto.getDeviceId())) {
            throw new RenException("语音创建的提醒不能更换设备，请取消后重新创建");
        }
        DeviceEntity device = scopeService.requireWorkspaceDeviceForUpdate(dto.getDeviceId(), workspaceId);
        if (!Objects.equals(entity.getDeviceId(), device.getId())) {
            ensurePendingCapacity(device.getId());
        }
        ReminderTimeSupport.ParsedTime parsed = parseAndValidate(dto.getRemindAt(), dto.getTimezone());
        String title = normalizedTitle(dto.getTitle());
        applyTarget(entity, workspaceId, device, title, "日程提醒：" + title, parsed);
        entity.setAttemptCount(0);
        entity.setLastAttemptAt(null);
        entity.setDeliveredAt(null);
        entity.setLastErrorCode(null);
        entity.setLeaseToken(null);
        entity.setLeaseUntil(null);
        if (StringUtils.isNotBlank(entity.getSourceRequestId())) {
            entity.setRequestHash(requestHash(entity));
        }
        UpdateWrapper<ReminderEntity> update = mutableUpdate(id)
                .set("last_attempt_at", null)
                .set("delivered_at", null)
                .set("last_error_code", null)
                .set("lease_token", null)
                .set("lease_until", null);
        int updated = reminderDao.update(entity, update);
        if (updated == 0) {
            throw new RenException("提醒正在投递或状态已发生变化，请刷新后重试");
        }
        return toVO(entity, device);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancel(String id) {
        String workspaceId = workspacePermissionService.currentWorkspaceId();
        workspacePermissionService.requireWrite(workspaceId);
        ReminderEntity entity = requireWorkspaceReminder(id, workspaceId);
        cancelPending(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> executeRuntimeAction(String agentId, ReminderRuntimeActionDTO dto) {
        // Runtime deviceId is the authenticated WebSocket identity/MAC, not ai_device.id.
        String action = StringUtils.lowerCase(StringUtils.trim(dto.getAction()), Locale.ROOT);
        return switch (action) {
            case "create" -> runtimeCreate(
                    agentId,
                    scopeService.requireRuntimeDeviceForUpdate(agentId, dto.getDeviceId()),
                    dto);
            case "list" -> runtimeList(
                    agentId,
                    scopeService.requireRuntimeDeviceForUpdate(agentId, dto.getDeviceId()));
            case "cancel" -> runtimeCancel(
                    agentId,
                    scopeService.requireRuntimeDeviceForUpdate(agentId, dto.getDeviceId()),
                    dto.getReminderId());
            default -> throw new RenException("不支持的日程提醒动作: " + action);
        };
    }

    private Map<String, Object> runtimeCreate(
            String agentId, DeviceEntity device, ReminderRuntimeActionDTO dto) {
        if (StringUtils.isAnyBlank(dto.getRequestId(), dto.getTitle(), dto.getRemindAt(), dto.getTimezone())) {
            throw new RenException("创建提醒需要requestId、title、remindAt和timezone");
        }
        String requestId = StringUtils.trim(dto.getRequestId());
        String title = normalizedTitle(dto.getTitle());
        String spokenText = StringUtils.defaultIfBlank(
                StringUtils.trim(dto.getSpokenText()), "日程提醒：" + title);
        ReminderTimeSupport.ParsedTime parsed = ReminderTimeSupport.parse(dto.getRemindAt(), dto.getTimezone());

        ReminderEntity candidate = new ReminderEntity();
        candidate.setWorkspaceId(device.getWorkspaceId());
        candidate.setDeviceId(device.getId());
        candidate.setAgentId(agentId);
        candidate.setTitle(title);
        candidate.setSpokenText(spokenText);
        candidate.setRemindAt(Date.from(parsed.instant()));
        candidate.setTimezone(parsed.zoneId().getId());
        candidate.setSource("VOICE");
        candidate.setSourceRequestId(requestId);
        candidate.setRequestHash(requestHash(candidate));

        ReminderEntity existing = findByRequestId(device.getId(), requestId);
        if (existing != null) {
            requireMatchingRequest(existing, candidate.getRequestHash());
            return runtimeView(existing);
        }

        existing = findPendingByRequestHash(
                device.getId(), agentId, candidate.getRequestHash());
        if (existing != null) {
            return runtimeView(existing);
        }

        ReminderTimeSupport.requireCreationWindow(parsed.instant(), Instant.now());
        ensurePendingCapacity(device.getId());
        candidate.setStatus(PENDING);
        candidate.setAttemptCount(0);
        candidate.setNextAttemptAt(candidate.getRemindAt());
        try {
            reminderDao.insert(candidate);
            return runtimeView(candidate);
        } catch (DuplicateKeyException concurrentCreate) {
            existing = findByRequestId(device.getId(), requestId);
            if (existing == null) {
                throw concurrentCreate;
            }
            requireMatchingRequest(existing, candidate.getRequestHash());
            return runtimeView(existing);
        }
    }

    private Map<String, Object> runtimeList(String agentId, DeviceEntity device) {
        List<Map<String, Object>> reminders = reminderDao.selectList(new LambdaQueryWrapper<ReminderEntity>()
                .eq(ReminderEntity::getDeviceId, device.getId())
                .eq(ReminderEntity::getAgentId, agentId)
                .eq(ReminderEntity::getStatus, PENDING)
                .orderByAsc(ReminderEntity::getRemindAt)
                .last("LIMIT 20"))
                .stream().map(this::runtimeView).toList();
        return Map.of("reminders", reminders);
    }

    private Map<String, Object> runtimeCancel(
            String agentId, DeviceEntity device, String reminderId) {
        if (StringUtils.isBlank(reminderId)) {
            throw new RenException("取消提醒需要reminderId");
        }
        ReminderEntity entity = reminderDao.selectOne(new LambdaQueryWrapper<ReminderEntity>()
                .eq(ReminderEntity::getId, StringUtils.trim(reminderId))
                .eq(ReminderEntity::getDeviceId, device.getId())
                .eq(ReminderEntity::getAgentId, agentId)
                .last("LIMIT 1"));
        if (entity == null) {
            throw new RenException("提醒不存在或不属于当前设备");
        }
        cancelPending(entity);
        return Map.of("reminderId", entity.getId(), "status", entity.getStatus());
    }

    private void cancelPending(ReminderEntity entity) {
        if (CANCELLED.equals(entity.getStatus())) {
            return;
        }
        if (!PENDING.equals(entity.getStatus())) {
            throw new RenException("只有待提醒状态可以取消");
        }
        requireMutable(entity);
        int updated = reminderDao.update(null, mutableUpdate(entity.getId())
                .set("status", CANCELLED)
                .set("next_attempt_at", null)
                .set("last_error_code", null)
                .set("update_date", new Date()));
        if (updated == 0) {
            throw new RenException("提醒正在投递或状态已发生变化，请刷新后重试");
        }
        entity.setStatus(CANCELLED);
        entity.setNextAttemptAt(null);
        entity.setLastErrorCode(null);
    }

    private UpdateWrapper<ReminderEntity> mutableUpdate(String id) {
        Date now = new Date();
        return new UpdateWrapper<ReminderEntity>()
                .eq("id", id)
                .eq("status", PENDING)
                .and(scope -> scope.isNull("lease_until").or().le("lease_until", now));
    }

    private void requireMutable(ReminderEntity entity) {
        if (!PENDING.equals(entity.getStatus())) {
            throw new RenException("只有待提醒状态可以修改或取消");
        }
        if (entity.getLeaseUntil() != null && entity.getLeaseUntil().after(new Date())) {
            throw new RenException("提醒正在投递，请稍后刷新状态");
        }
    }

    private ReminderEntity requireWorkspaceReminder(String id, String workspaceId) {
        ReminderEntity entity = reminderDao.selectOne(new LambdaQueryWrapper<ReminderEntity>()
                .eq(ReminderEntity::getId, id)
                .eq(ReminderEntity::getWorkspaceId, workspaceId)
                .last("LIMIT 1"));
        if (entity == null) {
            throw new RenException("提醒不存在或不属于当前工作空间");
        }
        return entity;
    }

    private void applyTarget(
            ReminderEntity entity,
            String workspaceId,
            DeviceEntity device,
            String title,
            String spokenText,
            ReminderTimeSupport.ParsedTime parsed) {
        entity.setWorkspaceId(workspaceId);
        entity.setDeviceId(device.getId());
        entity.setAgentId(device.getAgentId());
        entity.setTitle(title);
        entity.setSpokenText(spokenText);
        entity.setRemindAt(Date.from(parsed.instant()));
        entity.setTimezone(parsed.zoneId().getId());
        entity.setNextAttemptAt(entity.getRemindAt());
    }

    private ReminderTimeSupport.ParsedTime parseAndValidate(String raw, String timezone) {
        ReminderTimeSupport.ParsedTime parsed = ReminderTimeSupport.parse(raw, timezone);
        ReminderTimeSupport.requireCreationWindow(parsed.instant(), Instant.now());
        return parsed;
    }

    private void ensurePendingCapacity(String deviceId) {
        long count = reminderDao.selectCount(new LambdaQueryWrapper<ReminderEntity>()
                .eq(ReminderEntity::getDeviceId, deviceId)
                .eq(ReminderEntity::getStatus, PENDING));
        if (count >= MAX_PENDING_PER_DEVICE) {
            throw new RenException("该设备待处理提醒已达上限");
        }
    }

    private ReminderEntity findByRequestId(String deviceId, String requestId) {
        return reminderDao.selectOne(new LambdaQueryWrapper<ReminderEntity>()
                .eq(ReminderEntity::getDeviceId, deviceId)
                .eq(ReminderEntity::getSourceRequestId, requestId)
                .last("LIMIT 1"));
    }

    private ReminderEntity findPendingByRequestHash(
            String deviceId, String agentId, String requestHash) {
        return reminderDao.selectOne(new QueryWrapper<ReminderEntity>()
                .eq("device_id", deviceId)
                .eq("agent_id", agentId)
                .eq("status", PENDING)
                .eq("request_hash", requestHash)
                .orderByAsc("create_date")
                .last("LIMIT 1"));
    }

    private void requireMatchingRequest(ReminderEntity existing, String requestHash) {
        if (!StringUtils.equals(existing.getRequestHash(), requestHash)) {
            throw new RenException("同一requestId不能用于不同的提醒内容");
        }
    }

    private String normalizedTitle(String title) {
        String value = StringUtils.trim(title);
        if (StringUtils.isBlank(value) || value.length() > 200) {
            throw new RenException("提醒标题长度必须为1到200个字符");
        }
        return value;
    }

    private String normalizeListFilter(String status) {
        if (StringUtils.isBlank(status)) {
            return PENDING;
        }
        String normalized = StringUtils.upperCase(StringUtils.trim(status), Locale.ROOT);
        if (!STATUSES.contains(normalized) && !HISTORY.equals(normalized)) {
            throw new RenException("不支持的提醒状态");
        }
        return normalized;
    }

    private String requestHash(ReminderEntity entity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : List.of(
                    StringUtils.defaultString(entity.getAgentId()),
                    StringUtils.defaultString(entity.getDeviceId()),
                    StringUtils.defaultString(entity.getTitle()),
                    StringUtils.defaultString(entity.getSpokenText()),
                    entity.getRemindAt().toInstant().toString(),
                    StringUtils.defaultString(entity.getTimezone()),
                    StringUtils.defaultString(entity.getSource()))) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                digest.update((byte) ':');
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256不可用", impossible);
        }
    }

    private Map<String, Object> runtimeView(ReminderEntity entity) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reminderId", entity.getId());
        result.put("title", entity.getTitle());
        result.put("remindAt", ReminderTimeSupport.format(entity.getRemindAt(), entity.getTimezone()));
        result.put("timezone", entity.getTimezone());
        result.put("status", entity.getStatus());
        return result;
    }

    private ReminderVO toVO(ReminderEntity entity, DeviceEntity device) {
        return ReminderVO.builder()
                .id(entity.getId())
                .deviceId(entity.getDeviceId())
                .deviceName(deviceName(device))
                .agentId(entity.getAgentId())
                .title(entity.getTitle())
                .remindAt(ReminderTimeSupport.format(entity.getRemindAt(), entity.getTimezone()))
                .timezone(entity.getTimezone())
                .status(entity.getStatus())
                .source(entity.getSource())
                .deliveredAt(ReminderTimeSupport.format(entity.getDeliveredAt(), entity.getTimezone()))
                .lastErrorCode(entity.getLastErrorCode())
                .createDate(entity.getCreateDate())
                .build();
    }

    private String deviceName(DeviceEntity device) {
        if (device == null) {
            return null;
        }
        return StringUtils.defaultIfBlank(device.getAlias(), device.getMacAddress());
    }

    private Map<String, DeviceEntity> devicesById(Collection<String> ids, String workspaceId) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return deviceDao.selectList(new LambdaQueryWrapper<DeviceEntity>()
                        .in(DeviceEntity::getId, ids)
                        .eq(DeviceEntity::getWorkspaceId, workspaceId))
                .stream()
                .collect(Collectors.toMap(DeviceEntity::getId, Function.identity(), (left, right) -> left));
    }

    private Map<String, AgentEntity> agentsById(Collection<String> ids, String workspaceId) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return agentDao.selectList(new LambdaQueryWrapper<AgentEntity>()
                        .in(AgentEntity::getId, ids)
                        .eq(AgentEntity::getWorkspaceId, workspaceId))
                .stream()
                .collect(Collectors.toMap(AgentEntity::getId, Function.identity(), (left, right) -> left));
    }
}

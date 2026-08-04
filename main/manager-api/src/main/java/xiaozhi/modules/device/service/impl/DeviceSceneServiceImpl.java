package xiaozhi.modules.device.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.redis.RedisKeys;
import xiaozhi.common.redis.RedisUtils;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.dao.DeviceSceneBindingDao;
import xiaozhi.modules.device.dao.DeviceSceneSwitchDao;
import xiaozhi.modules.device.dto.DeviceSceneCreateDTO;
import xiaozhi.modules.device.dto.DeviceSceneSwitchDTO;
import xiaozhi.modules.device.dto.DeviceSceneSwitchResultDTO;
import xiaozhi.modules.device.dto.DeviceSceneUpdateDTO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.entity.DeviceSceneBindingEntity;
import xiaozhi.modules.device.entity.DeviceSceneSwitchEntity;
import xiaozhi.modules.device.runtime.DeviceRuntimeConfigRefreshEvent;
import xiaozhi.modules.device.service.DeviceSceneService;
import xiaozhi.modules.device.vo.DeviceSceneVO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.timbre.service.TimbreService;
import xiaozhi.modules.timbre.vo.TimbreDetailsVO;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

@Service
@RequiredArgsConstructor
public class DeviceSceneServiceImpl implements DeviceSceneService {
    private static final int MAX_SCENES_PER_DEVICE = 20;
    private static final List<String> TERMINAL_STATUSES = List.of("APPLIED", "FAILED", "ROLLED_BACK");

    private final DeviceSceneBindingDao bindingDao;
    private final DeviceSceneSwitchDao switchDao;
    private final DeviceDao deviceDao;
    private final AgentDao agentDao;
    private final ModelConfigService modelConfigService;
    private final TimbreService timbreService;
    private final WorkspacePermissionService workspacePermissionService;
    private final ApplicationEventPublisher eventPublisher;
    private final RedisUtils redisUtils;

    @Override
    public List<DeviceSceneVO> listScenes(String deviceId) {
        DeviceEntity device = requireDevice(deviceId, false);
        List<DeviceSceneBindingEntity> bindings = bindingDao.selectList(
                new LambdaQueryWrapper<DeviceSceneBindingEntity>()
                        .eq(DeviceSceneBindingEntity::getDeviceId, deviceId)
                        .orderByAsc(DeviceSceneBindingEntity::getSort)
                        .orderByAsc(DeviceSceneBindingEntity::getCreateDate));
        DeviceSceneSwitchEntity latest = latestSwitch(deviceId);
        return bindings.stream().map(binding -> toVO(binding, device, latest)).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeviceSceneVO createScene(String deviceId, DeviceSceneCreateDTO dto) {
        DeviceEntity device = requireWritableDeviceForUpdate(deviceId);
        long count = bindingDao.selectCount(new LambdaQueryWrapper<DeviceSceneBindingEntity>()
                .eq(DeviceSceneBindingEntity::getDeviceId, deviceId));
        if (count >= MAX_SCENES_PER_DEVICE) {
            throw new RenException("一个设备最多可关联20个场景");
        }
        AgentEntity agent = requireTargetAgent(dto.getAgentId(), device.getWorkspaceId());
        DeviceSceneBindingEntity existing = findByDeviceAndAgent(deviceId, agent.getId());
        if (existing != null) {
            throw new RenException("该智能体已经关联到此设备场景");
        }

        Date now = new Date();
        Long actorId = SecurityUser.getUserId();
        DeviceSceneBindingEntity binding = new DeviceSceneBindingEntity();
        binding.setWorkspaceId(device.getWorkspaceId());
        binding.setDeviceId(deviceId);
        binding.setAgentId(agent.getId());
        binding.setSceneName(normalizeSceneName(dto.getSceneName()));
        binding.setSwitchAliases(normalizeAliases(dto.getSwitchAliases()));
        binding.setEnabled(dto.getEnabled() == null ? 1 : normalizeEnabled(dto.getEnabled()));
        binding.setSort(dto.getSort() == null ? nextSort(deviceId) : Math.max(dto.getSort(), 0));
        binding.setTemplateId(blankToNull(dto.getTemplateId()));
        binding.setTemplateVersion(dto.getTemplateVersion());
        binding.setCreator(actorId);
        binding.setUpdater(actorId);
        binding.setCreateDate(now);
        binding.setUpdateDate(now);
        bindingDao.insert(binding);
        return toVO(binding, device, latestSwitch(deviceId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeviceSceneVO updateScene(String deviceId, String bindingId, DeviceSceneUpdateDTO dto) {
        DeviceEntity device = requireWritableDeviceForUpdate(deviceId);
        DeviceSceneBindingEntity binding = requireBinding(deviceId, bindingId);
        int enabled = dto.getEnabled() == null ? normalizeEnabled(binding.getEnabled()) : normalizeEnabled(dto.getEnabled());
        if (enabled == 0 && Objects.equals(device.getAgentId(), binding.getAgentId())) {
            throw new RenException("当前生效场景不能禁用");
        }
        binding.setSceneName(normalizeSceneName(dto.getSceneName()));
        binding.setSwitchAliases(normalizeAliases(dto.getSwitchAliases()));
        binding.setEnabled(enabled);
        if (dto.getSort() != null) {
            binding.setSort(Math.max(dto.getSort(), 0));
        }
        binding.setUpdater(SecurityUser.getUserId());
        binding.setUpdateDate(new Date());
        bindingDao.updateById(binding);
        return toVO(binding, device, latestSwitch(deviceId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteScene(String deviceId, String bindingId) {
        DeviceEntity device = requireWritableDeviceForUpdate(deviceId);
        DeviceSceneBindingEntity binding = requireBinding(deviceId, bindingId);
        if (Objects.equals(device.getAgentId(), binding.getAgentId())) {
            throw new RenException("当前生效场景不能删除，请先切换到其他场景");
        }
        bindingDao.deleteById(binding.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DeviceSceneVO switchActiveScene(String deviceId, DeviceSceneSwitchDTO dto) {
        String requestId = StringUtils.defaultIfBlank(dto.getSwitchRequestId(), uuid());
        DeviceSceneSwitchEntity repeated = switchDao.selectOne(
                new LambdaQueryWrapper<DeviceSceneSwitchEntity>()
                        .eq(DeviceSceneSwitchEntity::getSwitchRequestId, requestId));
        if (repeated != null) {
            if (!Objects.equals(deviceId, repeated.getDeviceId())) {
                throw new RenException("切换请求ID已被其他设备使用");
            }
            DeviceEntity current = requireDevice(deviceId, false);
            return toVO(requireBinding(deviceId, repeated.getBindingId()), current, repeated);
        }

        DeviceEntity device = deviceDao.selectByIdForUpdate(deviceId);
        if (device == null) {
            throw new RenException("设备不存在");
        }
        workspacePermissionService.requireWrite(device.getWorkspaceId());
        DeviceSceneBindingEntity target = requireBinding(deviceId, dto.getBindingId());
        if (normalizeEnabled(target.getEnabled()) != 1) {
            throw new RenException("目标场景已禁用");
        }
        AgentEntity targetAgent = requireTargetAgent(target.getAgentId(), device.getWorkspaceId());
        validateRuntimeModels(targetAgent);

        String fromAgentId = device.getAgentId();
        long currentVersion = device.getConfigVersion() == null ? 0L : device.getConfigVersion();
        long targetVersion = currentVersion;
        String status = "APPLIED";
        Date now = new Date();
        if (!Objects.equals(fromAgentId, targetAgent.getId())) {
            int updated = deviceDao.update(null, new UpdateWrapper<DeviceEntity>()
                    .eq("id", deviceId)
                    .eq("config_version", currentVersion)
                    .set("agent_id", targetAgent.getId())
                    .setSql("config_version = config_version + 1")
                    .set("updater", SecurityUser.getUserId())
                    .set("update_date", now));
            if (updated != 1) {
                throw new RenException("设备场景已被其他请求修改，请刷新后重试");
            }
            targetVersion = currentVersion + 1;
            status = "REQUESTED";
            clearAgentDeviceCountCaches(fromAgentId, targetAgent.getId());
        }

        DeviceSceneSwitchEntity audit = new DeviceSceneSwitchEntity();
        audit.setWorkspaceId(device.getWorkspaceId());
        audit.setDeviceId(deviceId);
        audit.setBindingId(target.getId());
        audit.setFromAgentId(fromAgentId);
        audit.setToAgentId(targetAgent.getId());
        audit.setSwitchRequestId(requestId);
        audit.setConfigVersion(targetVersion);
        audit.setStatus(status);
        audit.setRequestedBy(SecurityUser.getUserId());
        audit.setRequestedAt(now);
        audit.setUpdatedAt(now);
        if ("APPLIED".equals(status)) {
            audit.setAppliedAt(now);
        }
        switchDao.insert(audit);

        if ("REQUESTED".equals(status)) {
            eventPublisher.publishEvent(new DeviceRuntimeConfigRefreshEvent(
                    deviceId, fromAgentId, targetAgent.getId(), requestId,
                    targetVersion, "device_scene_switched"));
        }
        device.setAgentId(targetAgent.getId());
        device.setConfigVersion(targetVersion);
        return toVO(target, device, audit);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordRuntimeResult(DeviceSceneSwitchResultDTO dto) {
        String status = StringUtils.upperCase(StringUtils.trim(dto.getStatus()));
        if (!TERMINAL_STATUSES.contains(status) && !"SCHEDULED".equals(status)) {
            throw new RenException("无效的场景切换状态");
        }
        DeviceSceneSwitchEntity audit = switchDao.selectOne(
                new LambdaQueryWrapper<DeviceSceneSwitchEntity>()
                        .eq(DeviceSceneSwitchEntity::getSwitchRequestId, dto.getSwitchRequestId()));
        if (audit == null
                || !Objects.equals(audit.getDeviceId(), dto.getDeviceId())
                || !Objects.equals(audit.getToAgentId(), dto.getAgentId())
                || !Objects.equals(audit.getConfigVersion(), dto.getConfigVersion())) {
            throw new RenException("场景切换结果与已登记请求不匹配");
        }
        if (TERMINAL_STATUSES.contains(audit.getStatus())) {
            return;
        }
        String persistedStatus = status;
        if ("FAILED".equals(status) && StringUtils.isNotBlank(audit.getFromAgentId())) {
            int rolledBack = deviceDao.update(null, new UpdateWrapper<DeviceEntity>()
                    .eq("id", audit.getDeviceId())
                    .eq("agent_id", audit.getToAgentId())
                    .eq("config_version", audit.getConfigVersion())
                    .set("agent_id", audit.getFromAgentId())
                    .setSql("config_version = config_version + 1")
                    .set("update_date", new Date()));
            if (rolledBack == 1) {
                persistedStatus = "ROLLED_BACK";
                clearAgentDeviceCountCaches(audit.getFromAgentId(), audit.getToAgentId());
            }
        }
        audit.setStatus(persistedStatus);
        audit.setFailureCode("FAILED".equals(status) ? safeFailureCode(dto.getFailureCode()) : null);
        audit.setUpdatedAt(new Date());
        if ("APPLIED".equals(status)) {
            audit.setAppliedAt(new Date());
        }
        switchDao.updateById(audit);
    }

    @Override
    public void ensureCurrentBinding(DeviceEntity device, AgentEntity agent, Long actorId) {
        if (device == null || agent == null || StringUtils.isBlank(device.getId()) || StringUtils.isBlank(agent.getId())) {
            return;
        }
        if (findByDeviceAndAgent(device.getId(), agent.getId()) != null) {
            return;
        }
        Date now = new Date();
        DeviceSceneBindingEntity binding = new DeviceSceneBindingEntity();
        binding.setWorkspaceId(device.getWorkspaceId());
        binding.setDeviceId(device.getId());
        binding.setAgentId(agent.getId());
        binding.setSceneName(StringUtils.defaultIfBlank(agent.getAgentName(), "默认场景"));
        binding.setSwitchAliases(List.of());
        binding.setEnabled(1);
        binding.setSort(0);
        binding.setCreator(actorId);
        binding.setUpdater(actorId);
        binding.setCreateDate(now);
        binding.setUpdateDate(now);
        bindingDao.insert(binding);
    }

    @Override
    public void deleteByDeviceId(String deviceId) {
        bindingDao.delete(new LambdaQueryWrapper<DeviceSceneBindingEntity>()
                .eq(DeviceSceneBindingEntity::getDeviceId, deviceId));
        switchDao.delete(new LambdaQueryWrapper<DeviceSceneSwitchEntity>()
                .eq(DeviceSceneSwitchEntity::getDeviceId, deviceId));
    }

    @Override
    public void assertAgentNotBound(String agentId) {
        if (StringUtils.isBlank(agentId)) {
            return;
        }
        Long count = bindingDao.selectCount(
                new LambdaQueryWrapper<DeviceSceneBindingEntity>()
                        .eq(DeviceSceneBindingEntity::getAgentId, agentId));
        if (count != null && count > 0) {
            throw new RenException("智能体仍被设备场景引用，请先移除相关场景");
        }
    }

    private DeviceEntity requireDevice(String deviceId, boolean write) {
        DeviceEntity device = deviceDao.selectById(deviceId);
        if (device == null) {
            throw new RenException("设备不存在");
        }
        if (write) {
            workspacePermissionService.requireWrite(device.getWorkspaceId());
        } else {
            workspacePermissionService.requireRead(device.getWorkspaceId());
        }
        return device;
    }

    private DeviceEntity requireWritableDeviceForUpdate(String deviceId) {
        DeviceEntity device = deviceDao.selectByIdForUpdate(deviceId);
        if (device == null) {
            throw new RenException("设备不存在");
        }
        workspacePermissionService.requireWrite(device.getWorkspaceId());
        return device;
    }

    private AgentEntity requireTargetAgent(String agentId, String workspaceId) {
        AgentEntity agent = agentDao.selectById(agentId);
        if (agent == null) {
            throw new RenException("场景智能体不存在");
        }
        if (!Objects.equals(workspaceId, agent.getWorkspaceId())) {
            throw new RenException("设备与场景智能体必须属于同一工作空间");
        }
        workspacePermissionService.requireRead(agent.getWorkspaceId());
        return agent;
    }

    private DeviceSceneBindingEntity requireBinding(String deviceId, String bindingId) {
        DeviceSceneBindingEntity binding = bindingDao.selectOne(
                new LambdaQueryWrapper<DeviceSceneBindingEntity>()
                        .eq(DeviceSceneBindingEntity::getId, bindingId)
                        .eq(DeviceSceneBindingEntity::getDeviceId, deviceId));
        if (binding == null) {
            throw new RenException("设备场景不存在");
        }
        return binding;
    }

    private DeviceSceneBindingEntity findByDeviceAndAgent(String deviceId, String agentId) {
        return bindingDao.selectOne(new LambdaQueryWrapper<DeviceSceneBindingEntity>()
                .eq(DeviceSceneBindingEntity::getDeviceId, deviceId)
                .eq(DeviceSceneBindingEntity::getAgentId, agentId));
    }

    private void validateRuntimeModels(AgentEntity agent) {
        validateModel(agent.getVadModelId(), "VAD");
        validateModel(agent.getAsrModelId(), "ASR");
        validateModel(agent.getLlmModelId(), "LLM");
        validateModel(agent.getSlmModelId(), "SLM");
        validateModel(agent.getVllmModelId(), "VLLM");
        validateModel(agent.getTtsModelId(), "TTS");
        validateModel(agent.getMemModelId(), "Memory");
        validateModel(agent.getIntentModelId(), "Intent");
        if (StringUtils.isNotBlank(agent.getTtsVoiceId())) {
            TimbreDetailsVO voice = timbreService.get(agent.getTtsVoiceId());
            if (voice == null || !Objects.equals(agent.getTtsModelId(), voice.getTtsModelId())) {
                throw new RenException("场景音色与TTS模型不匹配");
            }
        }
    }

    private void validateModel(String modelId, String expectedType) {
        if (StringUtils.isBlank(modelId)) {
            return;
        }
        ModelConfigEntity model = modelConfigService.getModelByIdFromCache(modelId);
        if (model == null || !expectedType.equalsIgnoreCase(model.getModelType())) {
            throw new RenException("场景模型不存在或类型不匹配: " + expectedType);
        }
        if (!Integer.valueOf(1).equals(model.getIsEnabled()) || !Integer.valueOf(1).equals(model.getTestStatus())) {
            throw new RenException("场景模型必须先启用并通过真实测试: " + expectedType);
        }
    }

    private DeviceSceneSwitchEntity latestSwitch(String deviceId) {
        return switchDao.selectOne(new LambdaQueryWrapper<DeviceSceneSwitchEntity>()
                .eq(DeviceSceneSwitchEntity::getDeviceId, deviceId)
                .orderByDesc(DeviceSceneSwitchEntity::getRequestedAt)
                .last("LIMIT 1"));
    }

    private DeviceSceneVO toVO(DeviceSceneBindingEntity binding, DeviceEntity device, DeviceSceneSwitchEntity latest) {
        DeviceSceneVO vo = new DeviceSceneVO();
        vo.setId(binding.getId());
        vo.setDeviceId(binding.getDeviceId());
        vo.setAgentId(binding.getAgentId());
        AgentEntity agent = agentDao.selectById(binding.getAgentId());
        vo.setAgentName(agent == null ? "" : agent.getAgentName());
        vo.setSceneName(binding.getSceneName());
        vo.setSwitchAliases(binding.getSwitchAliases() == null ? List.of() : binding.getSwitchAliases());
        vo.setEnabled(binding.getEnabled());
        vo.setSort(binding.getSort());
        vo.setTemplateId(binding.getTemplateId());
        vo.setTemplateVersion(binding.getTemplateVersion());
        vo.setCurrent(Objects.equals(device.getAgentId(), binding.getAgentId()));
        vo.setConfigVersion(device.getConfigVersion() == null ? 0L : device.getConfigVersion());
        if (latest != null && Objects.equals(latest.getBindingId(), binding.getId())) {
            vo.setSwitchRequestId(latest.getSwitchRequestId());
            vo.setSwitchStatus(latest.getStatus());
            vo.setFailureCode(latest.getFailureCode());
            vo.setRequestedAt(latest.getRequestedAt());
            vo.setAppliedAt(latest.getAppliedAt());
        }
        return vo;
    }

    private int nextSort(String deviceId) {
        List<DeviceSceneBindingEntity> bindings = bindingDao.selectList(
                new LambdaQueryWrapper<DeviceSceneBindingEntity>()
                        .eq(DeviceSceneBindingEntity::getDeviceId, deviceId));
        return bindings.stream().map(DeviceSceneBindingEntity::getSort)
                .filter(Objects::nonNull).max(Integer::compareTo).orElse(-1) + 1;
    }

    private static String normalizeSceneName(String value) {
        String name = StringUtils.trimToEmpty(value);
        if (name.isEmpty()) {
            throw new RenException("场景名称不能为空");
        }
        return name;
    }

    private static List<String> normalizeAliases(List<String> aliases) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String alias : aliases == null ? List.<String>of() : aliases) {
            String value = StringUtils.trimToEmpty(alias);
            if (!value.isEmpty()) {
                normalized.add(value);
            }
        }
        if (normalized.size() > 10) {
            throw new RenException("场景切换别名不能超过10个");
        }
        return new ArrayList<>(normalized);
    }

    private static int normalizeEnabled(Integer enabled) {
        return Integer.valueOf(0).equals(enabled) ? 0 : 1;
    }

    private static String blankToNull(String value) {
        return StringUtils.trimToNull(value);
    }

    private static String safeFailureCode(String value) {
        String code = StringUtils.defaultIfBlank(value, "RUNTIME_REFRESH_FAILED")
                .replaceAll("[^A-Za-z0-9_.-]", "_");
        return StringUtils.left(code, 64);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void clearAgentDeviceCountCaches(String... agentIds) {
        List<String> keys = Stream.of(agentIds)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .map(RedisKeys::getAgentDeviceCountById)
                .toList();
        if (!keys.isEmpty()) {
            redisUtils.delete(keys);
        }
    }
}

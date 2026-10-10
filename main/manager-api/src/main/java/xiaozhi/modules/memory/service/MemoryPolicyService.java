package xiaozhi.modules.memory.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.dao.AgentVoicePrintDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.memory.dao.MemoryPolicyDao;
import xiaozhi.modules.memory.dto.MemoryPolicyDTO;
import xiaozhi.modules.memory.entity.MemoryPolicyEntity;
import xiaozhi.modules.workspace.service.WorkspacePermissionService;

@Service
@RequiredArgsConstructor
public class MemoryPolicyService {
    private final MemoryPolicyDao policyDao;
    private final AgentDao agentDao;
    private final DeviceDao deviceDao;
    private final AgentVoicePrintDao voicePrintDao;
    private final WorkspacePermissionService permissions;
    private final MemoryRuntimeRefresh refresh;
    @org.springframework.beans.factory.annotation.Autowired
    private xiaozhi.modules.model.service.ModelConfigService modelConfigService;
    @org.springframework.beans.factory.annotation.Autowired
    private xiaozhi.modules.model.service.impl.ModelRuntimeAssembler modelRuntimeAssembler;

    public boolean managed(String agentId) {
        AgentEntity agent = agentDao.selectById(agentId);
        if (agent == null || StringUtils.isBlank(agent.getMemModelId())) return false;
        var model = modelConfigService.getModelByIdFromCache(agent.getMemModelId());
        return model != null && "managed_memory".equals(modelRuntimeAssembler.buildTypedConfig(model).getStr("type"));
    }

    public MemoryPolicyDTO get(String agentId) {
        MemoryPolicyEntity stored = policyDao.selectById(agentId);
        return stored == null ? new MemoryPolicyDTO() : JSONUtil.toBean(stored.getPolicyJson(), MemoryPolicyDTO.class);
    }

    public AgentEntity requireAccess(String agentId, boolean write) {
        AgentEntity agent = agentDao.selectById(agentId);
        if (agent == null) throw new IllegalArgumentException("智能体不存在");
        if (write) permissions.requireWrite(agent.getWorkspaceId());
        else permissions.requireRead(agent.getWorkspaceId());
        return agent;
    }

    public Map<String, Object> managementView(String agentId) {
        AgentEntity agent = requireAccess(agentId, false);
        Map<String, Object> view = new LinkedHashMap<>(JSONUtil.parseObj(get(agentId)));
        view.put("canWrite", permissions.canWrite(agent.getWorkspaceId()));
        view.put("devices", deviceDao.selectList(new QueryWrapper<DeviceEntity>().eq("agent_id", agentId)).stream()
                .map(device -> Map.of("deviceId", device.getMacAddress(), "label",
                        StringUtils.defaultIfBlank(device.getAlias(), device.getMacAddress()))).toList());
        view.put("speakers", voicePrintDao.selectBoundByAgentId(agentId).stream()
                .map(speaker -> Map.of("speakerId", speaker.getId(), "label",
                        StringUtils.defaultIfBlank(speaker.getSourceName(), speaker.getId()))).toList());
        return view;
    }

    @Transactional(rollbackFor = Exception.class)
    public void save(String agentId, MemoryPolicyDTO policy) {
        requireAccess(agentId, true);
        agentDao.selectOne(new QueryWrapper<AgentEntity>().eq("id", agentId).last("FOR UPDATE"));
        MemoryPolicyEntity stored = new MemoryPolicyEntity();
        stored.setAgentId(agentId);
        stored.setPolicyJson(JSONUtil.toJsonStr(policy));
        if (policyDao.selectById(agentId) == null) policyDao.insert(stored);
        else policyDao.updateById(stored);
        refresh.changed(agentId);
    }

    public void requireDevice(String agentId, String deviceId) {
        if (StringUtils.isBlank(deviceId) || deviceDao.selectCount(new QueryWrapper<DeviceEntity>()
                .eq("agent_id", agentId).eq("mac_address", deviceId)) == 0) {
            throw new IllegalArgumentException("设备未绑定当前智能体");
        }
    }

    public List<String> speakers(String agentId) {
        return voicePrintDao.selectBoundIdsByAgentId(agentId);
    }

    public void requireSpeaker(String agentId, String speakerId) {
        if (StringUtils.isNotBlank(speakerId) && !speakers(agentId).contains(speakerId)) {
            throw new IllegalArgumentException("人物未绑定当前智能体");
        }
    }
}

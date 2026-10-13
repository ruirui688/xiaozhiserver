package xiaozhi.modules.reminder.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.RequiredArgsConstructor;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;

/** Derives reminder ownership from persisted device and agent bindings. */
@Service
@RequiredArgsConstructor
public class ReminderScopeService {
    private final DeviceDao deviceDao;
    private final AgentDao agentDao;

    public DeviceEntity requireWorkspaceDevice(String deviceRecordId, String workspaceId) {
        return requireConsistentBinding(findWorkspaceDevice(deviceRecordId, workspaceId));
    }

    /** Locks the target device row so the per-device pending limit is concurrency-safe. */
    public DeviceEntity requireWorkspaceDeviceForUpdate(String deviceRecordId, String workspaceId) {
        if (findWorkspaceDevice(deviceRecordId, workspaceId) == null) {
            throw new RenException("设备不存在、未绑定智能体或不属于当前工作空间");
        }
        DeviceEntity lockedDevice = deviceDao.selectByIdForUpdate(deviceRecordId);
        if (lockedDevice == null || !StringUtils.equals(lockedDevice.getWorkspaceId(), workspaceId)) {
            throw new RenException("设备不存在、未绑定智能体或不属于当前工作空间");
        }
        return requireConsistentBinding(lockedDevice);
    }

    private DeviceEntity findWorkspaceDevice(String deviceRecordId, String workspaceId) {
        return deviceDao.selectOne(new LambdaQueryWrapper<DeviceEntity>()
                .eq(DeviceEntity::getId, deviceRecordId)
                .eq(DeviceEntity::getWorkspaceId, workspaceId)
                .last("LIMIT 1"));
    }

    /** Locks the device row so runtime actions serialize with rebinding and deletion. */
    public DeviceEntity requireRuntimeDeviceForUpdate(String agentId, String macAddress) {
        if (StringUtils.isAnyBlank(agentId, macAddress)) {
            throw new RenException("设备与智能体身份不能为空");
        }
        DeviceEntity device = deviceDao.selectOne(new LambdaQueryWrapper<DeviceEntity>()
                .eq(DeviceEntity::getAgentId, agentId)
                .eq(DeviceEntity::getMacAddress, macAddress)
                .last("LIMIT 1 FOR UPDATE"));
        return requireConsistentBinding(device);
    }

    private DeviceEntity requireConsistentBinding(DeviceEntity device) {
        if (device == null || StringUtils.isAnyBlank(
                device.getId(), device.getWorkspaceId(), device.getAgentId(), device.getMacAddress())) {
            throw new RenException("设备不存在、未绑定智能体或不属于当前工作空间");
        }
        AgentEntity agent = agentDao.selectById(device.getAgentId());
        if (agent == null || !StringUtils.equals(device.getWorkspaceId(), agent.getWorkspaceId())) {
            throw new RenException("设备与智能体工作空间不一致");
        }
        return device;
    }
}

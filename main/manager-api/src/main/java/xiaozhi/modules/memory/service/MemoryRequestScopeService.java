package xiaozhi.modules.memory.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.memory.dao.MemorySourceEventDao;
import xiaozhi.modules.memory.entity.MemorySourceEventEntity;

/** Shared server secret does not authorize arbitrary agents or fabricated devices. */
@Service
@RequiredArgsConstructor
public class MemoryRequestScopeService {
    private final DeviceDao devices;
    private final MemorySourceEventDao events;

    public void require(String agentId, String mac, String historicalSessionId) {
        if (StringUtils.isBlank(agentId) || StringUtils.isBlank(mac)) throw new IllegalArgumentException("缺少智能体或设备身份");
        if (devices.selectCount(new QueryWrapper<DeviceEntity>().eq("agent_id", agentId).eq("mac_address", mac)) > 0) return;
        // A role change may already have moved the device. Only recorded session evidence grants the old scope.
        if (StringUtils.isNotBlank(historicalSessionId) && events.selectCount(new QueryWrapper<MemorySourceEventEntity>()
                .eq("agent_id", agentId).eq("mac_address", mac).eq("session_id", historicalSessionId)) > 0) return;
        throw new IllegalArgumentException("设备与智能体作用域不匹配");
    }
}

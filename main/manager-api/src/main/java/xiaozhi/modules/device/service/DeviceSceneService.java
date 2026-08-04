package xiaozhi.modules.device.service;

import java.util.List;

import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.device.dto.DeviceSceneCreateDTO;
import xiaozhi.modules.device.dto.DeviceSceneSwitchDTO;
import xiaozhi.modules.device.dto.DeviceSceneSwitchResultDTO;
import xiaozhi.modules.device.dto.DeviceSceneUpdateDTO;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.vo.DeviceSceneVO;

public interface DeviceSceneService {
    List<DeviceSceneVO> listScenes(String deviceId);
    DeviceSceneVO createScene(String deviceId, DeviceSceneCreateDTO dto);
    DeviceSceneVO updateScene(String deviceId, String bindingId, DeviceSceneUpdateDTO dto);
    void deleteScene(String deviceId, String bindingId);
    DeviceSceneVO switchActiveScene(String deviceId, DeviceSceneSwitchDTO dto);
    void recordRuntimeResult(DeviceSceneSwitchResultDTO dto);
    void ensureCurrentBinding(DeviceEntity device, AgentEntity agent, Long actorId);
    void deleteByDeviceId(String deviceId);
    void assertAgentNotBound(String agentId);
}

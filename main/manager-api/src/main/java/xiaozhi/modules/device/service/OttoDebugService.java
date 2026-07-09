package xiaozhi.modules.device.service;

import java.util.Map;

public interface OttoDebugService {
    Object connect(Long userId, String deviceId, String host);

    Object snapshot(Long userId, String deviceId);

    Object listTools(Long userId, String deviceId, String cursor, Integer limit);

    Object callTool(Long userId, String deviceId, String name, Map<String, Object> arguments);

    Object callToolFast(Long userId, String deviceId, String name, Map<String, Object> arguments);

    Object getConfig(Long userId, String deviceId);

    Object setConfig(Long userId, String deviceId, String key, String value);
}

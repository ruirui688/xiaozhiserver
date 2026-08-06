package xiaozhi.modules.device.runtime;

public record DeviceRuntimeConfigRefreshEvent(
        String deviceId,
        String fromAgentId,
        String toAgentId,
        String switchRequestId,
        long configVersion,
        String reason) {
    public static DeviceRuntimeConfigRefreshEvent forActivation(String deviceId, String agentId) {
        return new DeviceRuntimeConfigRefreshEvent(
                deviceId, null, agentId, null, 0L, "device_activated");
    }

    public static DeviceRuntimeConfigRefreshEvent forDeviceSettings(String deviceId, String agentId) {
        return new DeviceRuntimeConfigRefreshEvent(
                deviceId, agentId, agentId, null, 0L, "device_speech_routing_updated");
    }
}

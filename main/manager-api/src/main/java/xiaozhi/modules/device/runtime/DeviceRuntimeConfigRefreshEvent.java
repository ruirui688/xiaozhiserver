package xiaozhi.modules.device.runtime;

public record DeviceRuntimeConfigRefreshEvent(
        String deviceId,
        String fromAgentId,
        String toAgentId,
        String switchRequestId,
        long configVersion,
        String reason) {
}

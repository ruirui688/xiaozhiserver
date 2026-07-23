package xiaozhi.modules.agent.voiceprint;

public record VoiceprintRegisteredEvent(String agentId, String deviceId, String voicePrintId) {
}

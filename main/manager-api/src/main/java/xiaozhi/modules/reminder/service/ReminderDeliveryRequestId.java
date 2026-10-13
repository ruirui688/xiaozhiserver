package xiaozhi.modules.reminder.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.reminder.entity.ReminderEntity;

/** Builds the stable Python-side idempotency key for one reminder business version. */
final class ReminderDeliveryRequestId {
    private ReminderDeliveryRequestId() {
    }

    static String from(ReminderEntity reminder, DeviceEntity device) {
        String reminderId = Objects.requireNonNull(reminder.getId(), "reminderId");
        String fingerprint = sha256(List.of(
                reminderId,
                Objects.requireNonNull(reminder.getDeviceId(), "deviceId"),
                Objects.requireNonNull(device.getMacAddress(), "runtimeDeviceId"),
                Objects.requireNonNull(reminder.getAgentId(), "agentId"),
                Objects.requireNonNull(reminder.getSpokenText(), "spokenText"),
                Long.toString(Objects.requireNonNull(reminder.getRemindAt(), "remindAt").getTime())));
        String requestId = reminderId + ":" + fingerprint;
        if (requestId.length() > 128) {
            throw new IllegalStateException("提醒投递幂等键超过128字符");
        }
        return requestId;
    }

    private static String sha256(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
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
}

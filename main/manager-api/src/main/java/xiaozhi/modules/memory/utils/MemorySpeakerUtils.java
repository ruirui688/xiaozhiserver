package xiaozhi.modules.memory.utils;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import xiaozhi.modules.memory.entity.MemoryFactEntity;
import xiaozhi.modules.memory.entity.MemorySourceEventEntity;

/**
 * 声纹身份在记忆事件、维护模型动作和事实 payload 之间的可信转换。
 */
public final class MemorySpeakerUtils {
    public static final String PAYLOAD_SPEAKER_ID = "speaker_id";
    public static final String PAYLOAD_SPEAKER_NAME = "speaker_name";
    public static final String INTERNAL_SPEAKER_ID = "_speaker_id";
    public static final String INTERNAL_SPEAKER_NAME = "_speaker_name";

    private MemorySpeakerUtils() {
    }

    public record SpeakerIdentity(String id, String name) {
        public static SpeakerIdentity shared() {
            return new SpeakerIdentity("", "");
        }

        public boolean recognized() {
            return StringUtils.isNotBlank(id);
        }
    }

    public static final class Registry {
        private final Map<String, String> speakers = new LinkedHashMap<>();
        private boolean hasUnidentifiedUser;

        public void observe(SpeakerIdentity identity) {
            if (identity != null && identity.recognized()) {
                speakers.putIfAbsent(identity.id(), StringUtils.defaultString(identity.name()));
            } else {
                hasUnidentifiedUser = true;
            }
        }

        public SpeakerIdentity resolve(JSONObject modelOutput) {
            String requestedId = StringUtils.trimToEmpty(modelOutput.getStr(INTERNAL_SPEAKER_ID));
            if (StringUtils.isBlank(requestedId)) {
                requestedId = StringUtils.trimToEmpty(modelOutput.getStr(PAYLOAD_SPEAKER_ID));
            }
            if (StringUtils.isNotBlank(requestedId)) {
                String trustedName = speakers.get(requestedId);
                return trustedName == null ? null : new SpeakerIdentity(requestedId, trustedName);
            }
            if (speakers.isEmpty()) {
                return SpeakerIdentity.shared();
            }
            if (speakers.size() == 1 && !hasUnidentifiedUser) {
                Map.Entry<String, String> only = speakers.entrySet().iterator().next();
                return new SpeakerIdentity(only.getKey(), only.getValue());
            }
            return null;
        }

        public boolean requiresExplicitAttribution() {
            return speakers.size() > 1 || (!speakers.isEmpty() && hasUnidentifiedUser);
        }
    }

    public static SpeakerIdentity fromEvent(MemorySourceEventEntity event) {
        if (event == null) {
            return SpeakerIdentity.shared();
        }
        JSONObject payload = parseObject(event.getContentJson());
        String id = StringUtils.defaultIfBlank(event.getSpeakerId(), payload.getStr(PAYLOAD_SPEAKER_ID));
        return normalize(id, payload.getStr("speaker"));
    }

    public static SpeakerIdentity fromChatContent(String content) {
        JSONObject payload = parseObject(content);
        return normalize(payload.getStr(PAYLOAD_SPEAKER_ID), payload.getStr("speaker"));
    }

    public static String contentFromChatPayload(String content) {
        JSONObject payload = parseObject(content);
        if (!payload.containsKey("content")) {
            return StringUtils.defaultString(content).trim();
        }
        return StringUtils.trimToEmpty(payload.getStr("content"));
    }

    public static String userRole(SpeakerIdentity identity) {
        if (identity == null || !identity.recognized()) {
            return "用户[未识别]";
        }
        String name = StringUtils.defaultIfBlank(identity.name(), "已录入声纹");
        return "用户[声纹=" + name + ", speaker_id=" + identity.id() + "]";
    }

    public static void applyInternalIdentity(JSONObject target, SpeakerIdentity identity) {
        if (target == null || identity == null || !identity.recognized()) {
            return;
        }
        target.set(INTERNAL_SPEAKER_ID, identity.id());
        target.set(INTERNAL_SPEAKER_NAME, identity.name());
    }

    public static void applyPayloadIdentity(JSONObject payload, JSONObject action) {
        String speakerId = StringUtils.trimToEmpty(action.getStr(INTERNAL_SPEAKER_ID));
        if (StringUtils.isBlank(speakerId)) {
            payload.remove(PAYLOAD_SPEAKER_ID);
            payload.remove(PAYLOAD_SPEAKER_NAME);
            return;
        }
        payload.set(PAYLOAD_SPEAKER_ID, speakerId);
        payload.set(PAYLOAD_SPEAKER_NAME,
                StringUtils.trimToEmpty(action.getStr(INTERNAL_SPEAKER_NAME)));
    }

    public static String actionSpeakerId(JSONObject action) {
        return action == null ? "" : StringUtils.trimToEmpty(action.getStr(INTERNAL_SPEAKER_ID));
    }

    public static String payloadSpeakerId(MemoryFactEntity fact) {
        return fact == null ? "" : parseObject(fact.getPayload()).getStr(PAYLOAD_SPEAKER_ID, "");
    }

    public static SpeakerIdentity fromFact(MemoryFactEntity fact) {
        JSONObject payload = fact == null ? new JSONObject() : parseObject(fact.getPayload());
        return normalize(payload.getStr(PAYLOAD_SPEAKER_ID), payload.getStr(PAYLOAD_SPEAKER_NAME));
    }

    public static boolean isVisibleToSpeaker(MemoryFactEntity fact, String currentSpeakerId) {
        String factSpeakerId = StringUtils.trimToEmpty(payloadSpeakerId(fact));
        if (StringUtils.isBlank(factSpeakerId)) {
            return true;
        }
        return StringUtils.isNotBlank(currentSpeakerId) && factSpeakerId.equals(currentSpeakerId);
    }

    public static JSONObject parseObject(String value) {
        if (StringUtils.isBlank(value)) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(value);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static SpeakerIdentity normalize(String id, String name) {
        String normalizedId = StringUtils.trimToEmpty(id);
        String normalizedName = StringUtils.trimToEmpty(name);
        if ("未知说话人".equals(normalizedName)) {
            normalizedName = "";
        }
        return new SpeakerIdentity(normalizedId, normalizedName);
    }
}

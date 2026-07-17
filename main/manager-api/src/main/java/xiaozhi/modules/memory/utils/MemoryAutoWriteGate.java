package xiaozhi.modules.memory.utils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

import cn.hutool.json.JSONObject;

/**
 * Hard guardrail for LLM-generated automatic ADD actions.
 */
public final class MemoryAutoWriteGate {

    public record Result(boolean allowed, String reason) {
    }

    private static final List<String> BLOCKED_SOURCE_ROLES = List.of(
            "assistant", "tool", "system", "助手", "工具", "系统");

    private static final List<String> TEMPORARY_EXPRESSIONS = List.of(
            "刚才", "刚刚", "这次", "本次", "当前", "现在正在", "目前正在",
            "现在进入", "当前进入", "这次进入",
            "会议模式来了", "会议模式已开启", "进入会议模式", "开始会议模式",
            "开始记录会议", "正在记录会议", "会议结束", "结束会议模式",
            "要不要听故事", "那个故事", "刚才的故事");

    private static final List<String> TRANSIENT_STATE_EXPRESSIONS = List.of(
            "正在播放", "播放了", "当前歌曲", "这首歌", "这首音乐", "这首",
            "进入游戏", "正在游戏",
            "游戏开始", "游戏结束", "会议模式", "会议记录模式");

    private static final List<String> NEGATIVE_FEEDBACK_EXPRESSIONS = List.of(
            "胡说八道", "你错了", "不对", "不是这个意思", "别乱说", "不要乱说",
            "乱七八糟", "太抽象", "别老是", "不要老是");

    private static final List<String> INTERACTION_PREFERENCE_EXPRESSIONS = List.of(
            "偏好", "喜欢", "不喜欢", "希望", "不希望", "要求", "讨厌",
            "回答", "回复", "表达", "说话", "沟通", "交互", "风格",
            "直接", "具体", "简短", "简洁", "不要", "别");

    private static final List<String> SHARED_FACT_EXPRESSIONS = List.of(
            "共享", "团队", "所有人", "大家", "这台设备", "本设备", "公共");

    private MemoryAutoWriteGate() {
    }

    public static Result evaluate(JSONObject action) {
        if (action == null) {
            return reject("empty_action");
        }
        BigDecimal importance = MemoryHelper.score(action.get("importance"), MemoryHelper.DEFAULT_SCORE);
        if (importance.compareTo(MemoryHelper.MIN_AUTO_IMPORTANCE) < 0) {
            return reject("low_importance");
        }
        BigDecimal confidence = MemoryHelper.score(action.get("confidence"), MemoryHelper.DEFAULT_SCORE);
        if (confidence.compareTo(MemoryHelper.MIN_AUTO_CONFIDENCE) < 0) {
            return reject("low_confidence");
        }

        String canonical = MemoryHelper.cleanupMemoryText(action.getStr("canonical_text"));
        if (StringUtils.isBlank(canonical)) {
            return reject("blank_text");
        }
        if (MemoryHelper.characterCount(canonical) > MemoryHelper.MAX_AUTO_FACT_CHARS) {
            return reject("text_too_long");
        }

        String sourceRole = normalizeRole(action);
        boolean manualInstruction = Boolean.TRUE.equals(action.getBool("_manual_instruction"));
        if (isBlockedSourceRole(sourceRole)) {
            return reject("blocked_source_role");
        }
        if (!manualInstruction && !"user".equals(sourceRole)) {
            return reject("missing_user_source_role");
        }
        if (!manualInstruction && !hasVerifiedUserEvidence(action)) {
            return reject("unverified_user_evidence");
        }

        if (isTemporaryOrRuntimeState(action, canonical)) {
            return reject("temporary_runtime_state");
        }
        if (isUnsafeNegativeFeedback(canonical)) {
            return reject("unsafe_negative_feedback");
        }

        boolean hasSpeakerId = StringUtils.isNotBlank(MemorySpeakerUtils.actionSpeakerId(action))
                || StringUtils.isNotBlank(action.getStr(MemorySpeakerUtils.PAYLOAD_SPEAKER_ID));
        if (!hasSpeakerId && !manualInstruction && !isExplicitSharedLongTermFact(action, canonical)) {
            return reject("shared_fact_not_explicit");
        }

        return new Result(true, "ok");
    }

    private static Result reject(String reason) {
        return new Result(false, reason);
    }

    private static String normalizeRole(JSONObject action) {
        String role = firstNonBlank(
                action.getStr("source_role"),
                action.getStr("evidence_source_role"),
                action.getStr("source_chat_type"),
                action.getByPath("evidence.source_role", String.class),
                action.getByPath("evidence.role", String.class));
        if ("1".equals(role)) {
            return "user";
        }
        if ("2".equals(role)) {
            return "assistant";
        }
        if ("3".equals(role)) {
            return "tool";
        }
        return StringUtils.trimToEmpty(role).toLowerCase(Locale.ROOT);
    }

    private static boolean isBlockedSourceRole(String role) {
        if (StringUtils.isBlank(role)) {
            return false;
        }
        return BLOCKED_SOURCE_ROLES.stream().anyMatch(blocked -> blocked.equalsIgnoreCase(role));
    }

    private static boolean isTemporaryOrRuntimeState(JSONObject action, String canonical) {
        String haystack = joinedText(action, canonical);
        boolean canonicalPreference = isExplicitPreferenceStatement(canonical);
        if (containsAny(canonical, TEMPORARY_EXPRESSIONS)) {
            return true;
        }
        if (containsAny(canonical, TRANSIENT_STATE_EXPRESSIONS)) {
            return true;
        }
        boolean interactionPreference = isInteractionPreference(haystack);
        if (!interactionPreference && containsAny(haystack, TEMPORARY_EXPRESSIONS)) {
            return true;
        }
        if (!interactionPreference && containsAny(haystack, TRANSIENT_STATE_EXPRESSIONS)) {
            return true;
        }
        return false;
    }

    private static boolean hasVerifiedUserEvidence(JSONObject action) {
        String evidence = firstNonBlank(
                action.getStr("evidence_quote"),
                action.getByPath("evidence.quote", String.class),
                action.getByPath("evidence.text", String.class));
        String userEvidence = action.getStr("_user_evidence_text");
        if (StringUtils.isBlank(evidence) || StringUtils.isBlank(userEvidence)) {
            return false;
        }
        return compactForEvidence(userEvidence).contains(compactForEvidence(evidence));
    }

    private static boolean isUnsafeNegativeFeedback(String canonical) {
        return containsAny(canonical, NEGATIVE_FEEDBACK_EXPRESSIONS) && !isInteractionPreference(canonical);
    }

    private static boolean isInteractionPreference(String text) {
        return containsAny(text, INTERACTION_PREFERENCE_EXPRESSIONS);
    }

    private static boolean isExplicitPreferenceStatement(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return text.contains("用户偏好")
                || text.contains("用户希望")
                || text.contains("用户不希望")
                || text.contains("用户喜欢")
                || text.contains("用户不喜欢")
                || text.contains("用户要求")
                || text.contains("用户讨厌");
    }

    private static boolean isExplicitSharedLongTermFact(JSONObject action, String canonical) {
        Object sharedFlag = action.get("shared");
        if (Boolean.TRUE.equals(sharedFlag)) {
            return true;
        }
        String scope = StringUtils.trimToEmpty(firstNonBlank(
                action.getStr("memory_scope"), action.getStr("scope"), action.getStr("audience")));
        if (List.of("shared", "team", "device", "agent", "public").contains(scope.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return containsAny(joinedText(action, canonical), SHARED_FACT_EXPRESSIONS);
    }

    private static String joinedText(JSONObject action, String canonical) {
        return String.join(" ",
                StringUtils.defaultString(canonical),
                StringUtils.defaultString(action.getStr("memory_type")),
                StringUtils.defaultString(action.getStr("category")),
                StringUtils.defaultString(action.getStr("reason")));
    }

    private static boolean containsAny(String text, List<String> patterns) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        return patterns.stream().anyMatch(text::contains);
    }

    private static String compactForEvidence(String text) {
        return StringUtils.defaultString(text)
                .replaceAll("[\\p{Punct}\\s，。！？、；：：“”\"'‘’（）()【】\\[\\]]+", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }
}

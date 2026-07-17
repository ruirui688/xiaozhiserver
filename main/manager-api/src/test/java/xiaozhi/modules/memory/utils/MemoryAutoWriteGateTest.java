package xiaozhi.modules.memory.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import cn.hutool.json.JSONObject;

class MemoryAutoWriteGateTest {

    @Test
    void allowsUserSideInteractionPreferenceWithSpeaker() {
        MemoryAutoWriteGate.Result result = MemoryAutoWriteGate.evaluate(baseAction()
                .set(MemorySpeakerUtils.INTERNAL_SPEAKER_ID, "voice-1")
                .set("source_role", "user")
                .set("evidence_quote", "你要直接具体地分析问题")
                .set("_user_evidence_text", "你要直接具体地分析问题")
                .set("category", "交互偏好")
                .set("memory_type", "用户偏好")
                .set("canonical_text", "用户偏好直接具体的问题分析"));

        assertTrue(result.allowed());
    }

    @Test
    void rejectsAssistantOrToolAsAddSource() {
        assertRejected(baseAction()
                .set(MemorySpeakerUtils.INTERNAL_SPEAKER_ID, "voice-1")
                .set("source_role", "assistant")
                .set("canonical_text", "用户之前说的是大机器人迁移"),
                "blocked_source_role");
        assertRejected(baseAction()
                .set(MemorySpeakerUtils.INTERNAL_SPEAKER_ID, "voice-1")
                .set("source_role", "tool")
                .set("canonical_text", "用户刚才查询了天气工具"),
                "blocked_source_role");
    }

    @Test
    void rejectsMissingSourceRoleForAutomaticAdd() {
        JSONObject action = baseAction()
                .set(MemorySpeakerUtils.INTERNAL_SPEAKER_ID, "voice-1");
        action.remove("source_role");

        assertRejected(action, "missing_user_source_role");
    }

    @Test
    void rejectsMissingOrUnmatchedUserEvidenceForAutomaticAdd() {
        assertRejected(baseAction()
                .set(MemorySpeakerUtils.INTERNAL_SPEAKER_ID, "voice-1")
                .set("evidence_quote", ""),
                "unverified_user_evidence");
        assertRejected(baseAction()
                .set(MemorySpeakerUtils.INTERNAL_SPEAKER_ID, "voice-1")
                .set("evidence_quote", "我喜欢黑色自行车")
                .set("_user_evidence_text", "你好"),
                "unverified_user_evidence");
    }

    @Test
    void allowsManualInstructionWithoutSourceRole() {
        JSONObject action = baseAction()
                .set("_manual_instruction", true);
        action.remove("source_role");

        MemoryAutoWriteGate.Result result = MemoryAutoWriteGate.evaluate(action);
        assertTrue(result.allowed());
    }

    @Test
    void rejectsTemporaryMeetingStoryAndPlaybackStates() {
        assertRejected(baseAction()
                .set(MemorySpeakerUtils.INTERNAL_SPEAKER_ID, "voice-1")
                .set("source_role", "user")
                .set("evidence_quote", "用户喜欢这首歌")
                .set("_user_evidence_text", "用户喜欢这首歌")
                .set("canonical_text", "会议模式来了，我随时准备记录"),
                "temporary_runtime_state");
        assertRejected(baseAction()
                .set(MemorySpeakerUtils.INTERNAL_SPEAKER_ID, "voice-1")
                .set("source_role", "user")
                .set("canonical_text", "用户要不要听故事"),
                "temporary_runtime_state");
        assertRejected(baseAction()
                .set(MemorySpeakerUtils.INTERNAL_SPEAKER_ID, "voice-1")
                .set("source_role", "user")
                .set("canonical_text", "现在正在播放一首歌"),
                "temporary_runtime_state");
    }

    @Test
    void negativeFeedbackCannotPersistAssistantMistakeButCanBecomePreference() {
        assertRejected(baseAction()
                .set(MemorySpeakerUtils.INTERNAL_SPEAKER_ID, "voice-1")
                .set("source_role", "user")
                .set("evidence_quote", "你在胡说八道")
                .set("_user_evidence_text", "你在胡说八道")
                .set("canonical_text", "用户说助手在胡说八道"),
                "unsafe_negative_feedback");

        MemoryAutoWriteGate.Result result = MemoryAutoWriteGate.evaluate(baseAction()
                .set(MemorySpeakerUtils.INTERNAL_SPEAKER_ID, "voice-1")
                .set("source_role", "user")
                .set("category", "交互偏好")
                .set("canonical_text", "用户不希望助手在缺少证据时牵扯旧上下文"));
        assertTrue(result.allowed());
    }

    @Test
    void sharedAutoAddMustBeExplicitlySharedLongTermFact() {
        assertRejected(baseAction()
                .set("source_role", "user")
                .set("evidence_quote", "我在办公室上班")
                .set("_user_evidence_text", "我在办公室上班")
                .set("canonical_text", "用户的老板是钟文杰"),
                "shared_fact_not_explicit");
        assertRejected(baseAction()
                .set("source_role", "user")
                .set("evidence_quote", "我在办公室上班")
                .set("_user_evidence_text", "我在办公室上班")
                .set("canonical_text", "用户在办公室上班"),
                "shared_fact_not_explicit");

        MemoryAutoWriteGate.Result result = MemoryAutoWriteGate.evaluate(baseAction()
                .set("source_role", "user")
                .set("evidence_quote", "这台设备放在办公室前台，所有人都可以使用")
                .set("_user_evidence_text", "这台设备放在办公室前台，所有人都可以使用")
                .set("canonical_text", "这台设备放在办公室前台，所有人都可以使用"));
        assertTrue(result.allowed());
    }

    @Test
    void rejectsTransientStateEvenWhenModelWordsItAsPreference() {
        assertRejected(baseAction()
                .set(MemorySpeakerUtils.INTERNAL_SPEAKER_ID, "voice-1")
                .set("canonical_text", "用户喜欢这首歌"),
                "temporary_runtime_state");
        assertRejected(baseAction()
                .set(MemorySpeakerUtils.INTERNAL_SPEAKER_ID, "voice-1")
                .set("canonical_text", "用户喜欢当前歌曲"),
                "temporary_runtime_state");
        assertRejected(baseAction()
                .set(MemorySpeakerUtils.INTERNAL_SPEAKER_ID, "voice-1")
                .set("canonical_text", "用户偏好现在进入会议模式"),
                "temporary_runtime_state");
    }

    @Test
    void rejectsLowScoresAndOverlongFacts() {
        assertRejected(baseAction()
                .set(MemorySpeakerUtils.INTERNAL_SPEAKER_ID, "voice-1")
                .set("importance", 0.69)
                .set("canonical_text", "用户偏好直接具体的问题分析"),
                "low_importance");
        assertRejected(baseAction()
                .set(MemorySpeakerUtils.INTERNAL_SPEAKER_ID, "voice-1")
                .set("confidence", 0.84)
                .set("canonical_text", "用户偏好直接具体的问题分析"),
                "low_confidence");
        assertRejected(baseAction()
                .set(MemorySpeakerUtils.INTERNAL_SPEAKER_ID, "voice-1")
                .set("canonical_text", "长".repeat(81)),
                "text_too_long");
    }

    private JSONObject baseAction() {
        return new JSONObject()
                .set("action", "ADD")
                .set("source_role", "user")
                .set("memory_type", "用户偏好")
                .set("category", "交互偏好")
                .set("canonical_text", "用户偏好直接具体的问题分析")
                .set("evidence_quote", "你要直接具体地分析问题")
                .set("_user_evidence_text", "你要直接具体地分析问题")
                .set("confidence", 0.9)
                .set("importance", 0.8)
                .set("memory_tier", "long_term");
    }

    private void assertRejected(JSONObject action, String reason) {
        MemoryAutoWriteGate.Result result = MemoryAutoWriteGate.evaluate(action);
        assertFalse(result.allowed());
        assertEquals(reason, result.reason());
    }
}

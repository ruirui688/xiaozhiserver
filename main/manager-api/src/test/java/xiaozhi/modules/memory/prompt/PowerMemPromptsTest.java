package xiaozhi.modules.memory.prompt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PowerMemPromptsTest {

    @Test
    void extractionPromptRequiresUserSideEvidenceAndActivePreferenceDiscovery() {
        assertTrue(PowerMemPrompts.EXTRACTION_PROMPT.contains("用户不需要说“记住”"));
        assertTrue(PowerMemPrompts.EXTRACTION_PROMPT.contains("主动识别用户直接陈述、反复反馈、纠错、否定和交互偏好"));
        assertTrue(PowerMemPrompts.EXTRACTION_PROMPT.contains("助手回复和工具结果只能作为上下文"));
        assertTrue(PowerMemPrompts.EXTRACTION_PROMPT.contains("会议当前状态"));
        assertTrue(PowerMemPrompts.EXTRACTION_PROMPT.contains("用户负反馈可以沉淀为交互偏好"));
    }

    @Test
    void decisionAndReviewPromptsBlockAssistantSourceAndTemporaryRuntimeState() {
        assertTrue(PowerMemPrompts.DECISION_PROMPT.contains("ADD 必须有用户侧证据"));
        assertTrue(PowerMemPrompts.DECISION_PROMPT.contains("助手回复、工具结果、模型猜测不能单独支撑 ADD"));
        assertTrue(PowerMemPrompts.DECISION_PROMPT.contains("会议/游戏/播放状态"));
        assertTrue(PowerMemPrompts.ACTIONS_REVIEW_PROMPT.contains("助手回复和工具结果不能作为唯一事实来源"));
        assertTrue(PowerMemPrompts.ACTIONS_REVIEW_PROMPT.contains("用户负反馈只可沉淀为交互偏好"));
    }
}

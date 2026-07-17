package xiaozhi.modules.memory.prompt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PowerMemPromptsTest {

    @Test
    void rememberPromptLeavesMemorySemanticsToModel() {
        assertTrue(PowerMemPrompts.REMEMBER_COMMAND_PROMPT.contains("主对话模型已经主动决定保存"));
        assertTrue(PowerMemPrompts.REMEMBER_COMMAND_PROMPT.contains("不可被你撤销"));
    }

    @Test
    void reviewPromptKeepsOnlyEvidenceAndIntegrityBoundaries() {
        assertTrue(PowerMemPrompts.ACTIONS_REVIEW_PROMPT.contains("不使用固定人物、事件、关键词、预设分类或场景黑白名单"));
    }
}

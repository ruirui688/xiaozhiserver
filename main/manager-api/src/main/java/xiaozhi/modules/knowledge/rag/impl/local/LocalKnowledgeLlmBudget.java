package xiaozhi.modules.knowledge.rag.impl.local;

import xiaozhi.common.exception.RenException;

/** 单个文档的 LLM 切片消费预算。预算对象必须按文档创建。 */
public final class LocalKnowledgeLlmBudget {
    private final int maxRequests;
    private final long maxPromptChars;
    private int requestCount;
    private long promptChars;

    public LocalKnowledgeLlmBudget(int maxRequests, long maxPromptChars) {
        this.maxRequests = maxRequests;
        this.maxPromptChars = maxPromptChars;
    }

    public void consume(int requestPromptChars) {
        int normalizedChars = Math.max(0, requestPromptChars);
        if (requestCount >= maxRequests) {
            throw new BudgetExceededException(String.format(
                    "本次文档的大模型请求次数已达到安全上限（%d次），任务已停止；请拆分文档后重新上传",
                    maxRequests));
        }
        if (promptChars + normalizedChars > maxPromptChars) {
            throw new BudgetExceededException(String.format(
                    "本次文档发送给大模型的累计内容已达到安全上限（%d字符），任务已停止；请拆分文档后重新上传",
                    maxPromptChars));
        }
        requestCount++;
        promptChars += normalizedChars;
    }

    public static final class BudgetExceededException extends RenException {
        public BudgetExceededException(String message) {
            super(message);
        }
    }
}

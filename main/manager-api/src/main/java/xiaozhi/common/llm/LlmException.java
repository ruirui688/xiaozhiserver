package xiaozhi.common.llm;

/**
 * LLM 调用异常
 * <p>
 * 当 LLM 接口调用失败（网络异常、非 2xx 状态码、响应格式错误等）时抛出。
 * </p>
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}

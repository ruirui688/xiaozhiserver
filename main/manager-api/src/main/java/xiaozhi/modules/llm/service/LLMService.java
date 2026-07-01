package xiaozhi.modules.llm.service;

import java.util.List;

/**
 * LLM服务接口
 * 支持多种大模型调用
 */
public interface LLMService {

    /**
     * 生成聊天记录总结
     * 
     * @param conversation   对话内容
     * @param promptTemplate 提示词模板
     * @return 总结结果
     */
    String generateSummary(String conversation, String promptTemplate);

    /**
     * 生成聊天记录总结（使用默认提示词）
     * 
     * @param conversation 对话内容
     * @return 总结结果
     */
    String generateSummary(String conversation);

    /**
     * 生成聊天记录总结（指定模型ID）
     * 
     * @param conversation 对话内容
     * @param modelId      模型ID
     * @return 总结结果
     */
    String generateSummaryWithModel(String conversation, String modelId);

    /**
     * 生成聊天记录总结（指定模型ID和提示词模板）
     * 
     * @param conversation   对话内容
     * @param promptTemplate 提示词模板
     * @param modelId        模型ID
     * @return 总结结果
     */
    String generateSummary(String conversation, String promptTemplate, String modelId);

    /**
     * 生成结构化 JSON 输出（指定模型ID）。
     *
     * @param conversation 提示词内容
     * @param modelId      模型ID
     * @return JSON 文本；模型或供应商不支持 JSON 模式时由实现回退普通生成
     */
    String generateJson(String conversation, String modelId);

    /**
     * 生成聊天记录总结（包含历史记忆合并）
     * 
     * @param conversation   对话内容
     * @param historyMemory  历史记忆
     * @param promptTemplate 提示词模板
     * @param modelId        模型ID
     * @return 总结结果
     */
    String generateSummaryWithHistory(String conversation, String historyMemory, String promptTemplate, String modelId);

    /**
     * 检查服务是否可用
     * 
     * @return 是否可用
     */
    boolean isAvailable();

    /**
     * 检查指定模型的服务是否可用
     * 
     * @param modelId 模型ID
     * @return 是否可用
     */
    boolean isAvailable(String modelId);

    /**
     * 生成会话标题
     *
     * @param conversation 对话内容
     * @param modelId      模型ID
     * @return 标题（约15字）
     */
    String generateTitle(String conversation, String modelId);

    /**
     * 生成文本嵌入向量（OpenAI 兼容 embeddings API）
     *
     * @param text    待向量化的文本
     * @param modelId 模型配置ID（用于获取 base_url / api_key / embedding_model）
     * @return 嵌入向量（浮点数列表），失败时返回 null
     */
    List<Double> generateEmbedding(String text, String modelId);
}

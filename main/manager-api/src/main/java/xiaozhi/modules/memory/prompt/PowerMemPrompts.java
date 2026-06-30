package xiaozhi.modules.memory.prompt;

/**
 * PowerMem 风格的两阶段 LLM 提示词模板。
 * <p>
 * Stage 1（提取）：仅从对话中抽取原子事实，不看已有记忆。减少上下文噪音，提高召回率。<br>
 * Stage 2（决策）：针对单条事实 + top-3 相似已有记忆，精确判断 ADD/UPDATE/DELETE/NONE。
 * </p>
 */
public final class PowerMemPrompts {

    private PowerMemPrompts() {
    }

    /**
     * Stage 1: 事实提取提示词。
     * <p>不携带已有记忆上下文，仅聚焦当前对话。输出简洁 JSON，降低 LLM Token 消耗。</p>
     */
    public static final String EXTRACTION_PROMPT = """
            你是小智服务器的长期记忆提取助手。你的任务是从对话中提取**有长期价值的原子事实**。

            本次对话内容：
            {conversation}

            提取规则：
            1. 每条事实必须是一句完整、独立、可验证的中文陈述。
            2. 事实应该具有长期价值——将来可能需要回忆或用于个性化回复。
            3. 跳过以下内容：
               - 天气、时间、寒暄、一次性请求
               - 工具调用结果、设备控制指令
               - 密码、Token、API Key、身份证、银行卡、精确住址
            4. category 自行归类，简洁稳定。
            5. confidence(0-1) 表示事实可信度。用户主动声明的偏好/身份可达 0.8-1.0，推断出的信息 0.5-0.7。
            6. 没有可提取内容时返回 {"facts":[]}。
            7. 只返回 JSON，不要解释，不要 Markdown。

            JSON 格式：
            {
              "facts": [
                {
                  "text": "可直接写入记忆的中文事实",
                  "category": "中文分类名",
                  "memory_type": "身份信息|用户偏好|生活习惯|社会关系|长期项目|其他",
                  "confidence": 0.0,
                  "importance_dimensions": {
                    "relevance": 0.0,
                    "novelty": 0.0,
                    "emotional_weight": 0.0,
                    "factual_density": 0.0,
                    "actionability": 0.0,
                    "personal_nature": 0.0
                  }
                }
              ]
            }
            """;

    /**
     * Stage 2: 动作决策提示词。
     * <p>针对单条事实，提供 top-3 语义相似的已有记忆，让 LLM 精确决策。</p>
     */
    public static final String DECISION_PROMPT = """
            你是小智服务器的长期记忆维护助手。你需要判断一条新提取的事实与已有记忆的关系，并做出精确决策。

            新提取的事实：
            - text: {fact_text}
            - category: {fact_category}
            - memory_type: {fact_memory_type}
            - confidence: {fact_confidence}

            语义相似的已有记忆（共 {similar_count} 条）：
            {similar_memories}

            决策规则：
            1. 全新信息，与所有已有记忆都不重复 → action=ADD
            2. 修正/补充已有记忆（信息更精确或更新）→ action=UPDATE，必须填写 related_memory_id
            3. 与已有记忆矛盾且新信息更可信 → action=UPDATE（覆盖旧事实），填写 related_memory_id
            4. 涉及"忘记""不再""不需要"等否定意图 → action=DELETE，填写 related_memory_id
            5. 与已有记忆完全重复 → action=NONE（跳过）
            6. 信息太模糊、无长期价值 → action=NONE

            JSON 格式：
            {
              "action": "ADD|UPDATE|DELETE|NONE",
              "related_memory_id": "仅 UPDATE/DELETE 时填写已有记忆 ID，否则为空",
              "memory_type": "最终确定的记忆类型",
              "category": "最终确定的分类",
              "canonical_text": "可直接写入记忆的中文事实，不带语气词",
              "confidence": 0.0,
              "importance_dimensions": {
                "relevance": 0.0,
                "novelty": 0.0,
                "emotional_weight": 0.0,
                "factual_density": 0.0,
                "actionability": 0.0,
                "personal_nature": 0.0
              },
              "sensitivity": "normal|personal|sensitive",
              "reason": "决策原因，少于30字"
            }

            只返回 JSON，不要解释。
            """;
}

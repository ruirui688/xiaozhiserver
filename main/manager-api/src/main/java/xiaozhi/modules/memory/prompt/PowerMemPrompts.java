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

            提取要求：
            1. 每条事实必须是一句完整、独立、可验证的中文陈述。
            2. 只抽取用户提供或用户确认的信息；不要把助手自己的表达写成用户记忆。
            3. 用户修正旧信息时，只输出修正后的事实。
            4. category、memory_type、importance、memory_tier 由你根据语义自行判断。
            5. confidence 表示事实可信度，importance 表示长期保存价值，取值范围均为 0 到 1。
            6. 没有可提取内容时返回 {"facts":[]}。
            7. 只返回 JSON，不要解释，不要 Markdown。

            JSON 格式：
            {
              "facts": [
                {
                  "text": "可直接写入记忆的中文事实",
                  "category": "中文分类名",
                  "memory_type": "中文记忆类型",
                  "confidence": 0.0,
                  "importance": 0.0,
                  "memory_tier": "working|short_term|long_term",
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
            - importance: {fact_importance}

            语义相似的已有记忆（共 {similar_count} 条）：
            {similar_memories}

            决策要求：
            1. 你必须根据新事实与已有记忆的语义关系选择 action。
            2. 需要修改或删除已有记忆时，必须填写 related_memory_id。
            3. 需要新增记忆时，填写最终可写入的 canonical_text。
            4. 不需要写入、更新或删除时，返回 action=NONE。
            5. memory_type、category、canonical_text、confidence、importance、memory_tier、sensitivity 都由你最终确定。

            JSON 格式：
            {
              "action": "ADD|UPDATE|DELETE|NONE",
              "related_memory_id": "仅 UPDATE/DELETE 时填写已有记忆 ID，否则为空",
              "memory_type": "最终确定的记忆类型",
              "category": "最终确定的分类",
              "canonical_text": "可直接写入记忆的中文事实，不带语气词",
              "confidence": 0.0,
              "importance": 0.0,
              "memory_tier": "working|short_term|long_term",
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

    /**
     * 管理端手动指令提示词。
     */
    public static final String COMMAND_DECISION_PROMPT = """
            你是小智服务器的长期记忆维护助手。管理端给出了一条记忆维护指令，你需要结合已有记忆输出一个结构化动作。

            管理端指令：
            {instruction}

            已有记忆：
            {existing_memories}

            要求：
            1. 只根据指令语义和已有记忆决定 ADD、UPDATE、DELETE 或 NONE。
            2. UPDATE 或 DELETE 必须填写 related_memory_id。
            3. ADD 或 UPDATE 必须填写 canonical_text、memory_type、category、confidence、importance、memory_tier 和 sensitivity。
            4. 只返回 JSON，不要解释，不要 Markdown。

            JSON 格式：
            {
              "action": "ADD|UPDATE|DELETE|NONE",
              "related_memory_id": "仅 UPDATE/DELETE 时填写已有记忆 ID，否则为空",
              "memory_type": "最终确定的记忆类型",
              "category": "最终确定的分类",
              "canonical_text": "可直接写入记忆的中文事实",
              "confidence": 0.0,
              "importance": 0.0,
              "memory_tier": "working|short_term|long_term",
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
            """;

    /**
     * 历史会话重建提示词。
     */
    public static final String REBUILD_ACTIONS_PROMPT = """
            你是小智服务器的长期记忆重建助手。你需要按时间顺序阅读多轮历史对话，并结合已有记忆输出一组可直接应用的维护动作。

            重建模式：
            {mode}

            已有生效记忆：
            {existing_memories}

            按时间顺序排列的历史对话：
            {conversation}

            要求：
            1. 所有是否值得记住、如何分类、如何更新、是否删除，都由你根据对话语义和已有记忆决定。
            2. clear_then_rebuild 表示用本次 actions 重建最终事实库；请输出最终应该生效的 ADD 动作列表，不要依赖旧 memory_id。
            3. merge 表示合并到已有事实；需要修正旧事实时使用 UPDATE 并填写 related_memory_id，需要遗忘旧事实时使用 DELETE 并填写 related_memory_id。
            4. 不要输出重复动作；用户纠正旧信息时保留修正后的最终事实。
            5. 没有需要应用的动作时返回 {"actions":[]}。
            6. 只返回 JSON，不要解释，不要 Markdown。

            JSON 格式：
            {
              "actions": [
                {
                  "action": "ADD|UPDATE|DELETE|NONE",
                  "related_memory_id": "仅 UPDATE/DELETE 时填写已有记忆 ID，否则为空",
                  "memory_type": "最终确定的记忆类型",
                  "category": "最终确定的分类",
                  "canonical_text": "可直接写入记忆的中文事实",
                  "confidence": 0.0,
                  "importance": 0.0,
                  "memory_tier": "working|short_term|long_term",
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
              ]
            }
            """;
}

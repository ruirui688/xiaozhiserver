package xiaozhi.modules.memory.prompt;

/**
 * PowerMem 风格的两阶段 LLM 提示词模板。
 * <p>
 * Stage 1（提取）：仅从对话中抽取原子事实，不看已有记忆。减少上下文噪音，提高召回率。<br>
 * Stage 2（决策）：针对单条事实 + top-3 相似已有记忆，精确判断 ADD/UPDATE/ARCHIVE/DELETE/NONE。
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
            6. 只抽取未来对话可能复用的信息；没有可复用记忆价值时返回 {"facts":[]}。
            7. working 表示短暂上下文，short_term 表示阶段性有用，long_term 表示稳定长期事实或偏好。
            8. 不要依赖固定业务分类；是否值得记住完全由语义、可复用性、稳定性和证据强度决定。
            9. 只返回 JSON，不要解释，不要 Markdown。

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
            2. 需要修改、归档或删除已有记忆时，必须填写 related_memory_id。
            3. 需要新增记忆时，填写最终可写入的 canonical_text。
            4. 不需要写入、更新、归档或删除时，返回 action=NONE；NONE 是常见且合理的结果。
            5. memory_type、category、canonical_text、confidence、importance、memory_tier、sensitivity 都由你最终确定。
            6. importance 代表未来可复用价值，confidence 代表证据确定性；低价值或低确定性内容应返回 NONE 或给出较低分。
            7. 不要依赖固定业务分类；只按语义稳定性、可复用性、用户个人相关性和证据强度决策。

            JSON 格式：
            {
              "action": "ADD|UPDATE|ARCHIVE|DELETE|NONE",
              "related_memory_id": "仅 UPDATE/ARCHIVE/DELETE 时填写已有记忆 ID，否则为空",
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
            1. 只根据指令语义和已有记忆决定 ADD、UPDATE、ARCHIVE、DELETE 或 NONE。
            2. UPDATE、ARCHIVE 或 DELETE 必须填写 related_memory_id。
            3. ADD 或 UPDATE 必须填写 canonical_text、memory_type、category、confidence、importance、memory_tier 和 sensitivity。
            4. 只返回 JSON，不要解释，不要 Markdown。

            JSON 格式：
            {
              "action": "ADD|UPDATE|ARCHIVE|DELETE|NONE",
              "related_memory_id": "仅 UPDATE/ARCHIVE/DELETE 时填写已有记忆 ID，否则为空",
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
            3. merge 表示合并到已有事实；需要修正旧事实时使用 UPDATE，需要降级不可主动注入的事实时使用 ARCHIVE，需要显式删除旧事实时使用 DELETE，并填写 related_memory_id。
            4. 不要输出重复动作；用户纠正旧信息时保留修正后的最终事实。
            5. category 和 memory_type 必须是自然中文短语，面向管理后台用户阅读；不要输出英文枚举、下划线字段名或数据库字段名。
            6. canonical_text 必须是单条自然中文事实，不要把互不相关的信息硬合并到同一句。
            7. 没有需要应用的动作时返回 {"actions":[]}。
            8. 只返回 JSON，不要解释，不要 Markdown。

            JSON 格式：
            {
              "actions": [
                {
                  "action": "ADD|UPDATE|ARCHIVE|DELETE|NONE",
                  "related_memory_id": "仅 UPDATE/ARCHIVE/DELETE 时填写已有记忆 ID，否则为空",
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

    public static final String JSON_REPAIR_PROMPT = """
            你是 JSON 修复器。下面是同一个记忆维护模型刚刚返回的内容，但它不是合法 JSON。
            请只做格式修复：保留原有 action、related_memory_id、memory_type、category、canonical_text、confidence、importance、memory_tier、importance_dimensions、sensitivity、reason 的语义，不新增、不删除、不改写记忆判断。

            原始输出：
            {raw_output}

            只返回合法 JSON，不要解释，不要 Markdown。目标格式：
            {
              "actions": [
                {
                  "action": "ADD|UPDATE|ARCHIVE|DELETE|NONE",
                  "related_memory_id": "",
                  "memory_type": "",
                  "category": "",
                  "canonical_text": "",
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
                  "reason": ""
                }
              ]
            }
            """;

    public static final String ACTIONS_REVIEW_PROMPT = """
            你是小智服务器的长期记忆动作审校助手。下面是一组已经由维护模型决定的记忆动作。
            请只做证据核对、可读性、生命周期和结构审校：不要凭空新增记忆事实，不扩展用户事实含义。
            如果待审校动作是 ADD，但可核对证据中的已有记忆已经表达同一事实或被本次事实修正，请改为 UPDATE 并填写已有记忆的 related_memory_id。

            可核对证据：
            {evidence_context}

            审校要求：
            1. category 和 memory_type 必须改写为自然中文短语，面向管理后台用户阅读。
            2. 不要输出英文枚举、下划线字段名或数据库字段名，例如 personal_info、preference、hobby、fact。
            3. canonical_text 必须只保留证据中能直接支持的单条自然中文事实；无法从证据确认的尾巴、推测、误听或混杂内容必须删去。
            4. 如果一句中混合了互不相关的信息，只保留同一事实核心，不扩展新事实。
            5. merge 模式下，如果 ADD 与已有记忆表达同一事实、同义称呼、补充称呼或纠正旧事实，应改为 UPDATE 并填写 related_memory_id。
            6. 对未来复用价值低、证据不足或不应进入长期事实库的动作，应改为 NONE；对已有但不再适合主动注入的事实可改为 ARCHIVE。
            7. clear_then_rebuild 模式下不要输出 UPDATE、ARCHIVE 或 DELETE 动作；最终事实库只需要 ADD 或 NONE 动作。
            8. 只返回合法 JSON，不要解释，不要 Markdown。

            重建模式：
            {mode}

            待审校动作：
            {actions_json}

            返回格式：
            {
              "actions": [
                {
                  "action": "ADD|UPDATE|ARCHIVE|DELETE|NONE",
                  "related_memory_id": "",
                  "memory_type": "中文记忆类型",
                  "category": "中文分类",
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
                  "reason": ""
                }
              ]
            }
            """;

    public static final String ACTIVE_FACTS_REVIEW_PROMPT = """
            你是小智服务器的长期记忆质量重审助手。下面是当前已经生效的一组记忆事实。
            请按通用长期记忆原则判断每条事实是否仍应保留、更新、归档或删除。

            当前生效记忆：
            {active_memories}

            重审原则：
            1. 不使用固定业务黑名单或关键词规则，只根据语义稳定性、未来可复用性、个人相关性、证据强度和上下文价值判断。
            2. 仍然适合主动注入对话上下文的事实返回 NONE。
            3. 内容表达不佳但事实有价值时返回 UPDATE，并填写 related_memory_id 和改写后的 canonical_text。
            4. 事实低价值、过期、噪声化或不应再主动注入但需要保留审计时返回 ARCHIVE。
            5. 明确错误、重复或不应保留时返回 DELETE。
            6. 不要新增与现有记忆无关的事实。
            7. importance 代表未来可复用价值，confidence 代表事实可信度；memory_tier 可调整为 working、short_term 或 long_term。
            8. 只返回 JSON，不要解释，不要 Markdown。

            返回格式：
            {
              "actions": [
                {
                  "action": "UPDATE|ARCHIVE|DELETE|NONE",
                  "related_memory_id": "当前记忆 ID",
                  "memory_type": "中文记忆类型",
                  "category": "中文分类",
                  "canonical_text": "UPDATE 时填写改写后的事实；其他动作可填原文",
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
                  "reason": "少于30字"
                }
              ]
            }
            """;
}

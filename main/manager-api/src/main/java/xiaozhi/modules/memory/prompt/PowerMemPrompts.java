package xiaozhi.modules.memory.prompt;

/** 主模型发起记忆操作后使用的维护、重建和审校提示词模板。 */
public final class PowerMemPrompts {

    private PowerMemPrompts() {
    }

    /**
     * 管理端显式记住提示词。
     */
    public static final String REMEMBER_COMMAND_PROMPT = """
            你是小智服务器的长期记忆维护助手。主对话模型已经主动决定保存下面的内容并调用了记忆工具；你只负责结合已有记忆完成结构化维护。

            显式记住内容：
            {text}

            用户指定分类（可为空）：
            {category}

            已有记忆：
            {existing_memories}

            要求：
            1. 主对话模型的保存决定不可被你撤销；必须输出 ADD 或 UPDATE，不得输出 NONE。
            2. 如果已有记忆表达同一事实或需要修正旧事实，输出 UPDATE 并填写 related_memory_id。
            3. ADD 或 UPDATE 必须填写 canonical_text、memory_type、category、confidence、importance、memory_tier 和 sensitivity。
            4. 正文、分类、层级和重要性由你根据工具输入与已有记忆自主整理，不使用固定关键词、预设业务分类或场景规则。
            5. category 可以参考主对话模型给出的分类，但必须改写为自然中文短语。
            6. 只返回 JSON，不要解释，不要 Markdown。

            JSON 格式：
            {
              "action": "ADD|UPDATE",
              "related_memory_id": "仅 UPDATE 时填写已有记忆 ID，否则为空",
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
     * 管理端显式忘记提示词。
     */
    public static final String FORGET_COMMAND_PROMPT = """
            你是小智服务器的长期记忆治理助手。主对话模型已经主动决定忘记下面的内容并调用了记忆工具；你负责根据完整语义找出应删除或归档的已有记忆，并保留该忘记意图。

            显式忘记内容：
            {text}

            忘记原因（可为空）：
            {reason}

            规则类型偏好（semantic/exact/category，可为空）：
            {pattern_type}

            已有记忆：
            {existing_memories}

            要求：
            1. 这是“忘记”入口，不要输出 ADD 或 UPDATE。
            2. 如果已有记忆应彻底遗忘，输出 DELETE；如果只是未来不主动注入，输出 ARCHIVE。
            3. 能匹配到已有记忆时必须填写 related_memory_id。
            4. 关联判断完全由你根据工具输入和已有记忆的完整语义完成，不使用固定关键词、字符串包含关系、预设业务分类或场景规则。
            5. tombstone_pattern 是面向主对话模型展示的简短忘记意图，不由服务端用于自动匹配或阻断。
            6. 没有匹配事实时返回 NONE，但仍填写 tombstone_pattern，以便主对话模型在后续对话中自主判断。
            7. 只返回 JSON，不要解释，不要 Markdown。

            JSON 格式：
            {
              "action": "DELETE|ARCHIVE|NONE",
              "related_memory_id": "仅 DELETE/ARCHIVE 时填写已有记忆 ID，否则为空",
              "canonical_text": "被忘记事实或规则核心文本",
              "tombstone_pattern": "忘记规则文本",
              "pattern_type": "semantic|exact|category",
              "confidence": 0.0,
              "reason": "忘记原因，少于30字"
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
            1. 所有是否值得记住、如何分类、如何更新、是否删除，都由你根据完整对话语义和已有记忆自主决定；不要使用固定关键词、预设业务分类或场景黑白名单。
            2. clear_then_rebuild 表示用本次 actions 重建最终事实库；请输出最终应该生效的 ADD 动作列表，不要依赖旧 memory_id。
            3. merge 表示合并到已有事实；需要修正旧事实时使用 UPDATE，需要降级不可主动注入的事实时使用 ARCHIVE，需要显式删除旧事实时使用 DELETE，并填写 related_memory_id。
            4. 不要输出重复动作；用户纠正旧信息时保留修正后的最终事实。
            5. category 和 memory_type 必须是自然中文短语，面向管理后台用户阅读；不要输出英文枚举、下划线字段名或数据库字段名。
            6. canonical_text 必须是单条自然中文事实，不要把互不相关的信息硬合并到同一句。
            7. 没有需要应用的动作时返回 {"actions":[]}。
            8. 只返回 JSON，不要解释，不要 Markdown。
            9. 用户角色包含 speaker_id 时，每条动作必须原样保留事实来源 speaker_id；不得把一位用户的事实归给另一位用户。
            10. ADD 必须有用户侧证据；助手回复和工具结果只能作为上下文。

            JSON 格式：
            {
              "actions": [
                {
                  "action": "ADD|UPDATE|ARCHIVE|DELETE|NONE",
                  "related_memory_id": "仅 UPDATE/ARCHIVE/DELETE 时填写已有记忆 ID，否则为空",
                  "speaker_id": "事实来源用户的真实speaker_id；共享或未识别事实为空",
                  "source_role": "user|assistant|tool；ADD 的事实来源必须是 user",
                  "evidence_quote": "支持该动作的最短用户原话",
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
            请只做格式修复：保留原有 action、related_memory_id、speaker_id、source_role、evidence_quote、memory_type、category、canonical_text、confidence、importance、memory_tier、importance_dimensions、sensitivity、reason 的语义，不新增、不删除、不改写记忆判断。

            原始输出：
            {raw_output}

            只返回合法 JSON，不要解释，不要 Markdown。目标格式：
            {
              "actions": [
                {
                  "action": "ADD|UPDATE|ARCHIVE|DELETE|NONE",
                  "related_memory_id": "",
                  "speaker_id": "原输出中的speaker_id",
                  "source_role": "原输出中的source_role",
                  "evidence_quote": "原输出中的evidence_quote",
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
            1. 只做证据、可读性、身份、生命周期和 JSON 结构审校，不使用固定人物、事件、关键词、预设分类或场景黑白名单改变模型的语义判断。
            2. canonical_text 必须能由证据直接支持，并保持主体、对象、关系、条件和时间方向准确。
            3. merge 模式下，语义重复或对同一事实的修正应使用 UPDATE 并填写真实 related_memory_id。
            4. clear_then_rebuild 模式只输出 ADD 或 NONE。
            5. speaker_id 是可信身份边界，只能保留原值，不得修改、删除、交换或编造。
            6. ADD 必须能追溯到用户侧证据。
            7. 只返回合法 JSON，不要解释，不要 Markdown。

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
                  "speaker_id": "原动作中的speaker_id",
                  "source_role": "原动作中的source_role",
                  "evidence_quote": "支持该动作的最短用户原话",
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
            1. 是否保留、更新、归档或删除全部由你根据事实语义、证据和当前价值自主判断；不要使用固定业务黑名单、关键词、预设分类或场景规则。
            2. 需要更新时填写真实 related_memory_id 和 canonical_text；不要新增与现有记忆无关的事实。
            3. importance、confidence、memory_tier 和 sensitivity 由你自主判断。
            4. speaker_id 是可信身份归属，必须原样保留，不得修改或交换。
            5. 只返回 JSON，不要解释，不要 Markdown。

            返回格式：
            {
              "actions": [
                {
                  "action": "UPDATE|ARCHIVE|DELETE|NONE",
                  "related_memory_id": "当前记忆 ID",
                  "speaker_id": "当前记忆的speaker_id",
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

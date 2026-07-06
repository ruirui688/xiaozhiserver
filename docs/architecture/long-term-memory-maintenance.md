# 长期记忆维护架构

本文记录本项目长期记忆维护能力的首期工程边界。它解决的不是“保存聊天记录”，而是把设备对话、工具调用、用户显式记住/忘记和管理端维护动作，转成可归属、可查询、可修改、可归档、可删除、可审计的长期记忆对象。

## 首期边界

首期使用现有 `manager-api` 的 MySQL + Liquibase 作为事实存储，不引入 PostgreSQL、pgvector、Neo4j 或独立向量库。

核心链路：

```text
xiaozhi-server reportHandle
  -> /config/memory/events
  -> ai_memory_source_event
  -> 显式“记住/忘记”抽取
  -> ai_memory_fact / ai_memory_forget_tombstone / ai_memory_revision

xiaozhi-server ConnectionHandler.chat()
  -> /config/memory/query
  -> profile + facts + tool behavior + tombstone
  -> Dialogue.get_llm_dialogue_with_memory()

manager-web MemoryManagement.vue
  -> /agent/{agentId}/memories
  -> 图谱投影视图 + 查看、编辑、归档、删除、确认候选、手动记住、手动忘记
```

首期能力：

- 原始事件证据层：`ai_memory_source_event`
- 候选记忆层：`ai_memory_candidate`，可由配置的维护 LLM 生成，默认等待人工确认
- 长期事实层：`ai_memory_fact`
- 修订审计：`ai_memory_revision`
- 当前画像：`ai_memory_profile`
- 工具经验聚合：`ai_memory_tool_behavior`
- 忘记名单：`ai_memory_forget_tombstone`
- Web 图谱投影：把事实、候选、分类和忘记规则直接从 dashboard 数据投影为可点击节点
- Python 运行时查询超时保护，记忆服务异常时不阻塞语音主链路

## 作用域

运行时事件默认按设备作用域保存：

```text
scope_type = device
scope_id   = mac_address
```

管理端手动创建的记忆默认按智能体通用作用域保存：

```text
scope_type = device
scope_id   = agent_id
```

运行时查询会同时读取当前设备作用域和智能体通用作用域。这样可以兼容“某个设备独有偏好”和“该智能体下所有设备共享的人工维护记忆”。

## 与现有记忆组件的关系

现有 Memory provider 继续保留：

- `mem_local_short`：短摘要记忆，仍可写 `summaryMemory`。
- `powermem`：PowerMem 检索和画像能力，仍按 provider 配置运行。
- `mem0ai`：第三方记忆服务。
- `managed_memory`：智控台治理记忆，provider 本身不保存私有记忆；事件、候选、事实、审计、忘记名单和运行时查询全部走 manager-api。
- `mem_report_only`：只上报聊天记录。
- `nomem`：不使用记忆。

长期记忆维护层是 manager-api 侧的治理能力。它不替代 provider，而是在全模块部署时为所有非 `nomem` 记忆模式提供统一的维护、查询和删除入口。Python 会把 provider 查询结果与 manager-api 长期记忆查询结果合并后注入主 LLM。

`managed_memory` 是推荐的治理型入口，用于把 PowerMem 的“长期检索、画像、衰减、经验沉淀”设计方向落到本项目已有的管理端事实层中：

- Python 侧只声明记忆模式，不再新建本地向量库、文件摘要或第三方服务状态。
- manager-api 侧保留 `source_event -> candidate -> fact -> revision/profile/tool_behavior/tombstone` 的审计链路。
- 运行时查询仍受设备/智能体作用域、删除墓碑、超时降级和 `nomem` 门控控制。
- 后续如引入向量、全文、图关系或时间衰减召回，应扩展 manager-api 检索层，而不是继续增加 Python provider 私有存储。

## PowerMem 借鉴后的治理分层

PowerMem 的核心价值不是“把所有聊天都存下来”，而是把记忆拆成可维护生命周期：抽取、去重、更新、检索、衰减和画像。当前项目首期不直接引入 PowerMem 的向量存储或独立 `UserMemory` 运行态，但 `managed_memory` 必须吸收以下治理规则：

```text
source_event 原始证据
  -> maintenance_llm 输出 ADD / UPDATE / DELETE / NONE 动作
  -> 实时维护和历史重建按 LLM 动作直接写入、更新或删除 fact
  -> candidate 仅用于管理端预览和人工审核入口
  -> revision 记录每次确认、更新、刷新、删除
  -> query 只读取 active fact，不读取候选和原始聊天
```

分类不使用服务端预设表。维护模型应根据已有记忆和当前语义自主归纳分类：能复用已有分类就复用，不能复用时生成稳定、简短、面向人的分类。服务端只保存和展示分类，不通过关键词规则强行改写分类。

维护模型可以输出 `ADD`、`UPDATE`、`IGNORE` 三类维护动作。重复内容应返回 `IGNORE`；修正已有事实时返回 `UPDATE` 和 `related_memory_id`；真正的新长期事实才返回 `ADD`。后端只做通用质量门控、近似重复检查和审计记录，不内置业务分类规则。

## 维护模型配置

Memory 模型配置可以通过普通参数 `maintenance_llm` 指定一个已在模型管理中配置好的 LLM 模型 ID，例如 DeepSeek、通义千问、OpenAI 兼容服务或本地 Ollama。

配置边界：

- `maintenance_llm` 只保存 LLM 模型配置 ID，不保存 `base_url`、`api_key`、`model_name` 等凭据。
- DeepSeek 等模型的 API Key 仍保存在对应 `LLM` 模型配置中，由标准模型配置密钥表管理。
- `managed_memory` 必须配置 `maintenance_llm`。显式“记住/忘记”、自动抽取和历史重建都由维护模型输出结构化动作；未配置时模型配置测试不通过。
- 维护模型只处理用户发言 `chat_type = 1`，不会抽取助手回复或工具调用结果。
- 维护模型输出结构化动作，后台根据动作写入 `ai_memory_fact` 或生成预览项；是否记住、如何分类、是否更新由维护模型决定。
- 显式“记住/忘记”会交给维护模型结合已有记忆输出动作，再由后台执行对应事实更新。

推荐流程：

```text
模型配置 -> LLM -> 新增/测试 DeepSeek 等模型
模型配置 -> Memory -> 编辑当前记忆模型 -> maintenance_llm 填入该 LLM 模型 ID
角色配置 -> 为智能体选择该 Memory 模型
记忆管理页 -> 审核维护模型生成的候选记忆
```

维护模型提示词会要求返回 JSON。解析失败、模型不可用或返回空动作时会跳过本次事实变更，但原始事件仍会入库，不影响聊天记录上报和语音主链路。

## 可视化

管理后台的记忆管理页提供首期图谱视图，不额外引入图数据库或前端图谱依赖。它从 `/agent/{agentId}/memories` 返回的 dashboard 数据中即时投影：

```text
智能体 -> 记忆分类 -> 事实记忆
智能体 -> 候选记忆
智能体 -> 忘记规则
```

节点颜色区分智能体、分类、事实、候选和忘记规则。点击节点可以查看完整内容、作用域、置信度、重要性、原因和状态。该视图用于运营和调试时快速看清“当前记忆库里有什么、哪些是候选、哪些被忘记”，不作为运行时检索索引。

图谱详情面板必须提供和当前节点匹配的维护动作，避免用户选中记忆后还要切换到表格里查找同一条记录：

- 事实记忆节点：可以直接编辑、归档、删除。
- 候选记忆节点：可以直接确认或拒绝。
- 忘记规则节点：展示规则内容、原因和状态，首期不提供直接删除 tombstone。

面板和表格展示应使用面向用户的中文名称，例如把 `explicit` 展示为“用户明确记住”、把 `active` 展示为“生效中”。底层枚举值只作为接口和数据库字段保存，不应直接暴露为主要操作文案。

首期图谱是 UI 投影，不会改变 MySQL 事实层结构。后续如果需要人物、地点、组织、设备之间的真实关系图，可以在现有 `ai_memory_fact.payload` 或新关系表中增加结构化边，再把 Web 图谱从分类投影升级为实体关系图。

## 安全与运维

- `/config/memory/**` 走 `server.secret`，只允许 xiaozhi-server 调用。
- `/agent/{agentId}/memories/**` 走登录态和智能体归属检查。
- 删除采用软删除和 revision 审计；显式忘记会写 tombstone，防止同类内容被继续使用。
- 记忆查询默认限制返回数量，避免把全量历史塞入 LLM 上下文。
- 维护模型是可选能力；未配置时不产生额外外部模型调用和延迟。
- 维护模型在事件入库事务提交后执行，失败只记录日志，不回滚原始事件。

## 后续增强

后续可以在不破坏当前表结构的前提下逐步增加：

- 后台异步候选抽取任务，把同步维护模型调用迁移到独立任务队列。
- 工具调用成功/失败签名聚合，更新 `ai_memory_tool_behavior`。
- 向量索引或全文索引，替换首期 MySQL LIKE / 最新排序召回。
- 家庭共享、访客、声纹用户级作用域。
- 画像压缩任务，把事实层投影到 `ai_memory_profile`。
- 结构化图谱关系表或图数据库投影视图，用于人物、地点、组织、设备关系查询。

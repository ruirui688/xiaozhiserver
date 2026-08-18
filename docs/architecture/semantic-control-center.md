---
status: supporting
owner: xiaozhi-server
last_verified: 2026-08-18
source_paths:
  - main/xiaozhi-server/config.yaml
  - main/xiaozhi-server/core/semantic
  - main/xiaozhi-server/core/handle/receiveAudioHandle.py
  - main/manager-api/src/main/java/xiaozhi/modules/agent/skill
  - main/manager-web/src/components/role/AgentSkillDialog.vue
---

# 语义控制中枢架构

本文记录小智从“LLM 驱动工具调用”演进到“语音操作系统控制中枢”的第一阶段落地方式。目标不是多接几个 MCP，而是让高频控制、状态上下文、拒识和多轮任务先在服务端形成闭环。

## 1. 架构定位

旧主链路可以简化为：

```text
VAD -> ASR -> Intent / LLM function_call -> 工具 / MCP -> TTS
```

第一阶段新增语义控制中枢后，运行链路变为：

```text
VAD / ASR / 文本输入
  -> TextNormalizer
  -> SkillRegistry / SkillMatcher
  -> SemanticRouter
  -> StateManager
  -> PolicyEngine
  -> SemanticCenter
    -> 直接控制 / 状态机游戏 / 工具调用 / 拒识反问 / 旧 LLM 兜底
  -> TTS / 动作 / 状态更新
```

核心原则：

```text
Router + StateManager + PolicyEngine 是控制器
LLM 是复杂语义处理器和兜底生成器
MCP / 插件 / IoT 是工具执行层
```

TTS 播放期间的打断采用官方的 barge-in 机制：支持 AEC 的设备一旦检测到用户开口就立即停止播报，非 AEC 设备在完整 ASR 到达后停止。退出游戏、继续等具有明确会话状态约束的操作，仍可由服务端状态和策略直接处理。

### 交互注意力前置边界

语义控制中枢回答“用户想做什么”，不能回答“用户是否在对小智说话”。车机中同一麦克风还会采集乘员交谈、电话、
媒体和导航人声，因此目标链路必须在 `SemanticCenter` 前增加独立的交互注意力门控：

```text
VAD / ASR / 设备最终文本
  -> Interaction Attention Gate
     -> DIRECTED -> SemanticCenter -> Intent / LLM / 工具
     -> UNDIRECTED -> 丢弃
     -> UNKNOWN_ADDRESSING -> 安全阻断
```

无明确音区信息时，MVP 使用明确唤醒或按键触发、`ARMED / ENGAGED / AWAITING_REPLY / SPEAKING`
交互状态、时间窗口、上一轮问答、当前文本和 ASR 置信度。音区不是必填项，声纹也只能作为辅助证据。

`UNDIRECTED` 和 `UNKNOWN_ADDRESSING` 在强制模式下必须在创建回复 generation 之前结束本轮，并且不得进入：

- `SemanticCenter`、Intent 或主 LLM；
- 插件、MCP、Device IoT 或其他工具执行；
- Dialogue、后台聊天记录或长期记忆事件。

`DIRECTED` 只代表允许进入语义链路，不代表允许执行车控。高风险车控仍必须经过现有工具白名单、资源所有权、车辆状态、
参数范围和必要的二次确认，不能把 Addressing 置信度当作授权结果。

默认 `interaction_attention.mode=shadow` 只记录三态决策、置信度和原因，不改变当前行为。运行时代码已在
`startToChat()` 的语义、Intent、LLM 和播报打断之前执行门控；`enforce` 中判断异常、无结果或非法结果按
`UNKNOWN_ADDRESSING` 失败关闭，禁止回退为默认放行。

## 2. 当前落地范围

当前实现是单体内前置拦截器，不拆微服务，不改变设备协议，不迁移现有插件。

新增代码位置：

```text
main/xiaozhi-server/core/semantic/
  frames.py            语义数据结构 (ASRResult, RobotContext, IntentFrame, Decision)
  normalizer.py        文本标准化 (route_key, 标点/空格/唤醒词移除)
  skill_matcher.py     固定播报技能规则匹配与稳定冲突决胜
  skill_registry.py    当前连接的智能体技能配置编译与边界校验
  router.py            高置信度状态路由 (游戏/继续/兜底，不判断自然语言停止意图)
  state_manager.py     连接级状态投影 (RLock 线程安全)
  policy_engine.py     状态与策略判断 (execute/reject/fallback_chat)
  semantic_center.py   编排执行 (4 阶段管道: normalize→route→decide→execute)
```

接入点：

```text
ConnectionHandler.__init__
  -> self.semantic_center = SemanticCenter()

startToChat()
  -> 非 AEC 设备如果仍在播放，完整 ASR 到达后直接停止当前播报
  -> conn.semantic_center.handle_user_text(conn, actual_text)
     -> 空闲态固定播报技能命中时直接进入当前 TTS，不调用 Intent/LLM
  -> 未处理时继续走 handle_user_intent()
  -> 再未处理时继续走 conn.chat()
```

工具层兼容入口：

```text
UnifiedToolHandler.invoke(tool_name, arguments, source)
  -> ToolManager.execute_tool()
```

旧链路仍保留：

```text
handle_user_intent()
ConnectionHandler.chat()
LLM response_with_functions()
UnifiedToolHandler.handle_llm_function_call()
```

所以当前改造是“确定性控制前移”，不是删除 function-call。

### 智能体固定播报技能

`manager-api` 把每个智能体启用的技能作为 `semantic_skills` 下发，新连接由 `SkillRegistry` 编译为连接级只读规则。一个技能可包含多条 OR 规则：`phrase` 对一条完整话术做规范化后的包含匹配，`all_keywords` 要求同组关键词全部出现且顺序不限。例如 `["惠易公司", "介绍"]` 可以命中“请介绍一下惠易公司”，但不会命中“惠易公司天气怎么样”或“介绍一号点位”。

多个技能同时命中时依次比较技能优先级、命中 term 数量、term 总长度、完整话术优先、页面排序和技能 ID。该排序同时由 Web 模拟器和 Python 运行时实现，并通过相同样例回归。

固定技能只允许在 `idle` 状态触发，不能抢占会议、声纹采集、通话或游戏状态。命中后通过现有 reply generation 和 `FIRST/TEXT/LAST` TTS 队列播报，继续支持 AEC 打断；用户文本和固定回复保留聊天记录与 Dialogue 上下文，但标记跳过长期记忆。TTS 不可用或入队失败时终止本轮，不得回退 LLM 生成另一份答案。未配置、配置无效或未命中时继续旧 Intent/主 LLM 链路。

### 播报期间即时打断

打断与句尾分段分开处理，只保留 VAD 模型的单一 `min_silence_duration_ms`。设备在 `hello.features.aec=true` 声明支持 AEC 后，VAD 连续确认用户开口即可调用 `handleAbortMessage()`，无需等待句尾、完整 ASR 或额外 LLM 请求。当前用户音频会保留并继续进入 ASR，避免打断时截掉首字。

当前链路：

```text
AEC 设备：VAD 确认开口 -> 立即停止 TTS -> 用户音频继续进入 ASR
非 AEC 设备：VAD 句尾 -> 完整 ASR -> 如果仍在播放则停止 TTS
完整 ASR -> SemanticCenter -> Intent -> Chat
```

该机制不维护停止词表，也不区分附和、停止命令和新问题：任何有效的重叠发言都会停止当前播报。ASR 文本随后仍按普通用户轮次进入语义与聊天链路。

## 3. 核心数据结构

`ASRResult` 表示用户输入：

```text
session_id
device_id
raw_text
normalized_text
display_text
asr_confidence
metadata
```

`RobotContext` 表示当前机器人上下文：

```text
device_id
session_id
state
current_skill
tts_playing
motion_running
active_game_tool
client_is_speaking
client_listen_mode
last_intent
```

`IntentFrame` 表示结构化语义：

```text
domain
intent
confidence
slots
source
```

`Decision` 表示策略输出：

```text
decision     execute / reject / fallback_chat
executor
arguments
reply
next_state
priority
reason
```

## 4. 第一阶段行为

### 播报停止

播报停止不依赖 `SemanticRouter` 或固定停止词集合。AEC 设备在用户开口时立即复用 `handleAbortMessage()` 设置 `client_abort`、增加 `abort_generation`、清理 TTS 队列并向设备发送 `tts stop`；非 AEC 设备在完整 ASR 到达后执行相同停止动作。用户文本随后按普通轮次继续处理。

### 游戏上下文

成语接龙、你说我猜、小智想词用户猜等纯语言游戏不再进入语义控制中枢的状态机工具路径。用户输入应继续回到主 LLM 对话链路，由模型自然维护玩法上下文。

用户说：

```text
退出游戏 / 结束游戏 / 不玩了 / 游戏结束 / 不猜了 / 换游戏 / 换个游戏 / 停
```

会触发：

```text
game.exit
  → GameSessionManager.clear_active_games()
  → TTS 回复”好的，游戏先到这。”
  → 不关闭 websocket
```

纯语言游戏 code 仅保留兼容标识；`ServerPluginExecutor` 跳过注册，
`GameSessionManager.get_requested_game_tool()` 不得按关键词强制路由。相关用户输入继续回到主 LLM，
由功能插件软提示词主持游戏流程。

语言形式的退出意图同样由模型结合上下文选择退出工具；服务器不再维护 `exit_commands` 精确匹配旁路。

### 继续类

用户在空闲状态说“继续”，会被拒识反问：

```text
你想继续什么？
```

当前未实现 intro/company_intro 的段落状态，因此非游戏状态下的“继续介绍/下一段”仍会回到旧 LLM 流程或反问。后续应把讲解技能纳入 `current_skill` 和 `StateManager`。

### 普通聊天

未命中高置信度规则的文本返回 `fallback_chat`：

```text
SemanticCenter 返回 handled=False
startToChat 继续 handle_user_intent()
再继续 conn.chat()
```

因此天气、新闻、RAGFlow、本地知识库、MCP function-call、闲聊等旧能力不受第一阶段影响。

## 5. 状态来源

第一阶段状态不另建数据库，直接投影现有连接字段：

```text
client_is_speaking
client_listen_mode
game_session_manager.get_active_tool()
session_id
device_id
semantic_center.state_manager.last_intent
```

状态优先级：

```text
存在 active_game_tool -> game
client_is_speaking -> speaking
否则 -> StateManager 内存状态，默认 idle
```

当前状态是连接级内存，断线丢失。需要跨连接恢复时，再引入按 `device_id/session_id` 的状态存储。

**线程安全（新增）**：`StateManager` 使用 `threading.RLock`（可重入锁）保护所有状态读写 — `record_intent()` 调用 `transition()` 在同一流程中不会死锁。`semantic_center._invoke_tool()` 新增 try/except 保护工具调用异常。

**路由日志（新增）**：`router.py` 和 `semantic_center.py` 中所有路由决策和语义处理路径均添加 debug 级别日志，用于生产环境诊断。

## 6. 与工具系统的关系

工具系统仍以现有统一工具层为主：

```text
UnifiedToolHandler
  -> ToolManager
  -> ServerPluginExecutor
  -> ServerMCPExecutor
  -> MCPEndpointExecutor
  -> DeviceIoTExecutor
  -> DeviceMCPExecutor
```

语义中枢新增的是“直接调用入口”，不是新的工具注册系统：

```text
Router / Policy -> UnifiedToolHandler.invoke()
LLM function_call -> UnifiedToolHandler.handle_llm_function_call()
```

两条入口最终都进入 `ToolManager.execute_tool()`，这样服务端插件、MCP、设备 IoT 和设备端 MCP 仍保持统一执行边界。

## 7. 后续演进

推荐按以下顺序扩展，不要一次性拆成多个服务：

1. 实现独立的 Interaction Attention Gate，并先以 `shadow` 采集无音区真实数据。
2. 在保持确定性边界的前提下扩展技能允许状态、命中统计和配置热更新。
3. 完善 `RobotContext`：加入动作运行、TTS 播放、当前讲解段落、电量、网络、传感器安全状态。
4. 扩展 `PolicyEngine`：加入动作安全、电量、地面安全、二次确认、低置信度确认。
5. 把 RAGFlow/local knowledge 提升为知识类 skill，但保持旧插件名兼容。
6. 增加主动服务规则：迎宾、讲解结束推荐、游戏超时提示、长时间无响应提示。
7. 在日志中记录 `AddressingResult -> ASRResult -> IntentFrame -> Decision -> Executor -> Result`，用于回归测试和拒识优化。

## 8. 验证

当前新增测试：

```text
main/xiaozhi-server/tests/test_semantic_router.py
main/xiaozhi-server/tests/test_semantic_policy.py
main/xiaozhi-server/tests/test_semantic_skills.py
```

验证命令：

```bash
cd main/xiaozhi-server
conda run -n xiaozhi-esp32-server python -m compileall -q core/semantic core/handle/receiveAudioHandle.py core/connection.py core/providers/tools/unified_tool_handler.py
conda run -n xiaozhi-esp32-server python tests/test_semantic_router.py
conda run -n xiaozhi-esp32-server python tests/test_semantic_policy.py
```

完整测试仍建议使用：

```bash
conda run -n xiaozhi-esp32-server pytest tests
```

如果本地缺少 `pytest` 或项目依赖，需要先按开发环境文档安装依赖。

## 9. 关联文档

- [项目完整架构](./project-architecture.md)和[运行时链路](./runtime-flows.md) — 语义控制中枢在全链路中的位置
- [提示词注入工程](./prompt-injection-engineering.md) — LLM 兜底时的提示词和行为约束

端到端语音模型、个性化 VAD、音频淡出和训练数据采集仍是研究方向，不是当前运行契约。引入前必须单独完成工具调用兼容、降级开关、设备资源、隐私同意、数据导出/删除和真实延迟评测。

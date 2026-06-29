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

“停”“别说了”“退出游戏”“继续”等高频语音操作不能先交给 LLM 猜，而应由服务端规则、状态和策略直接处理。

## 2. 当前落地范围

当前实现是单体内前置拦截器，不拆微服务，不改变设备协议，不迁移现有插件。

新增代码位置：

```text
main/xiaozhi-server/core/semantic/
  frames.py            语义数据结构
  interruption.py      TTS 播放期间重叠语音的大模型打断裁决
  normalizer.py        文本标准化
  router.py            高置信度规则路由
  state_manager.py     连接级状态投影
  policy_engine.py     状态与策略判断
  semantic_center.py   编排执行
```

接入点：

```text
ConnectionHandler.__init__
  -> self.semantic_center = SemanticCenter()

startToChat()
  -> 如果 TTS 正在播放，先调用 SemanticInterruptionClassifier 让当前 LLM 裁决是否打断
  -> conn.semantic_center.handle_user_text(conn, actual_text)
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

### 播报期间语义打断

用户在 TTS 播放期间说话时，服务端不再仅因为 ASR 出了文本就立即 `handleAbortMessage()`。

当前链路：

```text
ASR 文本
  -> SemanticInterruptionClassifier
  -> 当前 LLM response_no_stream 裁决 continue / interrupt / new_turn
  -> continue：不打断、不进入主聊天，当前 TTS 继续
  -> interrupt/new_turn：复用 handleAbortMessage()，再进入现有 SemanticCenter / Intent / Chat 链路
```

裁决请求只携带少量上下文：

```text
assistant_state
recent_dialogue 最近最多 4 条 user/assistant 文本
overlap_user_text 当前重叠语音文本
```

服务端不维护附和词表、停止词表或长度阈值来替代判断；只负责请求 LLM、解析 JSON 结果和执行结果。LLM 裁决失败或返回非法 JSON 时，为避免真实打断请求被吞掉，保守回退为 `new_turn` 并走旧打断流程。

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

### 停止类

这些说法会直接路由到 `interrupt.stop_all`：

```text
停
停止
暂停
别动
别说了
不要说了
闭嘴
停止表演
你先停一下
小智你先别演了
```

处理方式：

```text
SemanticRouter -> interrupt.stop_all
PolicyEngine -> execute
SemanticCenter -> handleAbortMessage()
              -> 尝试调用配置或默认动作停止工具
              -> TTS 回复“好的，已停止。”
              -> 状态回到 idle
```

当前硬停止复用已有 `handleAbortMessage()`，它会设置 `client_abort`、增加 `abort_generation`、清理 TTS 队列并向设备发送 `tts stop`。动作停止第一阶段只做候选工具尝试；具体机器人动作 MCP 名称应通过 `semantic_control.stop_motion_tools` 配置补齐。

### 游戏上下文

成语接龙、你说我猜、小智想词用户猜等纯语言游戏不再进入语义控制中枢的状态机工具路径。用户输入应继续回到主 LLM 对话链路，由模型自然维护玩法上下文。

用户说：

```text
退出游戏
结束游戏
不玩了
游戏结束
```

会触发：

```text
game.exit
  -> GameSessionManager.clear_active_games()
  -> TTS 回复“好的，游戏先到这。”
  -> 不关闭 websocket
```

这和旧的 `exit_commands` 不同。旧退出命令仍用于关闭连接。

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

1. 配置化 `SkillRegistry`：把高频技能的 aliases、allowed_states、executor、priority 从代码抽到 YAML/JSON 或后台配置。
2. 完善 `RobotContext`：加入动作运行、TTS 播放、当前讲解段落、电量、网络、传感器安全状态。
3. 扩展 `PolicyEngine`：加入动作安全、电量、地面安全、二次确认、低置信度确认。
4. 把 RAGFlow/local knowledge 提升为知识类 skill，但保持旧插件名兼容。
5. 增加主动服务规则：迎宾、讲解结束推荐、游戏超时提示、长时间无响应提示。
6. 在日志中记录 `ASRResult -> IntentFrame -> Decision -> Executor -> Result`，用于回归测试和拒识优化。

## 8. 验证

当前新增测试：

```text
main/xiaozhi-server/tests/test_semantic_router.py
main/xiaozhi-server/tests/test_semantic_policy.py
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

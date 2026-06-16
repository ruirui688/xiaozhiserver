# 云端运行架构与配置链路

本文档整理 `xiaozhi-esp32-server` 云端服务器的运行边界。重点说明：后台配置如何进入 Python 实时服务，设备什么时候看到这些配置，提示词、插件、MCP 和状态机能力分别在哪里生效。

原始调研材料来自 `/root/小智云端服务器分析报告.docx`，本文已按当前仓库代码路径重新校正，作为后续开发和排查的正式工程文档。

## 1. 核心结论

本项目不是一个单纯的后台管理系统，而是一套端云协同的语音智能体运行平台。

生产主链路由三部分组成：

```text
manager-web / manager-mobile
  -> 配置入口

manager-api
  -> 配置事实来源、设备绑定、模型编排、OTA、插件、MCP 接入点

xiaozhi-server
  -> 设备实时连接、ASR、LLM、TTS、VAD、Memory、Intent、工具调用、MCP 调度
```

设备最终表现由三件事共同决定：

```text
设备身份和绑定关系
Java 后台下发给 Python 的智能体运行配置
Python 每轮对话组装出来的 messages、tools 和会话状态
```

前端保存成功不等于设备立即变化。配置真正生效通常需要 Python 重新读取配置、设备重新连接，或相关缓存被刷新。

## 2. 顶层模块边界

`main/` 下五个模块职责如下：

| 模块 | 职责 | 是否参与实时对话 |
| --- | --- | --- |
| `main/xiaozhi-server` | Python 核心实时服务，负责设备 WebSocket/MQTT 接入、Opus 音频收发、VAD、ASR、LLM、TTS、Memory、Intent、插件、IoT、MCP 工具调度和 OTA 地址处理 | 直接参与 |
| `main/manager-api` | Java Spring Boot 管理后台，保存配置、设备绑定、模型编排、OTA、MCP 接入点参数，并向 Python 下发运行配置 | 间接参与 |
| `main/manager-web` | Vue Web 智控台，配置智能体、模型、音色、提示词、插件、知识库、OTA、MCP 入口显示 | 不直接参与 |
| `main/manager-mobile` | 移动端智控台，复用 `manager-api` 能力，提供移动端设备、智能体、工具管理 | 不直接参与 |
| `main/digital-human` | 数字人和音频交互测试模块，用于验证数字人、音频、唤醒词和页面联调 | 不参与 ESP32 生产实时链路 |

`digital-human` 是辅助测试线，不能用它判断 ESP32 生产设备的 MCP、提示词或工具调用是否已经进入 Python 主链路。

## 3. 配置变成设备行为

智能体配置从后台到设备的主路径：

```text
manager-web / manager-mobile
  -> manager-api
  -> MySQL / Redis / 参数字典
  -> 设备启动请求 OTA
  -> 设备连接 xiaozhi-server
  -> xiaozhi-server 按设备 MAC 拉取私有配置
  -> 初始化 VAD / ASR / LLM / TTS / Memory / Intent / Plugins / MCP
  -> 每轮语音对话动态组装 LLM 请求
```

关键源码：

| 环节 | 位置 | 说明 |
| --- | --- | --- |
| 前端角色配置 | `main/manager-web/src/views/roleConfig.vue` | 编辑智能体、模型、提示词、功能 |
| 智能体实体 | `main/manager-api/src/main/java/xiaozhi/modules/agent/entity/AgentEntity.java` | 保存系统提示词、模型选择等字段 |
| Java 构造设备私有配置 | `main/manager-api/src/main/java/xiaozhi/modules/config/service/impl/ConfigServiceImpl.java` | 组合模型、插件、MCP、上下文源、声纹和提示词 |
| Python 连接会话 | `main/xiaozhi-server/core/connection.py` | 拉取配置、初始化组件、处理实时对话 |

`ConfigServiceImpl` 会把以下内容合并到返回给 Python 的配置中：

```text
selected_module
VAD / ASR / LLM / TTS / Memory / Intent / VLLM / SLM / RAG 配置
prompt
summaryMemory
plugins
mcp_endpoint
context_providers
voiceprint
TTS 私有音色、语速、音量、语言
```

## 4. 实时语音链路

设备和云端的运行链路分三层。

第一层是设备端音频链路：

```text
设备空闲
  -> 本地唤醒词 / AFE / 端侧状态判断
  -> 进入监听窗口
  -> 上传 Opus 音频帧到 xiaozhi-server
```

第二层是 Python 实时链路：

```text
WebSocket / MQTT 收到音频
  -> VAD 判断语音段和结束点
  -> ASR 转文字
  -> 语义控制中枢处理高频控制、状态上下文和拒识
  -> 未处理时进入 Intent 或 function_call 判断工具
  -> LLM 生成或工具执行
  -> TTS 合成
  -> 音频下发设备播放
```

第三层是模型请求组装：

```text
system prompt
conversation history
memory
voiceprint speakers
dynamic context
tools schema
tool results
```

模型不是只收到用户一句话，而是收到服务端组装后的完整请求。

## 5. 智能体能力组成

一个智能体通常不是只绑定一个 LLM，而是由多类模块组合：

| 模块 | 作用 |
| --- | --- |
| ASR | 语音转文字 |
| LLM | 主对话生成 |
| TTS | 文字转语音 |
| VAD | 判断语音开始和结束 |
| VLLM | 视觉理解 |
| Memory | 记忆摘要和历史偏好 |
| Intent | 工具调用意图识别 |
| SLM | 小模型辅助 |
| RAG | 知识库检索 |
| Plugins | 内置业务工具和互动工具 |
| MCP | 外部工具、服务器侧工具或设备能力扩展 |

Java 后台负责选择和保存这些模块，Python 在设备连接时按配置初始化。

## 6. 提示词与上下文入口

进入实时对话主 LLM 的内容主要包括：

| 内容 | 形式 | 来源 |
| --- | --- | --- |
| 智能体角色提示词 | system message 身份部分 | Java 下发的 `prompt` |
| 全局基础模板 | system message 规则部分 | `main/xiaozhi-server/agent-base-prompt.txt` |
| 动态上下文 | system message 中的上下文段 | 时间、日期、农历、位置、天气、上下文源 |
| 记忆 | `<memory>` 段落 | `summaryMemory` / Python Memory |
| 声纹说话人信息 | `<speakers_info>` 段落 | `voiceprint.speakers` |
| 插件和 IoT 工具 schema | OpenAI tools 或文本工具提示 | 内置插件函数描述、设备 IoT descriptors |
| 服务器侧 MCP 工具 schema | OpenAI tools 或文本工具提示 | MCP `tools/list` |
| 设备端 MCP 工具 schema | OpenAI tools 或文本工具提示 | 设备 MCP `tools/list` |

不直接进入主对话、但仍属于提示词工程的内容：

| 内容 | 位置 | 用途 |
| --- | --- | --- |
| `intent_llm` 意图识别提示词 | `core/providers/intent/intent_llm/intent_llm.py` | 要求意图模型只返回 JSON |
| 非原生工具调用提示词 | `core/providers/llm/system_prompt.py` | 给 Dify / Coze 等非原生 function call 模型使用 |
| 结束语提示词 | `config.yaml` / `data/.config.yaml` 的 `end_prompt` | 会话结束前生成告别文案 |
| Java 记忆总结提示词 | `OpenAIStyleLLMServiceImpl.DEFAULT_SUMMARY_PROMPT` | 后台总结聊天记录 |
| Java 会话标题提示词 | `OpenAIStyleLLMServiceImpl.DEFAULT_TITLE_PROMPT` | 后台生成会话标题 |
| `change_role` 内置角色提示词 | `plugins_func/functions/change_role.py` | 当前会话动态切换角色 |
| 设备端 MCP prompt | 设备固件 MCP 工具定义 | 覆盖设备工具的 description |

完整注入链路见 [提示词注入工程](../integrations/prompt-injection-engineering.md)。

## 7. 工具调用模式

工具调用不是“Python 看到工具就自动调用”。旧主链路中 Python 负责准备工具 schema，是否调用由模型或意图模块决定。新增语义控制中枢后，少数高频控制类指令可以由 Python Router / Policy 直接调用工具或中断 TTS，不再等待 LLM 选择。

当前主要有三种模式：

| 模式 | 谁判断 | 模型看到什么 | 返回什么 | 适合场景 |
| --- | --- | --- | --- | --- |
| 语义控制中枢 | Python Router / StateManager / PolicyEngine | 不依赖模型工具提示 | `Decision`：执行、拒识、反问或旧流程兜底 | 停止、退出游戏、状态机续局、低置信度拒识 |
| `function_call` | 主 LLM | 对话 `messages` + 完整 `tools` schema | 原生 `tool_calls` | 主模型支持 function calling，速度更直接 |
| `intent_llm` | 独立意图 LLM | 函数名、描述、参数拼成的意图提示词 | JSON：`continue_chat` / `result_for_context` / `function_call` | 主模型工具调用不稳定，或希望先用小模型做意图 |

排查工具不触发时先看三件事：

```text
目标工具是否出现在 Python 日志的“当前支持的函数列表”
模型请求是否携带 tools schema 或 intent prompt
模型是否真的返回 tool_calls / function_call JSON
```

如果工具已经在列表里但模型不调用，通常不是“接入失败”，而是工具描述、用户表达、全局提示词约束或模型 function calling 能力的问题。

## 8. 工具来源

运行时工具来源分五类：

| 工具来源 | 示例 | 谁配置或发现 | 谁执行 |
| --- | --- | --- | --- |
| 服务端内置插件 | `get_weather`、`play_music`、`get_news_from_newsnow`、`handle_exit_intent` | 后台功能配置或 Python 配置 | Python |
| 服务端 MCP | `.mcp_server_settings.json` 中的 MCP 服务 | Python 配置 | Python MCP client |
| 外部 MCP Endpoint | 智能体 MCP 接入点 | Java 系统参数 + 智能体配置 | Python 连接 Endpoint |
| 设备端 IoT | 设备上报的属性、方法 | 设备 hello / descriptor | Python 发设备控制消息 |
| 设备端 MCP | `self.get_device_status`、`self.screen.set_brightness` | 设备声明 `features.mcp=true` 后 `tools/list` | Python 发 `tools/call` 给设备 |

统一工具入口：

```text
main/xiaozhi-server/core/providers/tools/unified_tool_handler.py
  UnifiedToolHandler
```

工具执行器：

```text
server_plugins   服务端插件
server_mcp       服务器侧 MCP
mcp_endpoint     外部 MCP Endpoint
device_iot       设备 IoT
device_mcp       设备端 MCP
```

## 9. MCP 三类链路

MCP 在本项目中至少有三条链路，必须分开理解。

| MCP 类型 | 工具提示词来自哪里 | 工具功能通常是什么 | 谁执行 |
| --- | --- | --- | --- |
| 外部 MCP Endpoint | 接入点服务返回的 `tools/list.description/inputSchema` | 给某个智能体接入外部业务工具、平台工具 | Python 连接 Endpoint 后调用 |
| 服务器侧 MCP | Python 本地配置的 MCP server 返回的 `tools/list.description/inputSchema` | 服务器能访问的工具，例如音乐、搜索、文件、业务系统 | Python 作为 MCP client 调用 |
| 设备端 MCP | 设备固件返回的 `tools/list.description/inputSchema` | 设备自身硬件能力，例如状态、音量、屏幕、触摸、拍照 | Python 发 `tools/call` 给设备 |

三类 MCP 的共同点：

```text
模型看到的不是一段单独叫 mcp_prompt 的文本，
而是每个工具的 description 和 inputSchema。
```

区别在于工具是谁提供、谁执行。

## 10. 外部 MCP Endpoint

这是后台页面里“MCP 接入点”主要指的东西。

全模块部署时：

```text
参数字典 / 系统功能配置
  -> 勾选 MCP 接入点显示入口

参数管理
  -> 配置 server.mcp_endpoint

智能体管理 / 配置角色 / 编辑功能
  -> 查看该智能体自己的 MCP 接入点地址和工具列表

manager-api
  -> 把智能体地址从 /mcp/ 转成 /call/
  -> 下发给 Python 的 mcp_endpoint

xiaozhi-server
  -> 连接 Endpoint
  -> initialize
  -> notifications/initialized
  -> tools/list
```

单模块 Python 部署时，不走后台参数管理，通常在 `data/.config.yaml` 或当前配置文件中写 MCP Endpoint 配置。

## 11. 服务器侧 MCP

服务器侧 MCP 是“Python 服务所在服务器可以访问的工具服务”，不是设备提供的能力。

配置位置：

```text
main/xiaozhi-server/data/.mcp_server_settings.json
main/xiaozhi-server/mcp_server_settings.json
```

典型能力：

```text
搜索音乐
播放 / 暂停 / 恢复 / 停止
设置音量
切歌
查询业务系统
联网搜索
```

Python 会把 MCP 服务返回的 `tools/list` 转换成模型侧工具 schema，加入“当前支持函数列表”。只有模型选择对应工具时，Python 才调用 MCP。

## 12. 设备端 MCP

设备端 MCP 是“设备固件把自己的硬件能力暴露给服务器”。

触发条件：

```text
设备 hello 声明 features.mcp = true
Python 创建设备 MCP 客户端
Python 向设备发 initialize / tools/list
设备返回工具 name / description / inputSchema
```

设备端 MCP 能力通常来自固件里的工具注册，例如 `McpServer::AddTool(...)`。工具 description 也可以被设备端调试配置覆盖。

设备端 MCP 不是自动事件上报。比如设备暴露了 `self.get_head_status`，不代表被触摸时模型会自动知道；只有模型返回对应工具调用，Python 才会向设备发 `tools/call`。

## 13. 天气插件与 MCP 的区别

内置天气插件和 MCP 是两套能力。

| 维度 | 内置天气插件 `get_weather` | MCP |
| --- | --- | --- |
| 来源 | Python 内置插件函数 | 外部 MCP 服务、服务器侧 MCP 或设备端 MCP |
| 配置位置 | 智能体功能配置 / `plugins.get_weather` | `server.mcp_endpoint`、`.mcp_server_settings.json`、设备 `features.mcp` |
| 模型看到的提示 | `GET_WEATHER_FUNCTION_DESC` | MCP `tools/list` 的 description/inputSchema |
| 是否自动调用 | 否，由模型判断 | 否，由模型判断 |
| 当前本地天气 | 基础提示词可能要求直接用上下文 | 与 MCP 无关 |
| 适合场景 | 查询城市天气 | 扩展任意工具、外部系统、设备能力 |

如果用户问本地天气，而系统上下文已经提供本地天气，模型可能直接回答而不调用 `get_weather`。如果用户明确说其他城市，工具描述和基础提示词应要求调用天气工具。

## 14. OTA 与设备连接入口

OTA 不只是固件升级，也是设备获取云端连接地址的入口。

设备启动后：

```text
请求 OTA 地址
  -> 服务器返回固件信息
  -> 返回 WebSocket 或 MQTT 配置
  -> 设备连接 Python 实时服务
```

常见部署入口：

| 部署方式 | OTA 地址 | WebSocket 地址 |
| --- | --- | --- |
| 全模块部署 | `http://服务器IP:8002/xiaozhi/ota/` | `ws://服务器IP:8000/xiaozhi/v1/` |
| 单模块 Python | `http://服务器IP:8003/xiaozhi/ota/` | Python OTA 返回 |
| HTTPS/WSS | `https://域名/xiaozhi/ota/` | `wss://域名/xiaozhi/v1/` |

设备端读取 OTA 地址有优先级：NVS 中的 `wifi.ota_url` 优先于编译时 `CONFIG_OTA_URL`。如果设备曾经在配网页面写过旧 OTA 地址，重新编译也可能仍然连接旧服务器，需要清 NVS 或重新配置。

## 15. 单模块与全模块部署

| 部署方式 | 组成 | 适合场景 | 局限 |
| --- | --- | --- | --- |
| 单模块 Python | 只运行 `xiaozhi-server` | 本地开发、单设备验证、模型调试 | 后台管理弱，配置靠文件，不适合批量 |
| 全模块部署 | Python + Java + Vue + MySQL + Redis | 产品化、多设备、多智能体、OTA 管理 | 部署复杂，配置链路更长 |

如果目标是正式硬件产品，应长期按全模块部署理解和改造。

## 16. 排查顺序

遇到“后台改了但设备没变化”：

```text
1. 确认设备绑定的智能体是否正确。
2. 确认 manager-api 返回给 Python 的私有配置里是否有目标字段。
3. 确认 xiaozhi-server 是否重新读取配置或设备是否重连。
4. 确认 Python 日志里 selected_module、plugins、mcp_endpoint 是否符合预期。
5. 如果是工具问题，确认“当前支持的函数列表”是否包含目标工具。
6. 如果工具在列表里但没触发，检查 function_call / intent_llm 模式和模型返回。
7. 如果是提示词问题，检查 system prompt、tools schema、intent prompt 是否进入请求。
```

遇到“插件/MCP 看不到提示词”：

```text
1. 不要只查 agent-base-prompt.txt。
2. 查工具 description 和 inputSchema。
3. 查 UnifiedToolHandler.get_functions() 返回值。
4. 查 OpenAI 请求的 tools 字段。
5. 如果是 intent_llm，查 get_intent_system_prompt() 展开的函数说明。
6. 如果是状态机游戏，查 GameSessionManager，而不是后台大段 prompt。
```

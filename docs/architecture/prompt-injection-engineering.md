---
status: supporting
owner: runtime
last_verified: 2026-07-14
source_paths:
  - main/xiaozhi-server/core/utils/prompt_manager.py
  - main/xiaozhi-server/core/utils/dialogue.py
  - main/xiaozhi-server/core/providers/tools
---

# 提示词注入工程

本文档说明 xiaozhi-server 中“大模型能看到什么提示词、工具描述和上下文”，以及这些内容从后台配置到 Python 运行时再到模型请求的完整链路。

先读整体架构：[云端运行架构与配置链路](../architecture/cloud-runtime-architecture.md)。

## 1. 总体模型

运行时进入模型的内容不是一段单独的 prompt，而是多类对象组合：

```text
系统提示词
  角色人设、全局语音交互规则、动态上下文、天气、时间、上下文源

对话历史
  user / assistant / tool 历史消息

记忆和说话人信息
  summaryMemory、Memory 查询结果、voiceprint speakers

工具描述
  OpenAI tools / function call schema，或非原生模型的文本工具提示

工具执行结果
  ActionResponse.result / response，可能直接播报，也可能再次交给 LLM

服务端状态
  游戏隐藏答案、上一问、候选集、已排除条件、已用成语
```

不是所有“规则”都应该写进自然语言 prompt。高频、多轮、必须稳定记忆的玩法已经下沉到服务端状态机，模型只负责触发工具调用。

## 2. 从后台到 Python 的配置来源

后台保存的智能体配置由 `manager-api` 下发给 `xiaozhi-server`。

关键位置：

```text
main/manager-api/src/main/java/xiaozhi/modules/config/service/impl/ConfigServiceImpl.java
```

核心下发字段：

| 字段 | 进入 Python 后的作用 |
| --- | --- |
| `prompt` | 智能体角色提示词，进入 system prompt |
| `summaryMemory` | 记忆摘要，进入 `<memory>` 或记忆上下文 |
| `selected_module` | 指定当前 ASR、LLM、TTS、Memory、Intent 等模型 |
| `LLM` / `TTS` / `ASR` / `VAD` / `Memory` / `Intent` | 各模块运行配置 |
| `plugins` | 当前智能体启用的插件参数、工具描述覆盖、声明式插件规则 |
| `mcp_endpoint` | 外部 MCP Endpoint 地址 |
| `context_providers` | 动态上下文源 |
| `voiceprint` | 声纹说话人信息 |

Python 会在连接初始化时把这些配置放进当前 `ConnectionHandler.config`。

相关位置：

```text
main/xiaozhi-server/core/connection.py
  ConnectionHandler
```

## 3. 注入对象清单

| 类型 | 模型是否可见 | 注入形式 | 主要来源 |
| --- | --- | --- | --- |
| 智能体角色提示词 | 可见 | system message | Java 下发的 `prompt` |
| 全局基础模板 | 可见 | system message | `agent-base-prompt.txt` |
| 动态上下文 | 可见 | system message 上下文段 | 时间、日期、农历、位置、天气、上下文源 |
| 记忆 | 可见 | messages 中的记忆段 | `summaryMemory` / Python Memory |
| 声纹说话人 | 可见 | messages 中的 speakers 段 | `voiceprint.speakers` |
| 服务端插件工具描述 | 可见 | `tools` schema 或 intent prompt | `plugins_func` 注册表、后台插件配置 |
| 声明式插件玩法说明 | 部分可见 | 工具结果二次交给 LLM | 后台 `instructions` / `opening_message` |
| MCP 工具描述 | 可见 | `tools` schema 或 intent prompt | MCP `tools/list` |
| 设备 IoT 工具描述 | 可见 | `tools` schema 或 intent prompt | 设备 descriptors |
| 状态机游戏状态 | 不直接可见 | 服务端内存状态 | `GameSessionManager` |
| TTS 参考文本 prompt | 不给主 LLM | TTS provider 参数 | TTS 配置 |
| Java 总结/标题 prompt | 不给主 LLM | 后台 LLM 请求 | `OpenAIStyleLLMServiceImpl` |

## 4. 主系统提示词注入

入口：

```text
main/xiaozhi-server/core/connection.py
  _init_prompt_enhancement()
```

链路：

```text
ConnectionHandler.config["prompt"]
  -> PromptManager.build_enhanced_prompt(...)
  -> ConnectionHandler.change_system_prompt(...)
  -> Dialogue.update_system_message(...)
  -> LLM 请求 messages
```

相关文件：

```text
main/xiaozhi-server/agent-base-prompt.txt
main/xiaozhi-server/core/utils/prompt_manager.py
main/xiaozhi-server/core/utils/dialogue.py
main/xiaozhi-server/core/connection.py
```

`PromptManager.build_enhanced_prompt()` 会把 `agent-base-prompt.txt` 作为 Jinja 模板渲染，并注入：

```text
base_prompt        Java 下发的角色提示词
today_date         当前日期
today_weekday      当前星期
lunar_date         农历
local_address      根据 client_ip 推断的位置
weather_info       本地天气缓存
dynamic_context    上下文源返回内容
language           TTS 语言
device_id
client_ip
emojiList
```

`change_system_prompt()` 最终把增强后的 prompt 写入 `dialogue` 的 system message。

## 5. 对话历史、记忆和声纹注入

主 LLM 请求前会从 `Dialogue` 取对话历史：

```text
main/xiaozhi-server/core/connection.py
  chat()
    -> self.dialogue.get_llm_dialogue_with_memory(memory_str, voiceprint_config)
```

记忆来源：

```text
if self.memory is not None and query:
  self.memory.query_memory(query)
```

声纹来源：

```text
self.config.get("voiceprint", {})
```

`Dialogue.get_llm_dialogue_with_memory()` 会在每个成功识别轮次把当前身份写入动态 system 上下文，同时当前 user message 保留结构化 `speaker` / `speaker_id`。system 只包含当前匹配说话人的描述，不暴露其他已绑定人员；未知说话人不会获得人员列表。身份事实每轮可用，避免依赖可能被截断或被模型忽略的旧对话历史；“不要机械地重复称呼姓名、不要主动播报声纹识别结果”由明确提示词约束，而不是通过丢弃本轮身份实现。

## 6. 工具描述统一出口

所有工具描述的统一出口：

```text
main/xiaozhi-server/core/providers/tools/unified_tool_handler.py
  UnifiedToolHandler.get_functions()
```

内部链路：

```text
UnifiedToolHandler.get_functions()
  -> ToolManager.get_function_descriptions()
  -> ToolManager.get_all_tools()
  -> 各 ToolExecutor.get_tools()
  -> ToolDefinition.description
```

执行器分层：

| 执行器 | 来源 |
| --- | --- |
| `ServerPluginExecutor` | 服务端插件、声明式插件、状态机游戏 |
| `ServerMCPExecutor` | 服务器侧 MCP |
| `MCPEndpointExecutor` | 外部 MCP Endpoint |
| `DeviceIoTExecutor` | 设备 IoT descriptors |
| `DeviceMCPExecutor` | 设备端 MCP tools/list |

所以，后台看不到某些“大段提示词”并不代表没有注入。很多能力以工具 description、parameters、inputSchema 的形式进入模型。

## 7. 服务端插件工具描述

服务端插件由这里生成工具描述：

```text
main/xiaozhi-server/core/providers/tools/server_plugins/plugin_executor.py
  ServerPluginExecutor.get_tools()
```

工具来源：

```text
必要内置函数
  handle_exit_intent
  get_lunar

后台 Intent 配置 functions
  config["Intent"][selected]["functions"]

自由对话游戏旧 code
  guess_what_i_say / idiom_chain / xiaozhi_thinks_you_guess / guess_xiaozhi_word / i_think_you_guess
  -> 当前由 ServerPluginExecutor 跳过，不注入为 tools

后台声明式互动插件
  config["plugins"][provider_code]
```

内置插件如果命中 `all_function_registry`，优先使用插件 Python 函数注册的 description。后台 `plugins[func_name].description` 可以覆盖工具描述。

`play_music` 还有额外的曲库上下文注入。`ConnectionHandler.chat()` 会在当前智能体具备 `play_music` 工具时，向本轮 LLM 请求插入 `<music_library>` system 段，内容来自 `plugins_func/functions/play_music.py` 扫描到的本地音乐文件、`aliases` 和 `description`。`ServerPluginExecutor._init_music_description()` 也会把曲库摘要补进工具描述。

音乐相关提示词边界：

```text
用户问“有什么歌、你会放什么歌、曲库里有什么”
  -> LLM 根据 <music_library> 直接回答
  -> 不调用 play_music

用户明确要求播放、唱、放某首歌或随机播放音乐
  -> LLM / intent_llm 调用 play_music
  -> 服务端执行真实本地文件播放
```

`play_music` 的工具结果使用 `Action.RECORD` 写入工具调用审计，不再请求 LLM 二次改写。真实出声由 TTS 队列完成：先以普通 `ContentType.TEXT` 播放“即将为您播放 具体歌曲”等选曲提示语，再以 `ContentType.FILE` 播放本地音乐文件，并用 `LAST/ACTION` 结束边界触发流式 provider flush。聊天记录中的 `play_music({...})` 和 `{"result": "正在为您播放音乐"}` 不能单独作为播放成功或失败依据。

纯语言游戏不再由 `ServerPluginExecutor` 构造工具描述。用户发起成语接龙、你说我猜等玩法时，主 LLM 直接根据对话上下文处理。

## 8. 主聊天 function_call 注入

当 `Intent` 类型是 `function_call` 时，主聊天模型直接接收工具 schema。

入口：

```text
main/xiaozhi-server/core/connection.py
  chat()
```

关键链路：

```text
functions = list(self.func_handler.get_functions())
if depth == 0:
  functions.append(DIRECT_ANSWER_TOOL)

self.llm.response_with_functions(
  session_id,
  self.dialogue.get_llm_dialogue_with_memory(...),
  functions=functions
)
```

OpenAI 兼容 provider：

```text
main/xiaozhi-server/core/providers/llm/openai/openai.py
  response_with_functions()
```

请求字段：

```python
request_params = {
    "model": self.model_name,
    "messages": dialogue,
    "stream": True,
    "tools": functions,
}
```

结论：主聊天模型看到的插件提示词通常不拼在 system prompt 里，而是作为 `tools` 字段进入模型请求。

基础 system prompt 不再无条件宣称 `web_search`、`get_weather`、`play_music` 等可选工具存在。`PromptManager` 从 `UnifiedToolHandler.get_functions()` 返回的同一份真实 schema 提取工具名，写入 `<available_tools>` 动态索引；每轮请求前会再次同步该索引，以覆盖 MCP、设备 IoT 或设备 MCP 在连接建立后动态注册工具的情况。工具的用途、参数和限制仍以原生 `tools` schema 为唯一事实来源，动态索引只用于明确能力边界，未注册工具不得出现在该索引中。

如果模型仍因历史上下文或 provider 异常返回未注册工具调用，`UnifiedToolHandler` 会先通过当前 `ToolManager` 注册表拒绝，再决定是否向设备展示工具状态；因此设备不会先看到 `% web_search` 之类并不存在的工具正在执行。

## 9. intent_llm 注入

当使用 `intent_llm` 时，工具描述会被拼进意图识别系统提示词。

入口：

```text
main/xiaozhi-server/core/providers/intent/intent_llm/intent_llm.py
  detect_intent()
```

链路：

```text
functions = conn.func_handler.get_functions()
if conn.mcp_client:
  functions.extend(conn.mcp_client.get_available_tools())

prompt = get_intent_system_prompt(functions)
prompt += <musicNames>...</musicNames>
prompt += HomeAssistant devices

llm.response_no_stream(system_prompt=prompt, user_prompt=current_dialogue)
```

`get_intent_system_prompt()` 会把每个函数的以下字段展开成中文说明：

```text
name
description
parameters.properties
```

要求意图模型只返回 JSON，例如：

```json
{"function_call": {"name": "idiom_chain", "arguments": {"user_message": "成语接龙"}}}
```

活跃游戏会绕过意图缓存和普通意图判断：

```text
main/xiaozhi-server/core/handle/intentHandler.py
  handle_user_intent()

GameSessionManager.get_active_tool()
  -> 直接构造当前游戏工具调用
```

这是为了避免“对的”“不是”“猜不到”被误判为普通聊天，或被旧意图缓存污染。

## 10. 非原生 function call 模型

部分 provider 不支持原生 OpenAI `tools` 字段，会把工具 schema 改写进文本提示词。

位置：

```text
main/xiaozhi-server/core/providers/llm/system_prompt.py
  get_system_prompt_for_function()

main/xiaozhi-server/core/providers/llm/dify/dify.py
main/xiaozhi-server/core/providers/llm/coze/coze.py
```

这类模型看到的是文本形式的工具调用协议，例如要求输出 `<tool_call>`。排查时不能只看 OpenAI `tools` 字段。

## 11. 声明式互动插件注入

声明式互动插件来自后台配置：

```text
ai_model_provider.model_type = Plugin
ai_agent_plugin_mapping.param_info
```

运行时进入：

```text
conn.config["plugins"][provider_code]
```

工具描述构造：

```text
ServerPluginExecutor._build_declarative_interaction_tool()
```

它会把后台字段变成工具 schema：

```text
description
instructions
opening_message
parameters_schema
custom_plugin
```

执行链路：

```text
LLM 调用工具
  -> _execute_declarative_interaction_plugin()
  -> 返回 Action.REQLLM
  -> 把 <interaction_plugin>...</interaction_plugin> 写入工具结果
  -> 再调用一次 LLM 生成自然回复
```

声明式插件有两处提示词：

| 阶段 | 内容 | 作用 |
| --- | --- | --- |
| 工具描述 | description + parameters_schema | 让模型决定是否调用工具 |
| 二次提示 | `<interaction_plugin>` 中的玩法说明 | 工具调用后让模型生成主持话术 |

这类模式灵活，但延迟高，也容易丢状态。当前纯语言游戏直接交给主 LLM 对话；如果当前智能体在功能插件里启用了对应游戏，会把该插件可编辑的 `instructions` / `opening_message` 作为本轮软提示词注入主 LLM，但不会注册为工具，也不会走二次 LLM 工具链。

## 12. 自由对话游戏边界

以下旧游戏 code 不再强制注入为工具：

```text
guess_what_i_say          用户描述，小智猜
idiom_chain               成语接龙
xiaozhi_thinks_you_guess  小智想词，用户猜
guess_xiaozhi_word
i_think_you_guess
```

当前实现：

```text
main/xiaozhi-server/core/game/ (6 files, 1513 lines, re-exported from game_session.py)
  GameSessionManager (in base.py) - class constants + shared helpers
  GuessMyWordGame (in guess_my_word.py) - server picks word, user guesses
  UserDescribesGame (in user_describes.py) - user describes, server guesses
  IdiomChainGame (in idiom_chain.py) - idiom chain game
  FreeformGameManager (in freeform.py) - LLM-driven freeform games
  GameSessionManager.FREEFORM_GAME_TOOLS
  GameSessionManager.build_freeform_game_prompt()
  GameSessionManager.GUESS_MY_WORD_TOOLS = set()
  GameSessionManager.USER_DESCRIBES_TOOLS = set()
  GameSessionManager.IDIOM_CHAIN_TOOLS = set()
  GameSessionManager.get_requested_game_tool() -> None

main/xiaozhi-server/core/connection.py
  ConnectionHandler._get_enabled_plugin_configs()
  ConnectionHandler._with_freeform_game_prompt()
```

工具注册：

```text
ServerPluginExecutor.get_tools()
  -> 跳过 GameSessionManager.FREEFORM_GAME_TOOLS
```

运行规则：

```text
不提前路由为 function_call。
不注入工具 description。
不使用服务端候选库、关键词判定、固定问答或计分状态。
如果当前智能体启用了对应功能插件，从插件配置读取 `instructions` / `opening_message`，作为临时 system 软提示注入本轮主 LLM 请求。
由主 LLM 根据完整对话上下文和可编辑游戏规则自然主持。
```

这解释了为什么这些游戏现在不应该出现在“当前支持的函数列表”里：它们保留功能插件配置入口，但不再作为工具执行。

## 14. MCP 工具提示词注入

MCP 工具的“提示词”不是统一写在 system prompt 里的 `mcp_prompt`，而是 MCP 工具本身的：

```text
name
description
inputSchema
```

三类 MCP：

| 类型 | 注入来源 | 进入模型方式 |
| --- | --- | --- |
| 外部 MCP Endpoint | Endpoint 返回的 `tools/list` | `MCPEndpointExecutor.get_tools()` -> tools schema |
| 服务器侧 MCP | 本地 MCP server 返回的 `tools/list` | `ServerMCPExecutor.get_tools()` -> tools schema |
| 设备端 MCP | 设备返回的 `tools/list` | `DeviceMCPExecutor.get_tools()` -> tools schema |

设备端 MCP 工具名通常会从点号格式转换成模型侧函数名，例如：

```text
self.get_device_status        -> self_get_device_status
self.screen.set_brightness    -> self_screen_set_brightness
self.audio_speaker.set_volume -> self_audio_speaker_set_volume
```

只有模型选择对应工具时，Python 才会调用 MCP。MCP 暴露工具不等于自动执行。

## 15. 天气提示词与工具调用边界

天气能力有两条来源：

```text
动态上下文天气
  -> PromptManager 注入 weather_info
  -> 适合回答设备所在地、本地未来天气

get_weather 插件
  -> 服务端插件工具
  -> 适合查询用户明确指定的城市或区县
```

如果基础提示词强调“本地天气已在上下文”，模型可能不会调用 `get_weather`。如果用户说“南京天气”“杭州温度”，工具描述和基础提示词必须让模型调用天气工具，而不是把本地上下文当成外地天气。

天气接口异常时，工具本身应该返回可靠的直接回复，避免把 `Authentication failed`、`Invalid Host` 等错误交给 LLM 改写成“城市不存在”。

## 16. 工具结果进入 LLM 或 TTS

工具执行返回 `ActionResponse` 后，分两类：

```text
Action.RESPONSE
  -> 直接作为回复进入 TTS
  -> 低延迟
  -> 适合天气结果、设备控制确认

Action.REQLLM
  -> 工具结果写入上下文
  -> 再请求 LLM 生成自然语言
  -> 延迟更高
  -> 适合搜索结果总结、声明式互动插件、复杂工具结果解释
```

处理位置：

```text
main/xiaozhi-server/core/connection.py
  _handle_tool_results()

main/xiaozhi-server/core/handle/intentHandler.py
  process_intent_result()
```

纯语言游戏不应走 `Action.RESPONSE` 工具链；否则服务端硬规则会压过主模型能力。

## 17. 排查方法

查看当前连接支持哪些工具：

```bash
scripts/dev logs server -f
```

搜索关键日志：

```text
当前支持的函数列表
执行工具: <tool_name>
llm 识别到意图
```

如果看到 `guess_what_i_say`、`idiom_chain`、`xiaozhi_thinks_you_guess` 仍进入工具列表或执行工具日志，说明旧配置或代码路径绕过了 `FREEFORM_GAME_TOOLS` 跳过逻辑。

查看工具描述生成：

```bash
rg -n "get_tools|_build_.*tool|_execute_stateful_game_plugin|_execute_declarative_interaction_plugin" \
  main/xiaozhi-server/core/providers/tools
```

查看主 system prompt：

```bash
rg -n "build_enhanced_prompt|change_system_prompt|update_system_message" \
  main/xiaozhi-server/core
```

查看是否二次请求 LLM：

```text
Action.RESPONSE  -> 直接回复
Action.REQLLM    -> 工具结果再交给 LLM
```

## 18. 修改规则

修改提示词或插件时遵守：

```text
角色、人设、语音交互总规则写入 agent-base-prompt.txt 或后台 prompt。
影响“是否调用工具”的内容写入工具 description。
工具参数规则写入 parameters_schema / inputSchema。
必须访问服务端状态、设备或外部接口的能力才写入工具或服务端状态。
需要自然语言总结但不要求严格状态的插件可使用 Action.REQLLM。
纯语言游戏优先交给主 LLM；只有当前智能体启用了对应功能插件时，才注入该插件可编辑的软规则。
```

禁止：

```text
把 API Key、生产连接信息、真实用户数据写入提示词或文档。
把纯语言游戏重新强制注入为 tools。
只修改后台描述却以为能改变服务端状态逻辑。
只查 agent-base-prompt.txt 就判断插件没有提示词。
```

# 提示词注入工程

本文档说明 xiaozhi-server 中“大模型看到的提示词和工具描述”从哪里来、在哪里注入、如何进入模型请求，以及本地状态机游戏为什么不再依赖大段玩法提示词。

## 1. 注入对象

运行时进入大模型的内容分为四类：

```text
系统提示词            角色人设、动态上下文、时间天气等
对话历史              user / assistant / tool 历史消息
工具描述              OpenAI tools / function call 描述
工具执行结果          ActionResponse.result 或 response
```

不是所有“规则”都会以自然语言 prompt 形式进入大模型。对需要稳定记忆和低延迟的游戏，规则已经下沉到服务端状态机，模型只看到“该调用哪个工具”的工具描述。

## 2. 系统提示词注入

主入口在：

```text
main/xiaozhi-server/core/connection.py
  _init_prompt_enhancement()
```

链路：

```text
config["prompt"]
  -> PromptManager.build_enhanced_prompt(...)
  -> ConnectionHandler.change_system_prompt(...)
  -> dialogue 中的 system 消息
  -> LLM 请求 messages
```

相关文件：

```text
main/xiaozhi-server/agent-base-prompt.txt
main/xiaozhi-server/core/utils/prompt_manager.py
main/xiaozhi-server/core/connection.py
```

这条链路负责角色人设、动态上下文、天气上下文、设备上下文等全局行为，不负责每个插件的具体玩法状态。

## 3. 工具描述注入

工具描述的统一出口是：

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

服务端插件工具由这里生成：

```text
main/xiaozhi-server/core/providers/tools/server_plugins/plugin_executor.py
  ServerPluginExecutor.get_tools()
```

`get_tools()` 会合并：

```text
必要内置函数：handle_exit_intent、get_lunar
后台配置函数：config["Intent"][selected]["functions"]
本地强制状态机工具：guess_what_i_say、idiom_chain、xiaozhi_thinks_you_guess 等
```

最终每个工具会变成 OpenAI function 格式：

```json
{
  "type": "function",
  "function": {
    "name": "guess_what_i_say",
    "description": "你说我猜状态机游戏...",
    "parameters": {
      "type": "object",
      "properties": {
        "user_message": {"type": "string"},
        "context": {"type": "string"}
      },
      "required": ["user_message"]
    }
  }
}
```

## 4. 主聊天模型注入

当 `Intent` 选择 `function_call` 模式时，主聊天模型直接接收工具描述。

入口：

```text
main/xiaozhi-server/core/connection.py
  chat()
```

关键链路：

```text
functions = list(conn.func_handler.get_functions())
functions.append(DIRECT_ANSWER_TOOL)  # 仅第一层调用
llm.response_with_functions(session_id, messages, functions=functions)
```

OpenAI 兼容请求在：

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

所以主聊天模型看到的插件提示词不是拼在 system prompt 里，而是作为 `tools` 字段进入模型请求。

## 5. 意图模型注入

当使用 `intent_llm` 或需要外挂意图识别时，工具描述会被拼进意图识别系统提示词。

入口：

```text
main/xiaozhi-server/core/providers/intent/intent_llm/intent_llm.py
  detect_intent()
```

链路：

```text
functions = conn.func_handler.get_functions()
prompt = get_intent_system_prompt(functions)
llm.response_no_stream(system_prompt=prompt, user_prompt=current_dialogue)
```

`get_intent_system_prompt()` 会把每个函数的：

```text
name
description
parameters
```

展开成中文提示词，要求意图模型只输出 JSON：

```json
{"function_call": {"name": "idiom_chain", "arguments": {"user_message": "成语接龙"}}}
```

注意：活跃游戏中，`handle_user_intent()` 会优先检查 `GameSessionManager.get_active_tool()`，如果已有活动游戏，会绕过意图模型，直接构造该工具调用。这是为了避免“对的”“不是”被缓存或误判。

位置：

```text
main/xiaozhi-server/core/handle/intentHandler.py
  handle_user_intent()
```

## 6. 声明式互动插件

声明式插件来自后台配置：

```text
ai_model_provider.model_type = Plugin
ai_agent_plugin_mapping.param_info
```

运行时配置进入：

```text
conn.config["plugins"][provider_code]
```

`ServerPluginExecutor._build_declarative_interaction_tool()` 会把后台的 `description`、`instructions`、`parameters_schema` 变成工具描述。

执行时：

```text
LLM 调用工具
  -> _execute_declarative_interaction_plugin()
  -> 返回 Action.REQLLM
  -> 把 <interaction_plugin>...</interaction_plugin> 写入 tool 结果
  -> 再调用一次 LLM 生成自然回复
```

因此声明式插件有两处提示：

```text
工具描述：用于让模型决定是否调用工具
interaction_plugin：用于工具调用后让模型继续生成回复
```

这类模式灵活，但延迟较高，也容易丢状态。

## 7. 状态机游戏插件

以下插件已经不再靠声明式 prompt 维持玩法：

```text
guess_what_i_say          用户描述，小智猜
idiom_chain               成语接龙
xiaozhi_thinks_you_guess  小智想词，用户猜
guess_xiaozhi_word
i_think_you_guess
```

入口：

```text
main/xiaozhi-server/core/game_session.py
  GameSessionManager
```

工具描述仍然会注入给模型，用于触发工具调用；但玩法规则、隐藏答案、候选集、上一问、已排除条件等状态保存在服务端。

执行链路：

```text
LLM 或活跃游戏直达路由调用工具
  -> ServerPluginExecutor._execute_stateful_game_plugin()
  -> GameSessionManager.handle_...
  -> 返回 Action.RESPONSE
  -> 直接进入 TTS
```

状态机插件不会返回 `Action.REQLLM`，因此不会二次调用大模型。

这解释了为什么你在后台插件配置里看不到完整“玩法提示词”：关键玩法已经在代码状态机里，不再交给大模型临场发挥。

## 8. 当前本地状态机提示词职责

### `guess_what_i_say`

工具描述职责：

```text
告诉模型：用户要玩你说我猜或游戏中继续给线索时调用 guess_what_i_say，并原样传 user_message。
```

状态机职责：

```text
保存上一问
保存 yes/no 对应的事实
保存排除的人名
历史人物按地域 -> 年代 -> 身份 -> 事迹 -> 领域排查
候选耗尽时要求用户补充线索，不违背已确认条件乱猜
```

### `idiom_chain`

工具描述职责：

```text
告诉模型：用户要玩成语接龙或游戏中继续回答时调用 idiom_chain。
```

状态机职责：

```text
保存当前要接的字
保存已用成语
保存用户接对数量
判断是否常见四字成语
无效答案不改变当前要接的字
```

### `xiaozhi_thinks_you_guess`

工具描述职责：

```text
告诉模型：小智心里想一个词让用户猜时调用对应工具。
```

状态机职责：

```text
服务端保存隐藏 target_word
保存已给提示
处理猜不到、揭晓答案、不玩了
禁止把隐藏答案交给 LLM 维护
```

## 9. 排查方法

查看当前连接支持哪些工具：

```bash
tail -f .run/xiaozhi-server.log
```

搜索日志：

```text
当前支持的函数列表
执行工具: guess_what_i_say
执行工具: idiom_chain
```

在代码中查看工具描述：

```bash
rg -n "_build_.*tool|_execute_stateful_game_plugin|_execute_declarative_interaction_plugin" \
  main/xiaozhi-server/core/providers/tools/server_plugins/plugin_executor.py
```

查看工具是否二次调用 LLM：

```text
Action.RESPONSE  -> 直接回复，低延迟
Action.REQLLM    -> 工具结果再交给 LLM 生成，高延迟
```

相关处理位置：

```text
main/xiaozhi-server/core/connection.py
  _handle_tool_results()

main/xiaozhi-server/core/handle/intentHandler.py
  process_intent_result()
```

## 10. 修改规则

修改插件提示词或状态机时遵守：

```text
只影响是否调用工具的内容，写在工具 description。
必须严格保存状态、答案、候选集、计分的内容，写在 GameSessionManager。
需要灵活生成自然语言但不要求严格状态的插件，可以继续用声明式 REQLLM。
语音实时互动、高频游戏、容易丢上下文的玩法，必须优先用 Action.RESPONSE 状态机。
```

不要把 API Key、生产配置、真实用户数据写入提示词或文档。

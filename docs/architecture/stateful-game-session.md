---
status: supporting
owner: tools
last_verified: 2026-07-12
source_paths:
  - main/xiaozhi-server/core/game
  - main/xiaozhi-server/core/providers/tools/server_plugins
---

# 游戏会话边界

本文记录 xiaozhi-server 中互动游戏的当前运行边界。

## 当前策略

成语接龙、你说我猜、小智想词用户猜这类开放互动游戏不再由服务端状态机强制接管，也不再注册为 LLM tools。

它们仍然保留在“功能插件”配置里。当前智能体勾选某个游戏插件后，运行时会读取该插件的 `instructions` 和 `opening_message`，作为本轮主 LLM 的临时软提示词注入。后台取消勾选则完全不注入；后台修改规则后，下次会话使用新规则。

这些旧工具名保留为兼容标识，但运行时不会被强制注册为 tools，也不会被 `handle_user_intent()` 提前路由：

```text
guess_what_i_say
idiom_chain
xiaozhi_thinks_you_guess
guess_xiaozhi_word
i_think_you_guess
```

用户说“成语接龙”“你说我猜”“你想一个词我来猜”等明确玩法表达时，应回到主 LLM 对话链路，由模型根据上下文和当前功能插件规则自然主持游戏。

## 为什么取消状态机

这些玩法对语义理解、灵活追问和自然主持要求高。服务端候选库、关键词抽取、固定问答和计分规则会压过模型本身能力，导致：

- 用户说“提示”被当作普通答案解析。
- 用户已经表达“我想好了，请你猜”，仍被要求重新选择类别。
- 模型原本能自然处理的线索被小候选库限制。
- 工具调用气泡和固定话术让调试体验显得迟钝。

因此当前原则是：开放游戏交给主 LLM；功能插件只提供可启停、可编辑的玩法软规则；服务端只保留通用对话、工具、MCP 和设备控制能力。

## 代码位置

关键代码：

```text
main/xiaozhi-server/core/game_session.py
  GameSessionManager.FREEFORM_GAME_TOOLS
  GameSessionManager.build_freeform_game_prompt()
  GameSessionManager.GUESS_MY_WORD_TOOLS = set()
  GameSessionManager.USER_DESCRIBES_TOOLS = set()
  GameSessionManager.IDIOM_CHAIN_TOOLS = set()
  GameSessionManager.get_requested_game_tool() -> None

main/xiaozhi-server/core/connection.py
  ConnectionHandler._get_enabled_plugin_configs()
  ConnectionHandler._with_freeform_game_prompt()

main/xiaozhi-server/core/providers/tools/server_plugins/plugin_executor.py
  ServerPluginExecutor.get_tools()
  ServerPluginExecutor.has_tool()
```

`ServerPluginExecutor` 会跳过 `FREEFORM_GAME_TOOLS` 中的旧游戏 code，避免后台历史配置再次把这些玩法注入成工具。`ConnectionHandler` 只从当前智能体已启用的插件配置中读取软提示词。

## 新增游戏的原则

除非玩法必须访问真实外部状态或设备能力，否则不要新增服务端游戏状态机。

可以考虑服务端工具的情况：

- 必须调用设备、MCP、音乐、天气、业务接口。
- 必须读取服务端数据库或权限状态。
- 必须做安全校验或事务性动作。

不建议做服务端工具的情况：

- 成语接龙、猜谜、角色扮演、问答、讲故事等纯语言游戏。
- 只需要模型记住上下文即可完成的多轮互动。
- 规则变化大、用户表达开放、候选空间开放的玩法。

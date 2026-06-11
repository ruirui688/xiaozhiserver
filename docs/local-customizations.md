# 本地定制记录

本文档记录本项目相对官方 `xiaozhi-esp32-server` 的长期本地定制。同步官方 `upstream` 前必须先阅读本文档，避免误覆盖本地业务能力。

## 1. 功能插件架构与声明式互动模式

```text
定制模块：xiaozhi-server 服务端插件执行器
改动原因：把天气、新闻、音乐播放、HomeAssistant 和讲段子、热梗、成语接龙、你说我猜等能力统一为 Function Plugin 顶层模型；声明式互动玩法不需要为每个玩法新增 Python 插件文件。
关键文件：
  - main/xiaozhi-server/core/providers/tools/server_plugins/plugin_executor.py
  - docs/integrations/function-plugin-architecture.md
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方也修改插件注册、工具描述生成或 ServerPluginExecutor 执行流程，必须人工合并，保留 Function Plugin 顶层模型、声明式互动模式、custom_plugin 兼容字段、parameters_schema、opening_message、instructions 等配置约定。
```

## 2. Ubuntu 源码开发环境文档

```text
定制模块：本地开发环境说明
改动原因：记录本地 Ubuntu 开发机源码编译、依赖安装、服务启动和验证流程，区别于服务器部署文档。
关键文件：
  - docs/development/ubuntu-source-development-environment.md
是否允许被官方覆盖：否
官方同步时的处理规则：官方部署文档更新时可以补充本文档，但不要用部署流程替换本地源码开发流程。
```

## 3. xiaozhi-server 本地后台启动稳定性

```text
定制模块：xiaozhi-server 本地启动入口与开发启动脚本
改动原因：本地使用 nohup/后台方式启动 xiaozhi-server 时，app.py 原先无条件创建 stdin 监听任务；后台进程 stdin 不可交互，可能导致服务启动后立即退出。开发脚本原先使用 nohup 直接启动，也容易受调用者进程组影响。
关键文件：
  - main/xiaozhi-server/app.py
  - scripts/start-dev.sh
  - docs/development/ubuntu-source-development-environment.md
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改 app.py 启动流程或本地开发脚本，必须保留“仅交互式终端启用 stdin 监听”和“后台启动时脱离调用者进程组并将 stdin 指向 /dev/null”的约定，确保 xiaozhi-server 可长期后台运行。
```

## 4. 语音回复质量与互动游戏规则

```text
定制模块：LLM 输出清洗、工具调用上下文、声明式互动插件提示词、互动插件预设、状态机游戏会话
改动原因：语音场景下，模型容易把“哈哈”“哎呀”“好的”等口头禅单独切成 TTS 首句，听起来迟钝；声明式互动插件如果规则太软，会在“你说我猜”等游戏首轮直接问用户答案；用户打断播报后，旧工具结果可能继续写入下一轮上下文；“我想你猜”这类小智心里有答案的游戏必须由服务端保存隐藏答案，不能依赖 LLM 自己记。
关键文件：
  - main/xiaozhi-server/agent-base-prompt.txt
  - main/xiaozhi-server/core/connection.py
  - main/xiaozhi-server/core/game_session.py
  - main/xiaozhi-server/core/handle/abortHandle.py
  - main/xiaozhi-server/core/handle/intentHandler.py
  - main/xiaozhi-server/core/providers/intent/intent_llm/intent_llm.py
  - main/xiaozhi-server/core/providers/tools/server_plugins/plugin_executor.py
  - main/xiaozhi-server/core/providers/tools/unified_tool_handler.py
  - main/manager-web/src/views/FunctionPluginManagement.vue
  - main/xiaozhi-server/config.yaml
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改聊天流式输出、function call、TTS 入队、打断处理、意图缓存或 Function Plugin 预设，必须保留以下约定：句首口头禅在进入 TTS 和历史前清理；工具结果绑定本轮 sentence_id 和 abort_generation；REQLLM 工具结果二次生成时禁用继续调工具；声明式互动插件必须强制 user_message 参数并传递 context；首轮信息不足时只索要线索，不允许问答案；“不是、再猜、它能吃”等短反馈不能跨上下文复用意图缓存；多工具合并必须读取 ActionResponse.result/response；`xiaozhi_thinks_you_guess` 必须走服务端 GameSessionManager，隐藏 target_word 只保存在服务端状态里，不能交给 LLM 维护。
```

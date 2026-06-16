# 本地定制记录

本文档记录本项目相对官方 `xiaozhi-esp32-server` 的长期本地定制。同步官方 `upstream` 前必须先阅读本文档，避免误覆盖本地业务能力。

## 1. 功能插件架构与声明式互动模式

```text
定制模块：xiaozhi-server 服务端插件执行器
改动原因：把天气、新闻、音乐播放、HomeAssistant 和讲段子、热梗、成语接龙、你说我猜等能力统一为 Function Plugin 顶层模型；声明式互动玩法不需要为每个玩法新增 Python 插件文件。
关键文件：
  - main/xiaozhi-server/core/providers/tools/server_plugins/plugin_executor.py
  - docs/architecture/cloud-runtime-architecture.md
  - docs/integrations/function-plugin-architecture.md
  - docs/integrations/prompt-injection-engineering.md
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方也修改插件注册、工具描述生成、提示词注入、配置下发、MCP 工具发现或 ServerPluginExecutor 执行流程，必须人工合并，保留 Function Plugin 顶层模型、声明式互动模式、状态机游戏模式、custom_plugin 兼容字段、parameters_schema、opening_message、instructions 等配置约定，并同步更新云端运行架构和提示词注入工程文档。
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
改动原因：本地使用 nohup/后台方式启动 xiaozhi-server 时，app.py 原先无条件创建 stdin 监听任务；后台进程 stdin 不可交互，可能导致服务启动后立即退出。旧开发脚本原先直接写 `.run/` 和 `logs/dev/`，容易形成第二套运行状态；现在 `scripts/dev` 是唯一开发入口，旧 `scripts/start-dev.sh` 只做参数兼容转发。
关键文件：
  - main/xiaozhi-server/app.py
  - scripts/dev
  - scripts/start-dev.sh
  - docs/development/ubuntu-source-development-environment.md
  - docs/development/local-dev-workflow.md
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改 app.py 启动流程或本地开发脚本，必须保留“仅交互式终端启用 stdin 监听”和“后台启动时脱离调用者进程组并将 stdin 指向 /dev/null”的约定，确保 xiaozhi-server 可长期后台运行；同时必须保留 `scripts/dev` 作为唯一开发入口，旧 `scripts/start-dev.sh` 只能转发到 `scripts/dev`，不得重新写 `.run/` 或 `logs/dev/`。
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
  - main/xiaozhi-server/core/providers/tools/device_mcp/mcp_executor.py
  - main/xiaozhi-server/core/providers/tools/device_mcp/mcp_handler.py
  - main/xiaozhi-server/core/providers/tools/server_plugins/plugin_executor.py
  - main/xiaozhi-server/core/providers/tools/unified_tool_handler.py
  - main/manager-web/src/views/FunctionPluginManagement.vue
  - main/xiaozhi-server/config.yaml
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改聊天流式输出、function call、TTS 入队、打断处理、意图缓存、设备端 MCP 或 Function Plugin 预设，必须保留以下约定：句首口头禅在进入 TTS 和历史前清理；工具结果绑定本轮 sentence_id 和 abort_generation；REQLLM 工具结果二次生成时禁用继续调工具；声明式互动插件必须强制 user_message 参数并传递 context；首轮信息不足时只索要线索，不允许问答案；“不是、再猜、它能吃”等短反馈不能跨上下文复用意图缓存；存在活跃状态机游戏时，后续输入必须优先回到当前游戏工具；“玩个游戏/来个游戏/小游戏”默认进入 `idiom_chain` 状态机，不允许 LLM 自己编无状态游戏开场；“换游戏”只结束当前局，用户明确说“玩成语接龙”“你想一个词我来猜”“玩你说我猜”等新玩法时才切换工具；状态机游戏工具调用和工具结果不作为聊天气泡上报，只保留用户输入和最终播报文本；多工具合并必须读取 ActionResponse.result/response；设备 MCP 发送失败必须立即返回，连接关闭时取消等待中的 MCP 调用，音量/亮度等控制类 MCP 使用短超时并直接回复；`xiaozhi_thinks_you_guess` 必须走服务端 GameSessionManager，隐藏 target_word 只保存在服务端状态里，不能交给 LLM 维护，首轮必须带类别和第一条提示；用户说“不玩了/退出/猜不到/想不到/不会/揭晓答案”时必须由状态机结束或揭晓本局，禁止切换成“轮到你了，我来猜你”；`idiom_chain` 必须走服务端 GameSessionManager，保存当前要接的字、已用成语和接对数量，并直接返回 Action.RESPONSE，禁止用 REQLLM 二次生成造成游戏延迟；成语接龙只按内置成语库和字面首字判定，不承诺同音字接龙，已用成语不得重复计分；`guess_what_i_say` 必须走服务端 GameSessionManager，保存上一问、已知线索和排除条件，历史人物类题目按地域、年代、身份、事迹领域顺序排查，否定回答必须结合上一轮猜测或事实问题更新状态，禁止连续随机猜朝代或皇帝；前端预设不能承诺未实现的通用物品、地点或动物推理能力。
```

## 5. 云端运行架构与提示词注入文档

```text
定制模块：项目工程文档
改动原因：把“小智云端服务器分析报告.docx”的调研结论整理为仓库内长期文档，补齐后台配置下发、Python 实时链路、提示词注入、工具 schema 注入、MCP 三类链路和状态机游戏规则的工程说明。
关键文件：
  - docs/architecture/project-architecture.md
  - docs/architecture/cloud-runtime-architecture.md
  - docs/integrations/prompt-injection-engineering.md
  - docs/README.md
是否允许被官方覆盖：否
官方同步时的处理规则：官方文档同步时不得删除这些架构和提示词工程文档；如果官方修改连接初始化、PromptManager、Dialogue、UnifiedToolHandler、MCP executor、Intent provider、ConfigServiceImpl 或插件执行器，必须同步校正文档中的真实代码路径和运行链路。
```

## 6. 工程治理入口、配置隔离与部署资产边界

```text
定制模块：仓库治理、开发脚本、配置示例、运行产物目录、部署资产归档
改动原因：项目业务模块增多后，必须建立唯一开发入口、统一配置模型、运行产物隔离和部署资产归档规则，避免 Git status 噪音、私有配置误提交、Docker/源码/服务器部署互相干扰。
关键文件：
  - scripts/dev
  - docs/development/local-dev-workflow.md
  - docs/standards/config-runtime-policy.md
  - docs/standards/upstream-sync-policy.md
  - docs/deployment/deployment-asset-policy.md
  - config/examples/
  - runtime/
  - deploy/
  - .gitignore
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改启动脚本、配置目录、Docker/Compose、部署文档、.gitignore 或开发规范，必须保留以下约定：日常本地开发以 `scripts/dev` 为统一入口；私有配置和运行产物进入 `runtime/` 或旧兼容私有路径且不得提交；脱敏示例进入 `config/examples/`；部署资产后续归档到 `deploy/`；现有部署脚本迁移前必须保留兼容入口；官方同步前必须按 `upstream-sync-policy.md` 审查本地定制。
```

## 7. 天气查询容错

```text
定制模块：xiaozhi-server 天气插件
改动原因：和风天气 API Key 或 API Host 失效时，原实现会把 Authentication failed、Invalid Host 等接口错误交给 LLM 二次解释，导致回复“城市不存在”“南京玄武区不存在”“杭州也找不到”等错误结论。
关键文件：
  - main/xiaozhi-server/plugins_func/functions/get_weather.py
  - main/xiaozhi-server/config.yaml
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改 get_weather 插件，必须保留以下约定：和风接口异常、认证失败、Host 无效、城市查询失败或页面抓取失败时，先使用 Open-Meteo 兜底查询；`南京市玄武区`、`南京市江宁区` 等区县输入需要规范化为城市候选；工具结果必须以 Action.RESPONSE 直接返回给用户，不能再交给 LLM 把接口错误改写成地点不存在。
```

## 8. 视觉解释接口后台参数化

```text
定制模块：视觉模型配置下发、后台参数管理、Python 配置合并
改动原因：WSL/端口转发部署时，Python 默认用 get_local_ip() 生成 172.28.*.* 这类 WSL 内网地址，ESP32 无法从局域网直接访问，导致 self_camera_take_photo 拍照成功后连接 explain URL 失败。需要把下发给设备的视觉解释接口地址纳入后台参数管理，由运维明确配置为设备可访问的 LAN IP、域名或反向代理地址。
关键文件：
  - main/manager-api/src/main/java/xiaozhi/common/constant/Constant.java
  - main/manager-api/src/main/java/xiaozhi/modules/sys/controller/SysParamsController.java
  - main/manager-api/src/main/resources/db/changelog/202606151745.sql
  - main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml
  - main/xiaozhi-server/config/config_loader.py
  - main/xiaozhi-server/core/utils/util.py
  - docs/mcp/mcp-vision-integration.md
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改 server 配置下发、SysParamsController、ConfigServiceImpl、config_loader.py 或视觉接口逻辑，必须保留 `server.vision_explain` 作为后台参数；Python 从 manager-api 读取配置时，监听地址 `server.ip/port/http_port/auth_key` 可继续以本地文件为准，但下发给设备的 `server.vision_explain` 默认必须使用后台值，只有本地文件配置了非空、非 `null`、非 `你的...` 的真实值时才允许覆盖。修改参数后需要刷新 Redis `server:config` 和 `sys:params` 缓存或通过后台保存参数触发配置重建，并重启/通知 xiaozhi-server 重新读取配置。
```

## 9. 语义控制中枢与语音操作系统架构

```text
定制模块：xiaozhi-server 语义前置控制链路
改动原因：把小智从“ASR 后直接交给 LLM/function-call”的聊天机器人链路，演进为“TextNormalizer -> SemanticRouter -> StateManager -> PolicyEngine -> SemanticCenter”的语音控制中枢。停止、退出游戏、游戏续局、低置信度拒识等高频控制必须优先由服务端规则和状态处理，LLM 只作为复杂语义和普通聊天兜底。
关键文件：
  - main/xiaozhi-server/core/semantic/
  - main/xiaozhi-server/core/connection.py
  - main/xiaozhi-server/core/handle/receiveAudioHandle.py
  - main/xiaozhi-server/core/providers/tools/unified_tool_handler.py
  - main/xiaozhi-server/tests/test_semantic_router.py
  - main/xiaozhi-server/tests/test_semantic_policy.py
  - docs/architecture/semantic-control-center.md
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改 startToChat、ConnectionHandler 初始化、handle_user_intent、GameSessionManager、UnifiedToolHandler、ToolManager、abortHandle、TTS 入队或 function-call 主链路，必须保留语义控制中枢在旧意图/LLM 之前的前置拦截位置；未命中高置信度规则时必须继续兜底到旧 handle_user_intent 和 conn.chat；停止类指令必须复用 handleAbortMessage 并允许配置动作停止工具；游戏态输入必须优先回到 active_game_tool；退出游戏不得关闭 websocket；语义模块顶层导入应尽量轻量，避免引入 opus/loguru 等运行依赖导致纯单元测试无法导入。
```

# 本地定制记录

本文档记录本项目相对官方 `xiaozhi-esp32-server` 的长期本地定制。同步官方 `upstream` 前必须先阅读本文档，避免误覆盖本地业务能力。

## 1. 功能插件架构与声明式互动模式

```text
定制模块：xiaozhi-server 服务端插件执行器
改动原因：把天气、新闻、音乐播放、HomeAssistant 和讲段子、热梗、成语接龙、你说我猜等能力统一为 Function Plugin 顶层模型；声明式互动玩法不需要为每个玩法新增 Python 插件文件。
关键文件：
  - main/xiaozhi-server/core/providers/tools/server_plugins/plugin_executor.py
  - docs/architecture/cloud-runtime-architecture.md
  - docs/architecture/function-plugin-architecture.md
  - docs/architecture/prompt-injection-engineering.md
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

## 4. xiaozhi-server Python 运行时必须使用 Conda

```text
定制模块：xiaozhi-server Python 运行时与验证命令
改动原因：本项目的 xiaozhi-server 运行时依赖安装在 Conda 环境 `xiaozhi-esp32-server` 中。系统 Python 可能缺少 loguru、requests、pytest 等依赖，直接使用系统 `python` / `python3` / `pytest` 会产生误报，不能代表项目真实运行时。
关键文件：
  - main/xiaozhi-server/requirements.txt
  - scripts/dev
  - AGENTS.md
  - docs/development/ubuntu-source-development-environment.md
  - docs/development/local-dev-workflow.md
  - docs/standards/ai-coding-agent-instructions.md
是否允许被官方覆盖：否
官方同步时的处理规则：涉及 `main/xiaozhi-server` 的 Python 验证、单测、导入检查、脚本执行，必须使用 `conda run -n xiaozhi-esp32-server ...`，或先确认 shell 已 `conda activate xiaozhi-esp32-server`。`scripts/dev` 必须保留“缺少 Conda 环境时失败而不是退回系统 Python”的行为。禁止退回系统 Python 冒充验证；如果目标 Conda 环境不可用，必须在验证结果中明确说明“未在项目 Conda 运行时验证”。
```

## 5. 语音回复质量与互动游戏规则

```text
定制模块：LLM 输出清洗、工具调用上下文、声明式互动插件提示词、互动插件预设、状态机游戏会话
改动原因：语音场景下，模型容易把“哈哈”“哎呀”“好的”等口头禅单独切成 TTS 首句，听起来迟钝；声明式互动插件如果规则太软，会在“你说我猜”等游戏首轮直接问用户答案；用户打断播报后，旧工具结果可能继续写入下一轮上下文；成语接龙、你说我猜、小智想词用户猜等纯语言游戏需要功能插件里的可编辑规则约束范围，但不能再被服务端状态机或强制工具调用接管。
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
官方同步时的处理规则：如果官方修改聊天流式输出、function call、TTS 入队、打断处理、意图缓存、设备端 MCP 或 Function Plugin 预设，必须保留以下约定：句首口头禅在进入 TTS 和历史前清理；工具结果绑定本轮 sentence_id 和 abort_generation；REQLLM 工具结果二次生成时禁用继续调工具；声明式互动插件必须强制 user_message 参数并传递 context；多工具合并必须读取 ActionResponse.result/response；设备 MCP 发送失败必须立即返回，连接关闭时取消等待中的 MCP 调用，音量/亮度等控制类 MCP 使用短超时并直接回复；成语接龙、你说我猜、小智想词用户猜等纯语言游戏不得再由服务端状态机强制路由或注入为 tools，旧 provider_code 必须保留在 `GameSessionManager.FREEFORM_GAME_TOOLS` 并由 `ServerPluginExecutor` 跳过；当前智能体勾选对应功能插件时，只允许把插件里的 `instructions` / `opening_message` 作为临时软提示词注入主 LLM。
```

## 6. 云端运行架构与提示词注入文档

```text
定制模块：项目工程文档
改动原因：把“小智云端服务器分析报告.docx”的调研结论整理为仓库内长期文档，补齐后台配置下发、Python 实时链路、提示词注入、工具 schema 注入、MCP 三类链路和状态机游戏规则的工程说明。
关键文件：
  - docs/architecture/project-architecture.md
  - docs/architecture/cloud-runtime-architecture.md
  - docs/architecture/prompt-injection-engineering.md
  - docs/README.md
是否允许被官方覆盖：否
官方同步时的处理规则：官方文档同步时不得删除这些架构和提示词工程文档；如果官方修改连接初始化、PromptManager、Dialogue、UnifiedToolHandler、MCP executor、Intent provider、ConfigServiceImpl 或插件执行器，必须同步校正文档中的真实代码路径和运行链路。
```

## 7. 工程治理入口、配置隔离与部署资产边界

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

## 8. 生产 Docker Compose 资源限制

```text
定制模块：生产 Docker Compose 资源限制
改动原因：生产服务器目标规格可能只有 2 核 4G，且 Python 容器默认启用 CPU FunASR/SenseVoiceSmall 本地识别。为避免 Python、Java/Web、MySQL 或 Redis 任一容器内存/CPU 失控拖死宿主机，生产最小部署包在 docker-compose.yml 中设置 cpus、mem_limit、memswap_limit、pids_limit 和日志大小限制；install 会把默认限制写入 ~/xiaozhi-data/.env 并保留已有覆盖值。默认预算优先给 Python/FunASR 至少 2G 内存，并压缩 Java/Web、MySQL 和 Redis 的默认内存；Java 容器通过 JAVA_TOOL_OPTIONS 约束堆内存，MySQL 和 Redis 也设置与容器上限匹配的运行参数。
关键文件：
  - deploy/production/docker-compose.yml
  - deploy/production/install
  - deploy/production/README.md
  - docs/deployment/private-docker-release.md
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改 Docker Compose、安装脚本或生产部署文档，必须保留 2 核 4G 默认资源预算、Python/FunASR 至少 2G 内存、memswap_limit 等于 mem_limit 的防 swap 约定、~/xiaozhi-data/.env 中 XIAOZHI_* 资源变量的持久化覆盖能力，以及 Java/MySQL/Redis 与容器限制匹配的运行参数。
```

## 9. 天气查询容错

```text
定制模块：xiaozhi-server 天气插件
改动原因：和风天气 API Key 或 API Host 失效时，原实现会把 Authentication failed、Invalid Host 等接口错误交给 LLM 二次解释，导致回复“城市不存在”“南京玄武区不存在”“杭州也找不到”等错误结论。
关键文件：
  - main/xiaozhi-server/plugins_func/functions/get_weather.py
  - main/xiaozhi-server/config.yaml
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改 get_weather 插件，必须保留以下约定：和风接口异常、认证失败、Host 无效、城市查询失败或页面抓取失败时，先使用 Open-Meteo 兜底查询；`南京市玄武区`、`南京市江宁区` 等区县输入需要规范化为城市候选；工具结果必须以 Action.RESPONSE 直接返回给用户，不能再交给 LLM 把接口错误改写成地点不存在。
```

## 10. 视觉解释接口后台参数化

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

## 11. 语义控制中枢与语音操作系统架构

```text
定制模块：xiaozhi-server 语义前置控制链路
改动原因：把小智从“ASR 后直接交给 LLM/function-call”的聊天机器人链路，演进为“TextNormalizer -> SemanticRouter -> StateManager -> PolicyEngine -> SemanticCenter”的语音控制中枢。停止、退出游戏、游戏续局、低置信度拒识等高频控制必须优先由服务端规则和状态处理，LLM 只作为复杂语义和普通聊天兜底。
关键文件：
  - main/xiaozhi-server/core/semantic/
  - main/xiaozhi-server/core/connection.py
  - main/xiaozhi-server/core/handle/receiveAudioHandle.py
  - main/xiaozhi-server/core/providers/tools/unified_tool_handler.py
  - main/xiaozhi-server/tests/test_semantic_interruption.py
  - main/xiaozhi-server/tests/test_semantic_router.py
  - main/xiaozhi-server/tests/test_semantic_policy.py
  - docs/architecture/semantic-control-center.md
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改 startToChat、ConnectionHandler 初始化、handle_user_intent、GameSessionManager、UnifiedToolHandler、ToolManager、abortHandle、TTS 入队或 function-call 主链路，必须保留语义控制中枢在旧意图/LLM 之前的前置拦截位置；TTS 播放期间识别到用户重叠说话时，是否打断必须由当前 LLM 基于少量上下文裁决 continue / interrupt / new_turn，服务端不得用附和词表、停止词表或长度阈值替代语义打断判断；语义打断裁决器调用 LLM 时必须有短超时，超时、解析失败或模型异常必须降级为 new_turn 并继续处理用户新输入，不能让异常冒泡关闭 WebSocket；LLM 裁决为 continue 时不得清空 TTS 队列、不得进入主聊天；裁决为 interrupt 时先复用 handleAbortMessage 停止当前播报，随后必须交给语义中枢确认是否为停止/退出等高置信控制指令，若未命中控制指令则继续按 new_turn 进入旧 handle_user_intent 和 conn.chat，避免“要你自己唱”这类带新任务的打断被吞掉；裁决为 new_turn 或裁决失败时复用 handleAbortMessage 后继续处理新输入；未命中高置信度规则时必须继续兜底到旧 handle_user_intent 和 conn.chat；停止类指令必须复用 handleAbortMessage 并允许配置动作停止工具；纯语言游戏不得优先回到 active_game_tool，应交给主 LLM 对话，并只从已启用功能插件读取可编辑软规则；退出游戏不得关闭 websocket；语义模块顶层导入应尽量轻量，避免引入 opus/loguru 等运行依赖导致纯单元测试无法导入。
```

## 12. 管理后台模型测试详情字段补全

```text
定制模块：manager-api 模型配置测试接口
改动原因：manager-web 的模型测试详情弹窗已经展示 testInput、testOutput、responseTimeMs，但 manager-api 多数测试路径原先只返回 success/message，导致 LLM、ASR、TTS、Ollama 和基础配置校验即使测试通过，弹窗仍显示输入、输出和响应耗时为 “-”。视觉模型测试也只能看到服务端本地图片路径，浏览器无法直接预览图片。现在模型测试入口提供统一兜底计时，并在真实调用和失败路径中返回测试输入、输出摘要、耗时和视觉测试图片预览，方便后台排查模型连通性、识别结果、TTS 音频大小和配置完整性问题。
关键文件：
  - main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelConfigServiceImpl.java
  - main/manager-api/src/main/java/xiaozhi/modules/model/dto/ModelTestResultDTO.java
  - main/manager-web/src/views/ModelConfig.vue
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改模型测试接口、ModelTestResultDTO 或 manager-web 测试详情弹窗，必须保留 testModel 返回 testInput、testOutput、responseTimeMs、testStatus、testTime 的约定；视觉模型测试必须通过 testImageDataUrl 或等效可浏览器访问的预览字段展示真实测试图片，不能只显示服务端本地路径；OpenAI 兼容 LLM、Ollama、OpenAI ASR/TTS、豆包流式 ASR、视觉模型、Intent 和基础配置校验都应返回可读的测试输入与输出摘要；真实接口调用要记录调用耗时，未显式计时的路径必须由 testModel 兜底补充耗时；输出摘要不得包含 api_key、access_token、secret_key 等敏感字段。
```

## 13. 本地知识库大模型切片与中文召回增强

```text
定制模块：manager-api 本地知识库适配器、manager-web 知识库配置弹窗、xiaozhi-server 本地知识库工具
改动原因：本地知识库原先对 md/txt 文档主要按固定字符数硬切，容易把标题、适用问题、正文或一句话切开；召回也只按空格/标点切词，中文整句没有空格时容易查不到。现在知识库新增/编辑界面可以为每个知识库选择模型管理中已配置的 LLM 作为切片模型，parser_config 只保存 llm_model_id 和切片参数，API Key、接口地址和上游模型名由模型管理统一维护；文档解析时优先由大模型按语义结构生成 JSON 切片；召回同时匹配 content/question/answer，并使用中文 n-gram 增强自然句检索。为避免大知识库检索时全量加载 chunk 和 O(n*m) 字符串匹配打爆 CPU，本地召回必须先在数据库中按 dataset/document 做有界候选召回，再在 Java 内存中对有限候选精排；知识库高级设置保存在 parser_config.retrieval_config 中，用于控制常规/展会模式、语义/混合检索、召回级别、最大召回数、阈值和重排开关。
关键文件：
  - main/manager-api/src/main/java/xiaozhi/modules/knowledge/rag/impl/LocalKnowledgeAdapter.java
  - main/manager-api/src/main/java/xiaozhi/modules/knowledge/dao/KnowledgeChunkDao.java
  - main/manager-api/src/main/resources/mapper/knowledge/KnowledgeChunkDao.xml
  - main/manager-api/src/main/java/xiaozhi/modules/knowledge/service/impl/KnowledgeBaseServiceImpl.java
  - main/manager-api/src/main/resources/db/changelog/202606111650.sql
  - main/manager-api/src/main/resources/db/changelog/202606291030.sql
  - main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml
  - main/manager-web/src/components/KnowledgeBaseDialog.vue
  - main/manager-web/src/views/KnowledgeBaseManagement.vue
  - main/manager-web/src/i18n/zh_CN.js
  - main/xiaozhi-server/plugins_func/functions/search_from_local_knowledge.py
  - docs/models/local-knowledge-integration.md
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改本地知识库、RAG 模型配置、知识库弹窗、文档解析、本地知识库工具、知识库数据库迁移或切片查询 DAO，必须保留知识库级 parser_config 覆盖 RAG_LocalKnowledge 默认配置的约定；知识库切片模型应通过 llm_model_id 复用模型管理中的 LLM 配置，知识库配置不得再次要求用户填写真实 API Key；文档解析优先走大模型切片，失败时才回退段落切分；旧文档必须重新解析才应用新切片；召回必须同时匹配 content/question/answer，并保留中文自然句 n-gram 检索增强；检索链路禁止退回全量 selectList 后逐条 contains 的实现，必须保留 SQL LIMIT 候选召回、候选 term 数量上限、最终返回数量上限和 parser_config.retrieval_config 的兼容读取；新增全文索引迁移必须登记到 db.changelog-master.yaml。
```

## 14. 图片识别自然表达优化

```text
定制模块：xiaozhi-server OpenAI 兼容视觉模型、VLLM 模型配置、manager-api VLLM 供应器字段
改动原因：设备拍照后的图片识别回复容易出现“以下是详细描述”“画面的主体是”等报告式表达，在聊天记录和语音播报中显得生硬。现在视觉模型默认增加自然口语化系统提示词，先给一句结论再补充少量关键细节，并让 max_tokens、temperature、top_p 真正传入 OpenAI 兼容接口；智控台 VLLM 配置可编辑 prompt 和生成参数。
关键文件：
  - main/xiaozhi-server/core/providers/vllm/openai.py
  - main/xiaozhi-server/config.yaml
  - main/xiaozhi-server/tests/test_vllm_openai_provider.py
  - main/manager-api/src/main/resources/db/changelog/202606161805.sql
  - main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改 VLLM provider、视觉模型配置或模型供应器字段，必须保留默认自然表达提示词、可配置 prompt、生成参数透传和无配置时的默认兜底；视觉回复不得退回编号报告式模板。
```

## 15. 可视化 MCP HTTP 工具

```text
定制模块：manager-web 智能体功能弹窗、manager-api no-code MCP tool 配置、xiaozhi-server MCP HTTP 工具桥
改动原因：需要让可信运营人员不写 Python 插件也能为智能体配置简单 HTTP 工具，并通过 MCP Endpoint 注册给运行时工具系统调用。
关键文件：
  - main/manager-web/src/components/FunctionDialog.vue
  - main/manager-web/src/apis/module/agent.js
  - main/manager-api/src/main/java/xiaozhi/modules/agent/controller/AgentMcpAccessPointController.java
  - main/manager-api/src/main/java/xiaozhi/modules/config/controller/ConfigController.java
  - main/manager-api/src/main/java/xiaozhi/modules/agent/dto/McpNoCodeToolDTO.java
  - main/manager-api/src/main/java/xiaozhi/modules/agent/entity/AgentMcpNoCodeToolEntity.java
  - main/manager-api/src/main/resources/db/changelog/202606161720.sql
  - main/xiaozhi-server/scripts/no_code_mcp_tools.py
  - docs/mcp/no-code-http-tools.md
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改 MCP endpoint、智能体功能配置、ConfigController、AgentMcpAccessPointController 或工具 schema 下发链路，必须保留 no-code HTTP tool 的完整链路：Web 配置 JSON -> `ai_agent_mcp_no_code_tools` -> `/config/mcp/no-code-tools/{agentId}` -> `no_code_mcp_tools.py` -> MCP Endpoint tools/list/tools/call。该能力必须继续标注为高风险 HTTP 工具桥，生产环境应限制可信用户、保护 `MANAGER_API_SECRET`，并避免访问内网管理端口、云元数据地址和敏感服务。
```

## 16. 服务器音乐播放状态与音频队列边界

```text
定制模块：xiaozhi-server 服务器音乐播放插件
改动原因：`play_music` 在 function_call 模式下原先只异步提交音乐播放任务，同步工具结果只显示“指令已接收”；管理后台聊天记录看不到实际选中的歌曲，容易误判为卡住。选曲提示语（例如“即将为您播放 具体歌曲”）必须作为普通 TTS 文本真实播报，不能只通过 `send_tts_message` 做屏幕状态。另一个问题是火山双流等 TTS provider 收到 `ContentType.FILE` 音乐文件后会先暂存到 `before_stop_play_files`，如果没有 `SentenceType.LAST` 播放边界就不会 flush 到 WebSocket，表现为后台显示已选中歌曲但设备不播放。后续为避免客户歌曲语义被写死在服务端规则里，播放、唱某首歌、随机播放和曲库查询不再由旧意图入口直接派发，必须通过主 LLM 或 intent LLM 根据工具描述和曲库上下文决定是否调用 `play_music`；服务端仅保留音乐停止类确定性控制，先于播放触发词处理“我不听了/别放了/停止播放”等否定或停止语义，并直接复用 `handleAbortMessage` 清队列和清音乐上下文。
关键文件：
  - main/xiaozhi-server/core/semantic/music_intent.py
  - main/xiaozhi-server/core/semantic/router.py
  - main/xiaozhi-server/core/semantic/policy_engine.py
  - main/xiaozhi-server/core/semantic/semantic_center.py
  - main/xiaozhi-server/core/handle/intentHandler.py
  - main/xiaozhi-server/plugins_func/functions/play_music.py
  - main/xiaozhi-server/music/.xiaozhi-music-metadata.json
  - main/xiaozhi-server/tests/test_music_intent.py
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改 `handle_user_intent`、语义控制中枢、`play_music`、TTS 队列、`Action.RECORD`、`ContentType.FILE` 或流式 TTS provider，必须保留以下约定：播放、唱某首歌、随机播放、换一首、曲库查询和音乐上下文续播应由大模型根据 `play_music` 工具描述、`<music_library>` 曲库上下文和用户表达调度，不得在旧意图入口里硬编码直接调用播放工具；“我不听了/别放了/停止播放”等停止命令可以由语义控制中枢确定性处理，且 `stop` 必须先于播放触发词处理，并直接走 `handleAbortMessage`，不得被抽成歌名；工具同步结果应使用用户可读的“正在为您播放音乐”，不要只返回“指令已接收”；“此刻为您献上/正在播放/即将为您播放 具体歌曲”这类选曲提示语必须按普通 `ContentType.TEXT` 进入 TTS 播报和 assistant 聊天记录，不得只作为屏幕状态；音乐播放入队必须按 `FIRST/ACTION -> MIDDLE/TEXT -> MIDDLE/FILE -> LAST/ACTION` 发送播放边界，确保提示语播完后再播放文件，并确保火山双流、阿里云流式、讯飞流式、Minimax 等 provider 能把暂存文件音频 flush 到设备；曲库 `.xiaozhi-music-metadata.json` 支持 `aliases` 和 `description`，用于后台配置 ASR 近音、歌曲别名、用户俗称和模型可见背景说明，源码不得写入客户或歌曲专属语义。聊天记录中的 `play_music({...})` 和 `{"result": "正在为您播放音乐"}` 是工具调用审计记录，不应被当作播放失败错误。
```

## 17. ACR 镜像生产部署、模型可空与 CPU FunASR

```text
定制模块：ACR 镜像生产部署、模型默认配置、后台智能体模板、Python 运行时模型初始化
改动原因：源码仍完整推送到私有 Git 仓库，但生产服务器不再拉取完整源码，也不从源码构建业务镜像。唯一生产路线改为本地或构建机先构建 `xiaozhi-server` / `xiaozhi-web` 并推送到阿里云 ACR，同时把 `mysql:8.4` 和 `redis:8.0` 同步到 ACR；生产端只 sparse-checkout `deploy/production` 最小部署资产，再从 ACR 拉取全部运行镜像并启动；服务端 base 镜像仅作为构建缓存，不作为生产运行镜像；base 缓存拆为 `xiaozhi-server-base-common`、`xiaozhi-server-base-lite`、`xiaozhi-server-base-funasr` 三层，FunASR base 在 lite base 上追加 torch / torchaudio / funasr，避免 lite 和 FunASR 互相污染，同时保留公共缓存复用；基础镜像以 `base-<依赖哈希>` 作为依赖缓存标签，默认 `BUILD_BASE=auto` 复用已有 base，只有对应层依赖或基础镜像输入变化才重建；升级脚本必须用接近 `acr-build-push` 的摘要格式输出 version、current、assets、images、runtime 和最终访问地址，并在 `~/xiaozhi-data/.env` 记录 `XIAOZHI_DEPLOY_VERSION`；服务端 Docker 镜像支持 `lite` 和 `funasr` 两种安装 profile：lite profile 使用 `requirements-docker.txt`，不安装 torch / torchaudio / funasr，也不打包 SenseVoiceSmall；FunASR profile 在 lite 依赖上追加 `requirements-docker-funasr-extra.txt`，安装 CPU 版 torch / torchaudio / funasr，并在 `xiaozhi-server` 镜像构建阶段优先复用构建机本地 SenseVoiceSmall `model.pt`，本地不存在时才从配置的 URL 下载，从而支持 FunASR 本地 CPU 识别；`.dockerignore` 必须排除本地 `model.pt`，避免模型同时走普通源码上下文和 FunASR 专用 build context；生产安装阶段允许 ASR、VAD、LLM、TTS、VLLM、Memory、Intent 等模型全部暂时为空，管理员后续在智控台按实际需要配置模型。
关键文件：
  - Dockerfile-server
  - Dockerfile-server-base
  - Dockerfile-server-base-lite
  - Dockerfile-server-base-funasr
  - Dockerfile-manager-api
  - Dockerfile-manager-web
  - scripts/deploy
  - scripts/upgrade
  - scripts/acr-build-push
  - scripts/acr-mirror-runtime-images
  - scripts/acr-deploy-pull
  - scripts/acr-login
  - scripts/acr-build-push-lite
  - scripts/acr-build-push-funasr
  - scripts/docker-build-private.sh
  - scripts/install-private-docker.sh
  - scripts/upgrade-git-docker.sh
  - scripts/README.md
  - deploy/production/
  - main/xiaozhi-server/docker-compose_all.yml
  - main/xiaozhi-server/docker-compose.yml
  - main/xiaozhi-server/requirements-docker.txt
  - main/xiaozhi-server/requirements-docker-funasr.txt
  - main/xiaozhi-server/requirements-docker-funasr-extra.txt
  - main/xiaozhi-server/config.yaml
  - main/xiaozhi-server/core/websocket_server.py
  - main/xiaozhi-server/core/connection.py
  - main/xiaozhi-server/core/utils/modules_initialize.py
  - main/manager-api/src/main/java/xiaozhi/modules/device/controller/OTAMagController.java
  - main/manager-api/src/main/resources/db/changelog/202606171430.sql
  - main/manager-web/src/views/roleConfig.vue
  - docs/deployment/private-docker-release.md
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改 Dockerfile、Docker Compose、安装脚本、默认模型配置、智能体模板初始化、模型配置下发或 WebSocket 初始化流程，必须保留“源码完整进入私有 Git 仓库，但生产服务器只 sparse-checkout `deploy/production` 最小部署资产；生产服务器只拉取阿里云 ACR 中已发布运行镜像，不在生产端源码构建，也不依赖 Docker Hub；发布脚本必须同时推送 `xiaozhi-server:latest`、`xiaozhi-manager-api:latest`、`xiaozhi-manager-web:latest` 和 Git 短哈希标签；生产日常 `./install` / `./upgrade` 默认拉取 `latest`，短哈希 `VERSION=<sha>` 只用于固定版本部署或回滚；`mysql:8.4`、`redis:8.0` 作为运行时镜像同步到 ACR；服务端 base 必须保留 common/lite/funasr 三层缓存、`base-<依赖哈希>` 缓存标签和 `BUILD_BASE=auto/false/true` 控制；管理端必须保留 manager-api、manager-web 两个拆分镜像和 `api-/web-<内容哈希>` 缓存标签；服务端镜像必须保留 `SERVER_INSTALL_PROFILE=lite|funasr`、`scripts/acr-build-push-lite`、`scripts/acr-build-push-funasr`、`requirements-docker.txt` 轻量依赖和 `requirements-docker-funasr-extra.txt` 本地 FunASR 追加依赖这两条清晰入口；生产用户只需要记住 `~/work/xiaozhi-deploy` 部署资产目录、`~/xiaozhi-data` 数据目录、`deploy/production/install` 首次部署和 `deploy/production/upgrade` 后续升级；安装脚本主体必须按部署用户运行，运行数据、生成的 compose、`.env`、私有配置、数据库、上传文件默认放在 `~/xiaozhi-data`；容器 bind mount 的 UID/GID 权限修正必须通过一次性 Docker 容器执行，不直接把整个宿主机脚本切到 root；如果当前用户不能直接访问 Docker daemon，脚本使用 `sudo docker`，此时 ACR 凭据必须来自 `sudo docker login` 的 root Docker config；升级输出必须保持摘要式，能看出本次目标版本、上次部署版本、部署资产 commit、最终运行镜像、数据目录、Docker 调用方式和访问地址；生产服务器不执行 pip install、不下载源码依赖、不手工安装 FunASR；FunASR CPU 运行依赖只在 FunASR profile 中由构建机写入 ACR 镜像，SenseVoiceSmall model.pt 优先从构建机本地文件复制进镜像，本地不存在时才下载，且 `.dockerignore` 必须避免普通源码上下文重复传输模型；安装脚本必须在 manager-api 启动后从 MySQL 容器读取 `sys_params.server.secret`，自动写入 `~/xiaozhi-data/data/.config.yaml` 并重启 xiaozhi-server，不要求用户手工复制密钥；Python 容器以 999:999 非 root 用户运行，`~/xiaozhi-data/data` bind mount 必须允许该用户读写，`.config.yaml` 应由 999:999 持有且 0600；manager-api 容器必须允许写入 `uploadfile` 和 `music` bind mount，确保 OTA 固件和音乐上传可落盘；OTA 下载路径校验必须允许 `firmware` 和 `uploadfile` 两个根目录，同时继续阻止路径穿越；默认资源预算必须给 Python 容器至少 2G 内存并允许通过 `~/xiaozhi-data/.env` 覆盖；所有模型字段允许为空；模型缺失时服务可启动但对应能力不可用并记录清晰提示；manager-api 即使运行在 dev profile，CORS 也必须读取 `CORS_ORIGINS` 环境变量，生产 `~/xiaozhi-data/.env` 的 `XIAOZHI_CORS_ORIGINS` 必须包含浏览器实际访问 Origin，例如 `http://公网IP:8002`，否则登录/注册等浏览器 POST 会在进入业务代码前被 Spring 拦截为 HTTP 403”的约定。
```

## 18. 标准模型存储与运行时契约

```text
定制模块：manager-api 模型配置接口、manager-web 模型配置弹窗、xiaozhi-server 模型运行时初始化
改动原因：模型管理统一为标准存储和标准运行时契约，清晰区分模型配置 ID、后台展示名、上游真实模型名、适配器编码、协议、普通参数和密钥参数。`ai_model_config` 保存通用字段，`ai_model_config_param` 保存普通 provider 参数，`ai_model_config_secret` 保存密钥参数；manager-api 下发 `selected_module + model_config`，xiaozhi-server 只把 `model_config.<modelId>.typed_config` 作为 provider 初始化来源。
关键文件：
  - main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelConfigServiceImpl.java
  - main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelRuntimeAssembler.java
  - main/manager-api/src/main/java/xiaozhi/modules/model/dto/ModelConfigBodyDTO.java
  - main/manager-api/src/main/java/xiaozhi/modules/model/dto/ModelConfigDTO.java
  - main/manager-api/src/main/resources/db/changelog/202606221645.sql
  - main/manager-api/src/main/java/xiaozhi/modules/config/service/impl/ConfigServiceImpl.java
  - main/manager-web/src/components/ModelEditDialog.vue
  - main/manager-web/src/views/ModelConfig.vue
  - docs/models/model-config-catalog.md
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改模型配置查询、编辑、列表 DTO 转换、模型配置弹窗、配置下发或 Python 初始化流程，必须保留标准模型存储边界：新增/编辑接口提交 `adapterCode`、`protocol`、`upstreamModelId`、`endpointUrl`、`params`、`secrets`；后端保存到通用字段、参数表和密钥表；前端密钥只回显脱敏值，未修改的脱敏值保存时保留原密钥；运行时只下发 `selected_module + model_config`。
```

## 19. 管理后台模型批量性能测试

```text
定制模块：manager-web 模型批量性能测试入口、manager-api 异步任务与结果入库、xiaozhi-server benchmark HTTP 接口和 runner
改动原因：智控台需要在同一页面勾选多个模型并发起批量性能测试，用真实 provider 执行 LLM、ASR、TTS 等模型调用，记录成功率、延迟分位数、样本详情和未实测原因，避免只凭字段校验、WebSocket 握手成功或 0ms 结果误判模型可用性。LLM/SLM 测试需要按业务样本集选择主对话模型，每个 case 必须是真实多轮对话，候选模型逐轮回复并累积上下文，同一轮完整样本再打包给已有 LLM/SLM 模型配置作为 Judge 批量评分，减少逐条评审造成的割裂和慢请求；Judge 不单独维护 API Key 或环境变量，必须复用标准模型配置和已有 provider。ASR 测试必须覆盖所有语音识别供应器，不是豆包流式 ASR 的单点特例；所有 ASR 单模型测试和批量测试都必须上传或流式发送真实音频，并以非空识别文本作为通过依据。
关键文件：
  - main/xiaozhi-server/core/http_server.py
  - main/xiaozhi-server/core/api/benchmark_handler.py
  - main/xiaozhi-server/core/benchmark/
  - main/manager-api/src/main/java/xiaozhi/modules/model/controller/ModelController.java
  - main/manager-api/src/main/java/xiaozhi/modules/model/service/ModelBenchmarkService.java
  - main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelBenchmarkServiceImpl.java
  - main/manager-api/src/main/resources/db/changelog/202606171520.sql
  - main/manager-web/src/views/ModelConfig.vue
  - docs/models/performance_tester.md
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改 HTTP 服务路由、模型测试接口、模型配置页面、模型测试 DTO 或数据库迁移，必须保留 `xiaozhi-server` 的 `/benchmark/models` 和 `/benchmark/health` 路由、`core.benchmark.runner.run_benchmark` 真实 provider 执行链路、manager-api 异步任务轮询与结果入库、样本级 measured/latency/benchmark_level 口径、历史任务删除时级联清理结果和样本、Judge 复用已有 LLM/SLM 模型配置且不单独维护 API Key 的边界，以及 `scripts/dev doctor` 对 `main/xiaozhi-server/data/.config.yaml` 必需化检查。LLM/SLM 的 Judge 应按同一模型同一轮多轮样本集批量评分，并在明细中保留 case_id、完整 transcript、RAG/记忆上下文、assistant 输出、judge_mode 和安全 P0/P1 结果；`REAL_INFERENCE` 才能进入延时统计，`CONNECTIVITY`、`CONFIG_ONLY`、`UNSUPPORTED` 不能显示为 0ms；普通单模型测试也不能把单纯字段校验当成可启用的真实通过。官方同步时可以接受模型配置中的空 `selected_module` 和轻量 Docker/模型可空部署口径，但不能删除批量性能测试路由、runner、任务结果表和使用指南。
ASR 专项规则：所有语音识别模型的“测试”都必须执行真实音频识别流程，包含 OpenAI/Groq/Qwen3 这类非流式上传模型、豆包/阿里云/讯飞这类流式模型，以及本地或自部署 ASR。测试详情必须能看到测试音频信息、识别文本和耗时；不能把初始化握手、接口连通、鉴权成功或必填字段完整显示为“已通过”。暂未实现真实音频测试的 ASR 供应器只能显示未通过或未实测，不能自动启用。ASR 单模型测试默认统一使用 `main/xiaozhi-server/config/assets/wakeup_words_short.wav`，不能因为 provider 实现路径或目录遍历顺序不同而使用不同音频；标准音频缺失时才允许降级到其他内置 wav。
```

## 20. 模型配置智能助手

```text
定制模块：manager-api 模型配置助手、manager-web 模型配置页右侧助手
改动原因：模型配置流程中，用户需要让大模型理解厂商文档、归纳必填字段、判断当前适配器是否匹配，并生成可填入表单的配置草案。但大模型不能替代真实 provider 适配器，例如“讯飞流式语音识别”不等于“讯飞实时语音转写大模型”。助手必须复用后台已经配置并测试通过的 LLM 模型，不单独通过环境变量保存 DeepSeek 等密钥。没有匹配 adapter 时，助手不能只停留在口头说明，应返回接入级自动化草案，包括协议说明、实现任务清单、建议代码/数据库迁移/测试产物；在开发环境中允许用户把这些产物写入仓库工作区，形成可审查 diff，再由开发流程审核后真正接入。
关键文件：
  - main/manager-api/src/main/java/xiaozhi/modules/model/controller/ModelController.java
  - main/manager-api/src/main/java/xiaozhi/modules/model/service/ModelAssistantService.java
  - main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelAssistantServiceImpl.java
  - main/manager-api/src/main/resources/db/changelog/202606221130.sql
  - main/manager-web/src/components/ModelConfigAssistant.vue
  - main/manager-web/src/views/ModelConfig.vue
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改模型配置页、模型配置接口、LLM 配置表或 provider/catalog 结构，必须保留以下约定：助手设置只保存已配置 LLM 的 modelId；base_url、api_key、model_name 等凭据来自标准模型配置；发送给助手的上下文必须脱敏；助手可以生成配置草案、协议接入草案、实现任务和自动化产物草案，并在用户点击后把产物写入工作区；写入必须限制在模型接入相关白名单路径，默认不覆盖已有文件，禁止绝对路径和路径越界；助手不得直接保存模型配置、启用模型、执行生产数据库变更或标记测试通过；协议不匹配时必须返回未适配，不能把同厂商不同协议的 adapter 当作可用；真正接入新协议必须经过代码审核、数据库迁移审查和真实模型测试。
```

## 21. 长期记忆维护基础设施

```text
定制模块：manager-api 长期记忆事实层、manager-web 记忆管理页、xiaozhi-server 记忆事件上报和查询注入
改动原因：原有 summaryMemory、mem_local_short、PowerMem 等能力偏运行时摘要或 provider 接入，缺少统一的长期维护底座。现在新增 source_event -> candidate -> fact -> revision/profile/tool_behavior/tombstone 的 MySQL 首期事实层，支持显式记住/忘记、管理端查看编辑删除归档、忘记名单、审计轨迹和运行时记忆上下文查询。Memory 模型配置可通过 `maintenance_llm` 引用模型管理里已经配置和测试的 LLM，例如 DeepSeek/OpenAI 兼容模型，用于把普通用户发言抽取为待确认候选记忆；Memory 配置不保存 API Key、Base URL 或上游模型名。Python 在聊天记录上报时同步写入原始记忆事件，LLM 前把 manager-api 长期记忆和现有 Memory provider 结果合并注入，并为记忆查询设置超时，避免拖住语音主链路。新增 `managed_memory` 作为“智控台治理记忆”模型配置入口，吸收 PowerMem 的长期检索、画像和经验沉淀方向，但把状态、审计、删除和权限收束在 manager-api，而不是继续增加 Python provider 私有存储。
关键文件：
  - main/manager-api/src/main/java/xiaozhi/modules/memory/
  - main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelCatalogServiceImpl.java
  - main/manager-api/src/main/resources/db/changelog/202606291520.sql
  - main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml
  - main/manager-web/src/views/MemoryManagement.vue
  - main/manager-web/src/apis/module/memory.js
  - main/manager-web/src/components/DeviceItem.vue
  - main/xiaozhi-server/config/manage_api_client.py
  - main/xiaozhi-server/core/handle/reportHandle.py
  - main/xiaozhi-server/core/connection.py
  - docs/architecture/long-term-memory-maintenance.md
  - docs/models/memory-and-knowledge-governance.md
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改 Memory provider、聊天记录上报、ConfigController、AgentController、Shiro config、角色首页、模型配置目录、知识库切片配置或数据库迁移，必须保留长期记忆事实层、`managed_memory` 入口和 `/config/memory/**` server-secret 入口；运行时查询失败或超时时必须降级为空记忆，不能阻塞语音回复；`nomem` 模式不得注入长期记忆，且未选择治理型记忆模式时前端不得自动弹出治理记忆专属字段；`maintenance_llm` 只能引用已配置的 LLM/SLM modelId，不能把 DeepSeek 等模型密钥复制到 Memory 配置或记忆表；知识库 LLM 辅助必须通过模型管理中的模型列表选择 `parser_config.llm_model_id`，不得在知识库页面重复保存 API Key、Base URL 或上游模型名；维护模型只能创建 `pending` 候选，不得绕过管理端确认直接写 active fact；管理端删除/归档/确认/拒绝必须写 revision 审计；显式忘记必须写 tombstone 并阻止对应内容继续被主动使用；删除 `summary_memory` 前必须先完成 Java、Vue、Python、文档和迁移的完整兼容替代。
```

## 22. 设备对话调试已移除

```text
定制模块：manager-web 设备对话调试、Otto舞蹈调试、设备主题生成、manager-api 设备调试接口、xiaozhi-server 虚拟调试连接
改动原因：2026-07-10 起不再保留管理端设备调试能力。设备管理页不再提供对话调试、Otto 舞蹈调试或设备主题生成入口；manager-web 不再发布 `/generator` 静态生成器；manager-api 不再暴露 `/device/debug/**`、`/device/tools/**`、`/device/otto-debug/**` 调试接口；xiaozhi-server 不再保留 `manager-debug-*` 虚拟调试连接特殊注册逻辑。真实设备 WebSocket、OTA、设备绑定、设备互呼、Device MCP/IoT 运行时能力仍按业务链路保留。
关键文件：
  - main/manager-web/src/views/DeviceManagement.vue
  - main/manager-web/src/i18n/zh_CN.js
  - main/xiaozhi-server/core/device_runtime_registry.py
  - main/manager-api/src/main/java/xiaozhi/modules/device/service/impl/DeviceServiceImpl.java
  - main/manager-api/src/main/java/xiaozhi/modules/device/controller/DeviceController.java
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方重新引入管理端设备调试、虚拟 WebSocket 调试连接、Otto 调试工作台或设备工具调试接口，默认不合并这些能力；如业务重新需要，必须重新做权限、资源占用、接口暴露和真实设备连接隔离审查。
```

## 23. 登录验证码一次性消费与防重复提交

```text
定制模块：manager-web 登录页、manager-api 登录验证码链路
改动原因：登录接口会把图形验证码和密码拼接后通过 SM2 加密提交，manager-api 解密后用 captchaId 校验验证码并立即删除缓存。验证码是一次性的；如果前端允许按钮点击、回车或网络重试在同一个 captchaId 上重复提交，第一次请求会消费验证码，后续重复请求即使用户输入正确也会报验证码错误。登录页必须在请求完成前加提交锁，失败后刷新验证码并解锁，成功后跳转，不得无条件定时刷新或重复提交同一个 captchaId。登录、注册、找回密码等携带一次性验证码的接口遇到 HTTP 401/403 或非 200 响应时必须按本次失败处理，不能通过 `reAjaxFun` 自动重放同一份加密请求体。
关键文件：
  - main/manager-web/src/views/login.vue
  - main/manager-web/src/views/auth.scss
  - main/manager-api/src/main/java/xiaozhi/common/utils/Sm2DecryptUtil.java
  - main/manager-api/src/main/java/xiaozhi/modules/security/service/impl/CaptchaServiceImpl.java
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改登录页、验证码接口、SM2 加密或 CaptchaService，必须保留验证码一次性消费边界和前端防重复提交锁；登录失败后需要刷新验证码，成功后不得继续发起第二次同 captchaId 的登录请求；`user.login()` 的 networkFail 分支不得自动重试同一份 loginForm，必须回调登录页解锁和刷新验证码；不要把验证码错误简单归因于用户输入错误，需同时检查 captchaId 是否被重复消费。
```

## 24. manager-mobile WSL + Windows 微信开发者工具联调

```text
定制模块：manager-mobile 微信小程序本地开发流程
改动原因：项目源码运行在 WSL 仓库中，但微信开发者工具运行在 Windows 中，并导入 D 盘目录 `D:\codexws\xiaozhi-mp-weixin`。后续开发不能直接修改 D 盘编译产物，也不能把微信开发者工具导入目录误认为源码目录。标准流程是先在 WSL 的 `main/manager-mobile` 中构建 `dist/build/mp-weixin`，再同步到 `/mnt/d/codexws/xiaozhi-mp-weixin`。
关键文件：
  - main/manager-mobile/README.md
  - main/manager-mobile/manifest.config.ts
  - main/manager-mobile/env/.env.development
  - main/manager-mobile/env/.env.production
  - main/manager-mobile/env/.env.test
  - D:\codexws\xiaozhi-mp-weixin (Windows 编译产物目录，不属于 Git 源码)
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改 manager-mobile 构建脚本、manifest、微信小程序配置或 README，必须保留 WSL 源码目录与 Windows 微信开发者工具导入目录分离的约定；刷新 Windows 侧本地调试小程序时使用 `pnpm build:mp:local` 后执行 `rsync -a --delete --exclude='project.private.config.json' dist/build/mp-weixin/ /mnt/d/codexws/xiaozhi-mp-weixin/`，该脚本必须生成 `urlCheck=false` 以允许本地 `http://127.0.0.1:8002/xiaozhi` 调试；正式上传审核前才使用 `pnpm build:mp`，并必须使用 HTTPS 合法域名和 `urlCheck=true`；不得直接编辑 D 盘产物目录中的业务代码；不得覆盖 `project.private.config.json`。
```

## 25. 真机语音链路低延迟、ASR 长尾与连接日志安全

```text
定制模块：xiaozhi-server 豆包录音 ASR、唤醒首轮收音、WebSocket 请求头日志、运行时设备注册表
改动原因：2026-07-11 ESP32 真机与腾讯云日志联调确认，正常轮 ASR 到 LLM 请求约 121.5ms、LLM 首响应约 114.6ms、ASR 到整句 TTS 音频生成约 1.321s；实际严重卡顿来自豆包录音 ASR 约 60 秒查询超时、约 15 秒 HTTPS 读超时，以及唤醒后固定丢弃 2 秒上行音频和默认 2.4-2.7 秒欢迎语。豆包 provider 原先在 async 方法中调用 requests.post 和 time.sleep，会阻塞事件循环并放大音频队列积压。连接日志还可能输出原始 Authorization，旧连接晚注销也必须保证不能删除重连后新对象的注册映射。
关键行为：
  - 豆包录音 ASR 使用 aiohttp 异步提交/查询；http_timeout_seconds 默认10秒、query_interval_seconds 默认0.2秒、query_timeout_seconds 默认15秒。
  - 豆包流式 ASR 自动/实时模式由本地 Silero VAD 判停：发送当前静音尾帧后只发送一次 LAST，并继续等待最终 definite 结果。
  - 豆包流式 ASR 使用分阶段预算：init_timeout_seconds 默认10秒、response_timeout_seconds 默认15秒、final_response_timeout_seconds 默认5秒；最终结果预算从 LAST 发出时开始计算。
  - 新会话回放末尾最多10帧缓存时，当前帧若已在缓存中不得重复发送；自动模式的非空最终短词只要求存在音频帧，不再使用大于15帧的门槛。
  - 豆包流式 ASR 结果任务必须绑定创建时的 WebSocket 和停止事件；旧任务只能关闭自己的连接，不能清空或复位已经重连的新会话状态。
  - wakeup_vad_ignore_ms 默认0；无AEC且存在欢迎语回采时可配置500-2000ms，2000ms等价于旧兼容行为。
  - enable_greeting 默认false，需要欢迎语时由私有配置显式覆盖。
  - 唤醒词缓存仅在开关开启且文本命中后等待TTS，普通文本不再被额外阻塞最多3秒。
  - SeedTTS2 正常结束必须冲刷不足60ms的Opus尾帧，取消、失败或异常断链必须重置编码器，禁止残留样本跨会话。
  - 请求头日志按键名屏蔽 Authorization、Proxy-Authorization、Cookie、Set-Cookie、token、api-key、apikey、secret。
  - unregister_connection 只删除仍指向当前关闭对象的键，旧连接不能误删新连接。
关键文件：
  - main/xiaozhi-server/config.yaml
  - main/xiaozhi-server/core/providers/asr/doubao_recording.py
  - main/xiaozhi-server/core/providers/asr/doubao_stream.py
  - main/xiaozhi-server/core/providers/tts/huoshan_double_stream.py
  - main/xiaozhi-server/core/handle/wakeupHandle.py
  - main/xiaozhi-server/core/handle/helloHandle.py
  - main/xiaozhi-server/core/handle/receiveAudioHandle.py
  - main/xiaozhi-server/core/handle/textHandler/listenMessageHandler.py
  - main/xiaozhi-server/core/connection.py
  - main/xiaozhi-server/core/device_runtime_registry.py
  - main/xiaozhi-server/tests/test_doubao_recording_asr.py
  - main/xiaozhi-server/tests/test_doubao_stream_asr.py
  - main/xiaozhi-server/tests/test_huoshan_double_stream_tts_config.py
  - main/xiaozhi-server/tests/test_wakeup_vad_ignore.py
  - main/xiaozhi-server/tests/test_connection_log_sanitization.py
  - main/xiaozhi-server/tests/test_device_runtime_registry.py
  - docs/models/voice-pipeline-latency-analysis.md
  - docs/architecture/device-interaction-chain.md
是否允许被官方覆盖：否
本次发布状态：只修改并验证源码；未构建镜像、未部署、未重启腾讯云生产服务。用户手动发布后才进行真实服务端回归。
真实外部回归：豆包录音ASR 4/4、流式ASR修复前后5/5、SeedTTS2连续5会话、DeepSeek v4-pro/v4-flash原生与仓库provider均成功；当前provider完整ASR→LLM→TTS链路连续3/3成功，平均约3.753秒。真实凭据只用于进程内测试且未落盘；测试完成后应轮换在会话或截图中暴露过的凭据。
官方同步时的处理规则：如果官方修改豆包录音/流式 ASR、SeedTTS2、唤醒词处理、欢迎语、连接日志或设备注册表，必须保留异步 HTTP 与总预算超时、流式 WebSocket 分阶段等待保护、本地 VAD 判停发送唯一 LAST、缓存首帧去重、短词识别和按会话身份清理、Opus尾帧冲刷、默认零毫秒首轮音频丢弃、欢迎语显式开启、按请求头键名脱敏和对象身份注销校验。不得重新在 async provider 中引入 requests/time.sleep，不得把 Authorization、token、API key、Cookie 或 secret 写入日志；调整 ASR 超时、TTS编码边界和唤醒回声窗口时必须同步更新默认配置、专项测试、语音延迟文档，并在真实设备上验证首句不丢、短词可识别、无多句聚合、连续TTS无串音和重连映射稳定。
```

## 26. 外置声纹服务、智能体隔离与五条上限

```text
定制模块：manager-api 声纹远程客户端与业务约束、xiaozhi-server 实时声纹 provider、Web/Mobile 声纹管理页
改动原因：声纹模型独立部署在另一台服务器，voiceprint-api 0.1.0 起要求必填 user_id 并按用户隔离。小智侧必须从可信智能体绑定关系派生 user_id，不能信任浏览器或设备传入；每个智能体最多绑定 5 个声纹；API Token 必须从 URL 查询参数拆出，只通过 Bearer Header 传输。历史声纹在远程服务中属于 default 作用域，需要保留兼容并允许重新录音后迁移到 agentId 作用域。实时识别假设一句话只有一个说话人，只累计前 N 秒有效人声，默认 1 秒，可由参数管理 `server.voiceprint_sample_seconds` 调整，达到时长立即与 ASR 并行预取，短句跳过、长句后段不再上传，句末最多追加等待 300ms；没有绑定声纹时必须零调用。可信 `speaker_id` 继续进入聊天记录和长期记忆事件，新事实按人物关联，运行时只召回当前人物专属事实与共享事实；聊天记录和记忆图谱必须展示人物归属。官方上游 2026-07-13 的声纹对话修复同时表明，身份不应在每轮 system 和用户消息中重复注入，否则模型会机械地反复称呼名字。
关键文件：
  - main/manager-api/src/main/java/xiaozhi/modules/agent/voiceprint/VoiceprintApiClient.java
  - main/manager-api/src/main/java/xiaozhi/modules/agent/service/impl/AgentVoicePrintServiceImpl.java
  - main/manager-api/src/main/java/xiaozhi/modules/config/service/impl/ConfigServiceImpl.java
  - main/manager-api/src/main/resources/db/changelog/202607131430.sql
  - main/xiaozhi-server/core/utils/voiceprint_provider.py
  - main/xiaozhi-server/core/providers/asr/base.py
  - main/xiaozhi-server/core/providers/asr/doubao_stream.py
  - main/xiaozhi-server/core/handle/receiveAudioHandle.py
  - main/xiaozhi-server/core/connection.py
  - main/xiaozhi-server/core/utils/dialogue.py
  - main/xiaozhi-server/core/handle/reportHandle.py
  - main/manager-api/src/main/java/xiaozhi/modules/memory/utils/MemorySpeakerUtils.java
  - main/manager-web/src/components/ChatHistoryDialog.vue
  - main/manager-web/src/views/memory/
  - main/manager-web/src/views/VoicePrint.vue
  - main/manager-mobile/src/pages/voiceprint/index.vue
  - docs/models/voiceprint-integration.md
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改声纹注册/识别/删除、配置下发、音频状态重置、ASR并行识别、聊天记录展示、长期记忆事件/召回、对话身份注入或声纹页面，必须保留 `user_id=agentId` 的可信服务端派生、每智能体最多5条的后端与远程双重校验、`remote_user_id=default` 历史兼容、Token仅通过Bearer Header传输、私有/回环/链路本地目标拦截、结构化 speakers 配置、“前 N 秒有效人声达到后立即且每句只请求一次、不足 N 秒跳过、采样时长默认1秒且可由 `server.voiceprint_sample_seconds` 调整、句末最多等待300ms、无绑定声纹零调用”、可信 `speaker_id` 贯穿聊天记录与记忆、人物专属记忆不跨声纹召回，以及“每个已识别身份只在本会话首次进入system和JSON用户消息”的行为。前端上限提示不能替代后端校验；删除远程失败时不得静默留下占用额度的孤儿声纹；日志不得输出Token、Authorization或带密钥URL。
```

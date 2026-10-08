---
status: supporting
owner: architecture
last_verified: 2026-10-08
source_paths:
  - main/manager-api/src/main/java/xiaozhi/modules
  - main/manager-web/src
  - main/xiaozhi-server/core
---

# 本地定制记录

本文档记录本项目相对官方 `xiaozhi-esp32-server` 的长期本地定制。同步官方 `upstream` 前必须先阅读本文档，避免误覆盖本地业务能力。

## 1. 功能插件架构与声明式互动模式

```text
定制模块：xiaozhi-server 服务端插件执行器
改动原因：把天气、新闻、音乐播放、HomeAssistant 和讲段子、热梗、成语接龙、你说我猜等能力统一为 Function Plugin 顶层模型；声明式互动玩法不需要为每个玩法新增 Python 插件文件。
关键文件：
  - main/xiaozhi-server/core/providers/tools/server_plugins/plugin_executor.py
  - docs/architecture/runtime-flows.md
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
  - docs/development/local-dev-workflow.md
  - scripts/ui-audit-wsl-chrome
是否允许被官方覆盖：否
官方同步时的处理规则：官方部署文档更新时可以补充本文档，但不要用部署流程替换本地源码开发流程。WSL Web UI 自检继续优先复用 Windows 已安装 Chrome，保留 2048、1366、1024 三档检查、CDP 控制台与尺寸读取、国内网络下禁止无必要浏览器下载、临时审查入口和截图必须清理的约定。
```

## 3. xiaozhi-server 本地后台启动稳定性

```text
定制模块：xiaozhi-server 本地启动入口与开发启动脚本
改动原因：本地使用 nohup/后台方式启动 xiaozhi-server 时，app.py 原先无条件创建 stdin 监听任务；后台进程 stdin 不可交互，可能导致服务启动后立即退出。旧开发脚本原先直接写 `.run/` 和 `logs/dev/`，容易形成第二套运行状态；现在 `scripts/dev` 是唯一开发入口，旧 `scripts/start-dev.sh` 只做参数兼容转发。
关键文件：
  - main/xiaozhi-server/app.py
  - scripts/dev
  - scripts/start-dev.sh
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
  - docs/development/local-dev-workflow.md
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
官方同步时的处理规则：如果官方修改聊天流式输出、function call、TTS 入队、打断处理、意图缓存、设备端 MCP 或 Function Plugin 预设，必须保留以下约定：句首口头禅在进入 TTS 和历史前清理；工具结果绑定本轮 sentence_id 和 abort_generation；REQLLM 工具结果二次生成允许继续工具链，携带 tools 时的普通 content 按官方链路即时进入 TTS；声明式互动插件必须强制 user_message 参数并传递 context；多工具合并必须读取 ActionResponse.result/response；设备 MCP 发送失败必须立即返回，连接关闭时取消等待中的 MCP 调用，音量/亮度等控制类 MCP 使用短超时并直接回复；成语接龙、你说我猜、小智想词用户猜等纯语言游戏不得再由服务端状态机强制路由或注入为 tools，旧 provider_code 必须保留在 `GameSessionManager.FREEFORM_GAME_TOOLS` 并由 `ServerPluginExecutor` 跳过；当前智能体勾选对应功能插件时，只允许把插件里的 `instructions` / `opening_message` 作为临时软提示词注入主 LLM。
```

## 6. 云端运行架构与提示词注入文档

```text
定制模块：项目工程文档
改动原因：把“小智云端服务器分析报告.docx”的调研结论整理为仓库内长期文档，补齐后台配置下发、Python 实时链路、提示词注入、工具 schema 注入、MCP 三类链路和状态机游戏规则的工程说明。
关键文件：
  - docs/architecture/project-architecture.md
  - docs/architecture/runtime-flows.md
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
  - docs/standards/development-specification.md
  - docs/standards/upstream-sync-policy.md
  - docs/deployment/private-docker-release.md
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
官方同步时的处理规则：如果官方修改 get_weather 插件，必须保留以下约定：和风接口异常、认证失败、Host 无效、城市查询失败或页面抓取失败时，先使用 Open-Meteo 兜底查询；`南京市玄武区`、`南京市江宁区` 等区县输入需要规范化为城市候选；工具结果必须以 Action.RESPONSE 直接返回给用户，不能再交给 LLM 把接口错误改写成地点不存在；API Key 通过 `X-QW-Api-Key` 请求头传递，不得进入 URL 查询参数。
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
  - docs/mcp/mcp-operation-guide.md
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改 server 配置下发、SysParamsController、ConfigServiceImpl、config_loader.py 或视觉接口逻辑，必须保留 `server.vision_explain` 作为后台参数；Python 从 manager-api 读取配置时，监听地址 `server.ip/port/http_port/auth_key` 可继续以本地文件为准，但下发给设备的 `server.vision_explain` 默认必须使用后台值，只有本地文件配置了非空、非 `null`、非 `你的...` 的真实值时才允许覆盖。修改参数后需要刷新 Redis `server:config` 和 `sys:params` 缓存或通过后台保存参数触发配置重建，并重启/通知 xiaozhi-server 重新读取配置。
```

## 11. 语义控制中枢与语音操作系统架构

```text
定制模块：xiaozhi-server 语义前置控制链路与声学活动门控
改动原因：把小智从“ASR 后直接交给 LLM/function-call”的聊天机器人链路，演进为“TextNormalizer -> SemanticRouter -> StateManager -> PolicyEngine -> SemanticCenter”的语音控制中枢。退出游戏、游戏续局、低置信度拒识等具有明确状态约束的控制可优先由服务端规则和状态处理；播报打断仍与语义控制解耦，但在 Silero 后增加轻量 RMS、自适应噪声底和 SNR 多帧确认，避免低能量间歇噪声过早触发 AEC Abort 并拖长 ASR 音频段。选中 VAD 模型的显式 typed_config 是实际运行值，`server.vad_*` 管理参数只为缺失或 null 的旧模型字段提供缺省值，不再静默覆盖模型级调参。
关键文件：
  - main/xiaozhi-server/core/audio/speech_activity.py
  - main/xiaozhi-server/core/semantic/
  - main/xiaozhi-server/core/connection.py
  - main/xiaozhi-server/core/handle/receiveAudioHandle.py
  - main/xiaozhi-server/core/providers/tools/unified_tool_handler.py
  - main/xiaozhi-server/tests/test_barge_in_flow.py
  - main/xiaozhi-server/tests/test_semantic_router.py
  - main/xiaozhi-server/tests/test_semantic_policy.py
  - docs/architecture/semantic-control-center.md
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改 startToChat、ConnectionHandler 初始化、Silero VAD、hello features、handle_user_intent、GameSessionManager、UnifiedToolHandler、ToolManager、abortHandle、TTS 入队或 function-call 主链路，必须保留语义控制中枢在旧意图/LLM 之前的前置拦截位置；设备通过 `features.aec=true` 启用句首即时打断，`speech_gate` 在 Silero 概率后使用 RMS/SNR 与多帧状态机确认讲话开始，只有确认后的 `speech_started` 边沿能够停止 TTS，不等待完整 ASR 或额外 LLM；`disabled/shadow/enforce`、约 320ms pre-roll、严格开始/宽松继续、模糊尾帧有界宽限和声纹录入至少两秒静音的契约不得被官方单阈值实现覆盖；豆包流式 ASR 2.0 自动/实时模式必须把 `end_window_size` 交给豆包内部 VAD，并只以 `definite=true` 提交本轮，不能因本地 Silero 设置 `client_voice_stop` 就主动发送 LAST，设备显式 `listen/stop` 仍保留 LAST；该门控仍是声学 VAD，不得扩张成停止词表或独立 LLM 打断分类器；本地 abort 清理输出时必须保留正在形成的用户音频，非 AEC 设备则在完整 ASR 后停止；未命中高置信度语义规则时必须继续兜底到旧 handle_user_intent 和 conn.chat；纯语言游戏不得优先回到 active_game_tool，应交给主 LLM 对话，并只从已启用功能插件读取可编辑软规则；退出游戏不得关闭 websocket；语义模块顶层导入应尽量轻量，避免引入 opus/loguru 等运行依赖导致纯单元测试无法导入。
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
改动原因：本地知识库原先对 md/txt 文档主要按固定字符数硬切，容易把标题、适用问题、正文或一句话切开；召回也只按空格/标点切词，中文整句没有空格时容易查不到。当前按知识库选择 AUTO/DIRECT/LLM_ENHANCED 处理策略，结构化记录保持原子边界，模型增强只用于非结构化正文；parser_config 只保存 llm_model_id 和处理参数，API Key、接口地址和上游模型名由模型管理统一维护。弱服务器不启用向量知识库：上传不生成 embedding，查询不调用向量/重排模型；召回同时匹配 content/question/answer，使用最多 12 个中文 n-gram 候选词，数据库候选默认 200、硬上限 800，再在 Java 内存中做词法评分。知识库高级设置只控制常规/展会模式、关键词拆分级别、最大召回数和最低匹配度；旧 semantic 配置仅兼容读取。2026-07 进一步固定 parserConfig 字符串/DTO 边界，并把本地上传改为“请求内验收并创建 RUNNING 记录、后台处理、数据库持久化进度与失败原因”，页面无需阻塞等待且关闭重进仍能恢复状态。2026-08 参考 Onyx connector 模式增加知识库级可选动态数据源：只有显式绑定来源的本地知识库参与调度，人工知识库保持不变；网页来源以 ETag/Last-Modified/内容哈希增量同步，候选版本切片成功后才在事务内替换正式版本，旧版本禁用保留且不参与召回；同步运行、失败退避和脱敏错误持久化，公网 URL 每次抓取及重定向都必须通过 SSRF 校验。智能体编辑通过显式 hydration 恢复知识库绑定和 queryPolicy，禁止旧 prop、乱序列表或程序化 clearSelection 抹掉选择；知识结果、工具历史和工具描述使用分域预算，且知识工具结果标记为不可信参考数据，不得当作指令。
关键文件：
  - 2026-08：上传原件保存到持久化卷，召回改为用户原问 + 模型改写双路查询、确定性同义扩展和内嵌 Lucene CJK/BM25；结构化切片额外区分主实体名称、价格属性与搭配关系，普通“多少钱/单独点”查询不能再被辅助提及或无关“单独”描述压过直接商品记录。空召回后的禁用工具轮也必须拦截原始/HTML 转义 DSML，禁止协议文本进入字幕或 TTS。
  - 2026-09：本地人工文档提供文本/问答条目的新增、编辑、停用和恢复召回。原文件不改，MySQL 只保留当前条目，不保存旧正文快照、不提供历史查询恢复；保留权限、归属、expectedVersion 与文档锁。已发布历史表通过后续迁移删除，部署前备份且禁止旧版 API 混跑。Lucene 按 contentVersion 惰性重建，失败回退数据库且不回传旧正文。动态来源和 RAGFlow 只读；不提供替换原文件合并人工修改，旧文档无需原件即可编辑。
  - main/manager-api/src/main/java/xiaozhi/modules/knowledge/service/KnowledgeEntryService.java
  - main/manager-api/src/main/java/xiaozhi/modules/knowledge/controller/KnowledgeEntryController.java
  - main/manager-web/src/components/knowledge/content/KnowledgeContentWorkspace.vue
  - 2026-09 内容管理交互：采用独立路由的左右分栏工作区，列表和编辑器分组件，离开和切换条目均保护草稿。移除修改历史、版本对比和历史恢复界面及前后端接口，只保留版本冲突保护。旧 Excel 无可信字段映射时保留完整正文，不能用冒号猜测字段。
  - main/manager-api/src/main/java/xiaozhi/modules/knowledge/rag/impl/LocalKnowledgeAdapter.java
  - main/manager-api/src/main/java/xiaozhi/modules/knowledge/dao/KnowledgeChunkDao.java
  - main/manager-api/src/main/resources/mapper/knowledge/KnowledgeChunkDao.xml
  - main/manager-api/src/main/java/xiaozhi/modules/knowledge/service/impl/KnowledgeBaseServiceImpl.java
  - main/manager-api/src/main/java/xiaozhi/modules/knowledge/rag/impl/local/
  - main/manager-api/src/main/java/xiaozhi/modules/knowledge/service/impl/LocalKnowledgeUploadTaskService.java
  - main/manager-api/src/main/java/xiaozhi/modules/knowledge/source/
  - main/manager-api/src/main/java/xiaozhi/modules/knowledge/controller/KnowledgeSourceController.java
  - main/manager-api/src/main/java/xiaozhi/modules/knowledge/config/KnowledgeUploadTaskConfig.java
  - main/manager-api/src/main/java/xiaozhi/modules/agent/service/impl/KnowledgeToolDescriptionBuilder.java
  - main/manager-api/src/main/resources/db/changelog/202606111650.sql
  - main/manager-api/src/main/resources/db/changelog/202606291030.sql
  - main/manager-api/src/main/resources/db/changelog/202608251930.sql
  - main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml
  - main/manager-web/src/components/KnowledgeBaseDialog.vue
  - main/manager-web/src/components/KnowledgeBaseSelectorDialog.vue
  - main/manager-web/src/components/role/RoleEditDialog.vue
  - main/manager-web/src/utils/roleConfigDraft.js
  - main/manager-web/src/views/KnowledgeBaseManagement.vue
  - main/manager-web/src/views/KnowledgeFileUpload.vue
  - main/manager-web/src/components/knowledge/FileUploadPanel.vue
  - main/manager-web/src/components/knowledge/KnowledgeSourcePanel.vue
  - main/manager-web/src/i18n/zh_CN.js
  - main/manager-mobile/src/pages/knowledge/config.vue
  - main/manager-mobile/src/pages/knowledge/index.vue
  - main/xiaozhi-server/plugins_func/functions/search_from_local_knowledge.py
  - main/xiaozhi-server/core/utils/prompt_budget.py
  - docs/architecture/knowledge-base-chain.md
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改本地知识库、RAG 模型配置、知识库弹窗、文档解析、本地知识库工具、知识库数据库迁移或切片查询 DAO，必须保留知识库级 parser_config 覆盖 RAG_LocalKnowledge 默认配置的约定；LLM_ENHANCED 策略必须通过 llm_model_id 复用模型管理中已启用、测试通过且兼容 OpenAI Chat Completions 的 LLM 配置，知识库配置不得再次要求用户填写真实 API Key；AUTO/DIRECT 必须保持结构化记录原子边界，LLM_ENHANCED 的模型错误必须使上传失败，禁止 quality_degraded 静默降级；旧文档必须重新上传原文件才应用新切片；本地召回禁止生成或查询 embedding，必须同时匹配 content/question/answer，并保留中文自然句 n-gram 检索增强、结构化主实体字段加权、普通价格/单品同义扩展，以及“实体属性查询优先主实体、搭配查询保留关系结果”的确定性重排；检索链路禁止退回全量 selectList 后逐条 contains 的实现，必须保留 SQL LIMIT 候选召回、候选 term 数量上限、最终返回数量上限和 parser_config.retrieval_config 的兼容读取；新增全文索引迁移必须登记到 db.changelog-master.yaml。同步还必须保护以下可靠性契约：parserConfig 原始完整 JSON 优先持久化，本地上传先返回持久化的 RUNNING 记录，再由有界后台任务执行切片并持续更新进度；页面关闭重进可恢复状态，终态明确显示成功或脱敏失败原因，暂存文件必须清理，无进展任务不能永久 RUNNING；零切片或不支持格式明确失败；智能体详情中的普通插件和知识库映射必须在元数据加载后规范化拆分，保存失败保留草稿，关闭重进可恢复模型、知识库和 queryPolicy；程序化恢复选择不能触发空选择覆盖；知识工具说明必须领域中立且有界，动态知识与工具历史预算不能修改参数 schema。
动态数据源同步还必须保持 dataset 级 opt-in：没有来源记录的知识库不得进入调度；抓取地址与每次重定向必须通过 SSRF 校验，禁止 URL 密钥；新版本必须先作为 disabled 候选完成切片，再原子切换正式版本，任何失败都保留旧版并写入同步运行记录。召回 SQL 必须继续过滤 disabled 历史版本。
独立 intent_llm 模式的本地知识库与 RAGFlow 回答必须复用主模型流式 chat，不得退回 replyResult 收齐整篇答案后播报。保留用户问题、成对的工具调用/结果消息、未命中提示及本次回答禁用工具的约束；打断或 generation 失效后不得注入旧检索结果，失效任务不得覆盖新回复的 sentence_id。实现与验证见 core/handle/intentHandler.py、core/connection.py、tests/test_intent_knowledge_streaming.py 和知识库链路文档。
切回 continue_chat 不得单独删除工具结果，必须保留工具调用与结果的配对。LLM 异常已排队错误提示时必须返回已处理，由 TTS LAST 结束同一 generation，禁止任务回调提前终止该兜底；异常排队逻辑放在 core/handle/llmErrorHandle.py，连续对话及错误收尾由 tests/test_intent_knowledge_streaming.py 和 tests/test_llm_error_reply.py 回归验证。
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
  - docs/mcp/mcp-operation-guide.md
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改 MCP endpoint、智能体功能配置、ConfigController、AgentMcpAccessPointController 或工具 schema 下发链路，必须保留 no-code HTTP tool 的完整链路：Web 配置 JSON -> `ai_agent_mcp_no_code_tools` -> `/config/mcp/no-code-tools/{agentId}` -> `no_code_mcp_tools.py` -> MCP Endpoint tools/list/tools/call。该能力必须继续标注为高风险 HTTP 工具桥，生产环境应限制可信用户、保护 `MANAGER_API_SECRET`，并避免访问内网管理端口、云元数据地址和敏感服务。
```

## 16. 服务器音乐播放状态与音频队列边界

```text
定制模块：xiaozhi-server 服务器音乐播放插件
改动原因：`play_music` 在 function_call 模式下原先只异步提交音乐播放任务，同步工具结果只显示“指令已接收”；管理后台聊天记录看不到实际选中的歌曲，容易误判为卡住。选曲提示语（例如“即将为您播放 具体歌曲”）必须作为普通 TTS 文本真实播报，不能只通过 `send_tts_message` 做屏幕状态。另一个问题是火山双流等 TTS provider 收到 `ContentType.FILE` 音乐文件后会先暂存到 `before_stop_play_files`，如果没有 `SentenceType.LAST` 播放边界就不会 flush 到 WebSocket，表现为后台显示已选中歌曲但设备不播放。后续为避免客户歌曲语义被写死在服务端规则里，播放、唱某首歌、随机播放和曲库查询不再由旧意图入口或关键词解析器直接派发，播放选择由主 LLM 或 intent LLM 根据工具描述和曲库上下文决定；播放期间的用户开口统一触发官方式 barge-in。TTS 音频发送不得设置整轮固定播放时长上限；`LAST` 收尾必须按剩余音频帧时长动态等待，网络半开保护应限制单个 WebSocket 音频包发送，不能通过 20 秒总上限清空正常长语音。流式 TTS provider 的字幕文本与聊天记录上报文本必须使用独立生命周期：provider 可在首包后清理字幕，但上报文本必须保留到正常 `LAST` 或 Abort 成功入队。Abort 只能对已经跨过真实音频 `FIRST` 的当前 `sentence_id` 上报，必须先校验播放状态和文本再竞争最终上报权；入队失败时释放该权利并保留文本，避免迟到 `LAST` 被误判为重复上报而永久丢失回答。
关键文件：
  - main/xiaozhi-server/core/semantic/router.py
  - main/xiaozhi-server/core/semantic/policy_engine.py
  - main/xiaozhi-server/core/semantic/semantic_center.py
  - main/xiaozhi-server/core/handle/intentHandler.py
  - main/xiaozhi-server/core/handle/sendAudioHandle.py
  - main/xiaozhi-server/core/handle/reportHandle.py
  - main/xiaozhi-server/core/providers/tts/base.py
  - main/xiaozhi-server/plugins_func/functions/play_music.py
  - main/xiaozhi-server/tests/test_music_intent.py
  - main/xiaozhi-server/tests/test_tts_interrupted_report.py
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改 `handle_user_intent`、语义控制中枢、`play_music`、TTS 队列、`Action.RECORD`、`ContentType.FILE` 或流式 TTS provider，必须保留以下约定：播放、唱某首歌、随机播放、换一首、曲库查询和音乐上下文续播应由大模型根据 `play_music` 工具描述、`<music_library>` 曲库上下文和用户表达调度，不得在旧意图入口里硬编码直接调用播放工具；播放期间的用户开口按统一 AEC barge-in 停止当前输出，不为音乐维护额外停止词或独立裁决器；工具同步结果应使用用户可读的“正在为您播放音乐”，不要只返回“指令已接收”；“此刻为您献上/正在播放/即将为您播放 具体歌曲”这类选曲提示语必须按普通 `ContentType.TEXT` 进入 TTS 播报和 assistant 聊天记录，不得只作为屏幕状态；音乐播放入队必须按 `FIRST/ACTION -> MIDDLE/TEXT -> MIDDLE/FILE -> LAST/ACTION` 发送播放边界，确保提示语播完后再播放文件，并确保火山双流、阿里云流式、讯飞流式、Minimax 等 provider 能把暂存文件音频 flush 到设备；流式 provider 的字幕清理不得删除按 `sentence_id` 保留的聊天记录上报文本，真实音频 `FIRST` 必须按句登记，Abort 与正常 `LAST` 只能在文本和播放状态校验通过后竞争一次最终上报，且只在上报队列接收成功后清理状态，失败时必须释放上报权并保留文本和已构建指标；整轮 TTS 播放不得设置固定总时长上限，`LAST` 收尾应按剩余队列时长动态等待，并以单包发送超时处理 WebSocket 半开；曲库 `.xiaozhi-music-metadata.json` 支持 `aliases` 和 `description`，用于后台配置 ASR 近音、歌曲别名、用户俗称和模型可见背景说明，源码不得写入客户或歌曲专属语义。聊天记录中的 `play_music({...})` 和 `{"result": "正在为您播放音乐"}` 是工具调用审计记录，不应被当作播放失败错误。
```

## 17. ACR 镜像生产部署、模型可空与 CPU FunASR

```text
定制模块：ACR 镜像生产部署、模型默认配置、后台智能体模板、Python 运行时模型初始化
改动原因：源码仍完整推送到私有 Git 仓库，但生产服务器不再拉取完整源码，也不从源码构建业务镜像。唯一生产路线改为本地或构建机先构建 `xiaozhi-server` / `xiaozhi-web` 并推送到阿里云 ACR，同时把 `mysql:8.4` 和 `redis:8.0` 同步到 ACR；生产端只 sparse-checkout `deploy/production` 最小部署资产，再从 ACR 拉取全部运行镜像并启动；服务端 base 镜像仅作为构建缓存，不作为生产运行镜像；base 缓存拆为 `xiaozhi-server-base-common`、`xiaozhi-server-base-lite`、`xiaozhi-server-base-funasr` 三层，FunASR base 在 lite base 上追加 torch / torchaudio / funasr，避免 lite 和 FunASR 互相污染，同时保留公共缓存复用；基础镜像以 `base-<依赖哈希>` 作为依赖缓存标签，默认 `BUILD_BASE=auto` 复用已有 base，只有对应层依赖或基础镜像输入变化才重建；manager-api / manager-web 默认使用 buildx 的 ACR registry cache 跨构建机复用 Maven/npm 中间层，远端 OCI 镜像必须用 imagetools 做无下载元数据检查，manager-web builder 固定使用带 digest 的 Node slim 基础镜像，避免镜像维护后重复拉取完整 Node 镜像；manager-api 的依赖预取与 JAR 打包必须共用具名 Maven BuildKit cache，并为仓库连接、响应与重试设置有限预算，禁止因单个 CDN 节点无响应无限等待或通过清理缓存处理；升级脚本必须用接近 `acr-build-push` 的摘要格式输出 version、current、assets、images、runtime 和最终访问地址，并在 `~/xiaozhi-data/.env` 记录 `XIAOZHI_DEPLOY_VERSION`；Liquibase 必须使用独立数据库连接，不得复用普通业务连接的 30 秒读超时，避免大表迁移超时后无法释放 `DATABASECHANGELOGLOCK`；manager-api 健康检查必须验证 8003 端口真实就绪，升级器应按可配置的 900 秒默认预算等待迁移完成，不能只凭 Java 进程或历史日志判定启动成功；服务端 Docker 镜像支持 `lite` 和 `funasr` 两种安装 profile：lite profile 使用 `requirements-docker.txt`，不安装 torch / torchaudio / funasr，也不打包 SenseVoiceSmall；FunASR profile 在 lite 依赖上追加 `requirements-docker-funasr-extra.txt`，安装 CPU 版 torch / torchaudio / funasr，并在 `xiaozhi-server` 镜像构建阶段优先复用构建机本地 SenseVoiceSmall `model.pt`，本地不存在时才从配置的 URL 下载，从而支持 FunASR 本地 CPU 识别；`.dockerignore` 必须排除本地 `model.pt`，避免模型同时走普通源码上下文和 FunASR 专用 build context；生产安装阶段允许 ASR、VAD、LLM、TTS、VLLM、Memory、Intent 等模型全部暂时为空，管理员后续在智控台按实际需要配置模型。
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
  - main/manager-web/src/views/home.vue
  - main/manager-web/src/components/home/AgentTemplateCenter.vue
  - docs/deployment/private-docker-release.md
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改 Dockerfile、Docker Compose、安装脚本、默认模型配置、智能体模板初始化、模型配置下发或 WebSocket 初始化流程，必须保留“源码完整进入私有 Git 仓库，但生产服务器只 sparse-checkout `deploy/production` 最小部署资产；生产服务器只拉取阿里云 ACR 中已发布运行镜像，不在生产端源码构建，也不依赖 Docker Hub；发布脚本必须同时推送 `xiaozhi-server:latest`、`xiaozhi-manager-api:latest`、`xiaozhi-manager-web:latest` 和 Git 短哈希标签；生产首次引导成功后必须安装并持续原子更新唯一日常入口 `~/xiaozhi-upgrade`，该入口默认拉取 `latest`，短哈希 `VERSION=<sha>` 用于固定版本部署或回滚；`mysql:8.4`、`redis:8.0` 作为运行时镜像同步到 ACR；服务端 base 必须保留 common/lite/funasr 三层缓存、`base-<依赖哈希>` 缓存标签和 `BUILD_BASE=auto/false/true` 控制；管理端必须保留 manager-api、manager-web 两个拆分镜像和 `api-/web-<内容哈希>` 缓存标签；服务端镜像必须保留 `SERVER_INSTALL_PROFILE=lite|funasr`、`scripts/acr-build-push-lite`、`scripts/acr-build-push-funasr`、`requirements-docker.txt` 轻量依赖和 `requirements-docker-funasr-extra.txt` 本地 FunASR 追加依赖这两条清晰入口；生产用户日常只需要记住 `~/xiaozhi-data` 数据目录和 `~/xiaozhi-upgrade` 升级命令，仓库内 `deploy/production/install` 只用于首次引导或故障恢复；安装脚本主体必须按部署用户运行，运行数据、生成的 compose、`.env`、私有配置、数据库、上传文件默认放在 `~/xiaozhi-data`；容器 bind mount 的 UID/GID 权限修正必须通过一次性 Docker 容器执行，不直接把整个宿主机脚本切到 root；如果当前用户不能直接访问 Docker daemon，脚本使用 `sudo docker`，此时 ACR 凭据必须来自 `sudo docker login` 的 root Docker config；升级输出必须保持摘要式，能看出本次目标版本、上次部署版本、部署资产 commit、最终运行镜像、数据目录、Docker 调用方式和访问地址；Liquibase 必须使用独立数据库连接并允许大表迁移超过业务查询的 30 秒读超时，manager-api 健康状态必须来自 8003 端口真实就绪，安装器必须等待当前健康状态且保留可配置启动预算，禁止通过 Java 进程存在、容器 uptime 或历史 `Started` 日志跳过就绪检查；生产服务器不执行 pip install、不下载源码依赖、不手工安装 FunASR；FunASR CPU 运行依赖只在 FunASR profile 中由构建机写入 ACR 镜像，SenseVoiceSmall model.pt 优先从构建机本地文件复制进镜像，本地不存在时才下载，且 `.dockerignore` 必须避免普通源码上下文重复传输模型；安装脚本必须在 manager-api 启动后从 MySQL 容器读取 `sys_params.server.secret`，自动写入 `~/xiaozhi-data/data/.config.yaml` 并重启 xiaozhi-server，不要求用户手工复制密钥；Python 容器以 999:999 非 root 用户运行，`~/xiaozhi-data/data` bind mount 必须允许该用户读写，`.config.yaml` 应由 999:999 持有且 0600；manager-api 容器必须允许写入 `uploadfile` 和 `music` bind mount，确保 OTA 固件和音乐上传可落盘；OTA 下载路径校验必须允许 `firmware` 和 `uploadfile` 两个根目录，同时继续阻止路径穿越；默认资源预算必须给 Python 容器至少 2G 内存并允许通过 `~/xiaozhi-data/.env` 覆盖；所有模型字段允许为空；模型缺失时服务可启动但对应能力不可用并记录清晰提示；生产 Compose 必须显式启用 `SPRING_PROFILES_ACTIVE=prod`，使用生产连接池、关闭 Knife4j 并限制 Tomcat 线程；生产 `~/xiaozhi-data/.env` 的 `XIAOZHI_CORS_ORIGINS` 必须包含浏览器实际访问 Origin，例如 `http://公网IP:8002`，否则登录/注册等浏览器 POST 会在进入业务代码前被 Spring 拦截为 HTTP 403”的约定。
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
  - docs/models/model-management.md
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
  - docs/architecture/model-benchmark-and-assistant.md
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
改动原因：原有 summaryMemory、mem_local_short、PowerMem 等能力偏运行时摘要或 provider 接入，缺少统一的长期维护底座。现在新增 source_event -> candidate -> fact -> revision/profile/tool_behavior/tombstone 的 MySQL 事实层，支持会话结束后的大模型主动记忆、管理端查看编辑删除归档、忘记意图、审计轨迹和运行时记忆上下文查询。`managed_memory` 参考官方 Memory provider：`ConnectionHandler._save_and_close()` 把完整对话交给 provider，provider 再调用 `/config/memory/process-session`，由所选 `maintenance_llm` 自动决定 ADD/UPDATE/ARCHIVE/DELETE/NONE；主对话模型不再暴露 `manage_memory` 工具。单条聊天事件仍只保存原始证据，不逐句调用模型。记忆写入维护不使用固定人物、关键词、条件任务或场景黑白名单替代模型判断，只保留所有权、可信声纹隔离、容量、ID、事务和审计边界；运行时读取对齐上游 mem_local_short：权威配置加载有界事实摘要，对话本地读取，不逐轮 HTTP 召回；SQL 候选最多 200，按可信 speaker_id 区分 shared/private，不调用向量或额外 LLM。旧画像和工具经验继续存储，但不混入缺乏可靠人物归属的运行时摘要。Memory 配置只引用已配置的 `maintenance_llm` modelId，不保存 API Key、Base URL 或上游模型名。智能体页允许持久化选择只保存文字或保存文字+音频，模型切换不得强制覆盖。管理端正式重建继续使用 Redis 持久化异步任务；动态 memory 注入只裁剪 `<memory>` 内容，不能裁剪静态角色与安全规则，并标记记忆内容不是系统指令。
关键文件：
  - main/manager-api/src/main/java/xiaozhi/modules/memory/
  - main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelCatalogServiceImpl.java
  - main/manager-api/src/main/resources/db/changelog/202606291520.sql
  - main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml
  - main/manager-web/src/views/MemoryManagement.vue
  - main/manager-web/src/views/memory/memoryRebuildTaskState.mjs
  - main/manager-web/src/apis/module/memory.js
  - main/manager-web/src/components/DeviceItem.vue
  - main/xiaozhi-server/config/manage_api_client.py
  - main/xiaozhi-server/core/handle/reportHandle.py
  - main/xiaozhi-server/core/connection.py
  - main/xiaozhi-server/core/utils/prompt_budget.py
  - docs/architecture/memory-system-chain.md
  - docs/models/memory-and-knowledge-governance.md
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改 Memory provider、聊天记录上报、ConfigController、AgentController、Shiro config、角色首页、模型配置目录、知识库切片配置或数据库迁移，必须保留长期记忆事实层、`managed_memory` 入口、会话结束后的 `/config/memory/process-session` 主动整理和 `/config/memory/**` server-secret 入口；不得重新把 `manage_memory` 注册给主 LLM。运行时查询失败或超时时必须降级为空记忆，不能阻塞语音回复；`nomem` 模式不得注入长期记忆；单条事件不得逐句调用记忆模型，主动整理只在完整会话结束后执行；写入维护不得加入固定人物/关键词/场景门控、条件任务特殊类型、TTL、衰减或字符串相似度判断；运行时摘要必须保持说话人隔离、200 条候选硬上限、有界字符预算、有限租约、更新/遗忘/声纹撤销后的失效与并发旧响应拦截；不得逐轮回退 HTTP 查询，不得调用向量或召回模型；租约仅限制内存摘要新鲜度，不修改事实存储 TTL；可信 speaker_id 决定 private 归属，未识别单人会话事实保持 shared；`maintenance_llm` 只能引用已配置的 LLM/SLM modelId，不能复制模型密钥；聊天记录文字/音频选择必须尊重智能体持久化值。管理端删除、归档、确认、拒绝和自动写入必须写 revision 审计；删除 `summary_memory` 前必须完成 Java、Vue、Python、文档和迁移的完整兼容替代。正式重建必须继续采用服务端任务与短事务原子替换，不能退回单个 300 秒 HTTP 请求或仅靠浏览器 loading 状态维持互斥。
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
定制模块：xiaozhi-server 豆包录音 ASR、主 LLM function-call 流播、SeedTTS2 会话启动与收尾、唤醒首轮收音、WebSocket 请求头日志、运行时设备注册表
改动原因：2026-07-11 ESP32 真机与腾讯云日志联调确认，正常轮 ASR 到 LLM 请求约 121.5ms、LLM 首响应约 114.6ms、ASR 到整句 TTS 音频生成约 1.321s；实际严重卡顿来自豆包录音 ASR 约 60 秒查询超时、约 15 秒 HTTPS 读超时，以及唤醒后固定丢弃 2 秒上行音频和默认 2.4-2.7 秒欢迎语。豆包 provider 原先在 async 方法中调用 requests.post 和 time.sleep，会阻塞事件循环并放大音频队列积压。2026-07-20 又确认 SeedTTS2 长回复在 `FinishSession` 后 5 秒内未收到结束事件时，异常分支会跳过 `SentenceType.LAST`，设备因收不到 `tts stop` 永久停在 speaking。2026-07-22 进一步确认 function-call 普通文本可能在主 LLM 完整生成后才进入 TTS，旧实现却在逻辑 `FIRST` 到达时立即启动 SeedTTS2 上游会话，导致上游长时间无文本后空闲失效，最终整轮零音频。2026-07-29 的阶段日志又显示首个 LLM 分片约 217ms、完整响应约 1.593s，普通回答整轮缓存浪费约 1.376s 流式窗口。连接日志还可能输出原始 Authorization，旧连接晚注销也必须保证不能删除重连后新对象的注册映射。
关键行为：
  - 豆包录音 ASR 使用 aiohttp 异步提交/查询；http_timeout_seconds 默认10秒、query_interval_seconds 默认0.2秒、query_timeout_seconds 默认15秒。
  - 豆包流式 ASR 自动/实时模式由本地 Silero VAD 判停：发送当前静音尾帧后只发送一次 LAST，并继续等待最终 definite 结果。
  - 豆包流式 ASR 使用分阶段预算：init_timeout_seconds 默认10秒、response_timeout_seconds 默认15秒、final_response_timeout_seconds 默认5秒；最终结果预算从 LAST 发出时开始计算。
  - 新会话回放末尾最多10帧缓存时，当前帧若已在缓存中不得重复发送；自动模式的非空最终短词只要求存在音频帧，不再使用大于15帧的门槛。
  - 豆包流式 ASR 结果任务必须绑定创建时的 WebSocket 和停止事件；旧任务只能关闭自己的连接，不能清空或复位已经重连的新会话状态。
  - wakeup_vad_ignore_ms 默认0；无AEC且存在欢迎语回采时可配置500-2000ms，2000ms等价于旧兼容行为。
  - enable_greeting 默认false，需要欢迎语时由私有配置显式覆盖。
  - 唤醒词缓存仅在开关开启且文本命中后等待TTS，普通文本不再被额外阻塞最多3秒。
  - 唤醒词聊天记录保留设备实际检测文本，不再用内部的“嘿，你好呀”替代；缓存欢迎音成功播放后必须上报实际助手文本和音频，保证后台按 user -> assistant 显示完整唤醒交互。
  - 主 LLM 沿用官方 `direct_answer` 虚拟工具流播：普通 function-call 轮不使用关键词、正则或服务端预设意图选择工具；provider 支持时发送 `tool_choice=required`，由模型在 `direct_answer` 与真实工具之间自主选择，并发送 `parallel_tool_calls=false` 保证单出口。增长中的 `direct_answer.response` 只保留 5 字符 JSON 安全尾，其余稳定增量立即进入 TTS，不再等待自然短句聚合。会议激活后的静默控制轮继续只暴露会议工具并保留 `tool_choice=required`。兼容端点不支持结构化必选或并行工具参数时，分别用 `supports_tool_choice: false`、`supports_parallel_tool_calls: false` 显式降级；后者对 `direct_answer` 在完整响应确认后提交。
  - 本地知识库成功检索但结果为空时，工具结果递归轮临时关闭全部 tools，并注入一次性可信边界：公开历史、文化、科普和故事允许使用模型通用知识回答，企业资料、人员身份和其他私有事实必须说明暂无记录且不能猜测。该边界不写入 Dialogue，不新增关键词路由或独立分类器，下一条用户消息恢复正常工具集合。
  - `direct_answer` 与真实工具通过 `parallel_tool_calls=false` 保持本轮单出口；会议激活轮不启用普通回答流播。播报打断沿用官方 Abort/AEC 链路，不注册模型决定的停止说话工具，不维护停止词表或独立 LLM 打断分类器。
  - 纯 `direct_answer` 成功解析并提交非空 response 与 LAST 后，`ConnectionHandler.chat()` 必须返回 `True`；裸 `return` 会被 `startToChat.on_chat_done()` 解释为 `llm_no_spoken_result`，提前失败当前 generation 并清空尚未发送的 TTS 音频。空 response 才允许返回空结果，且不得伪造 LAST。
  - 流式 TTS 的音频增量不得被字幕等待阻塞；但设备 `FIRST` 字幕必须来自按 `sentence_id` 保存的完整回答，禁止把首个 LLM 字符或短语当作正式字幕。正常 `LAST` 上报聊天记录时继续优先使用完整回答，并把整轮音频与完整正文关联保存。
  - SeedTTS2 把本地逻辑轮次和上游厂商会话分开：`SentenceType.FIRST` 只重置流状态并登记本轮，第一段经清洗后非空的 `ContentType.TEXT` 或路径有效的 `ContentType.FILE` 到达时才启动一次上游会话；空轮 `LAST` 不得反向创建会话。
  - FIRST 在允许连接复用时可并行预热 TTS 传输连接，但不得提前创建合成会话；预热失败由正文路径重试，关闭时取消，握手共用连接锁。
  - 纯唤醒在绑定、当前角色、LLM、TTS 和本地完整系统提示就绪后可提前放行，不等待位置、天气和动态上下文刷新；普通指令仍等完整就绪。保留长期记忆、身份识别和 detect/ASR 去重；热更新、初始化失败及关闭使快速门禁失效。详见设备交互链路的唤醒优化边界。
  - SeedTTS2 正常结束必须冲刷不足60ms的Opus尾帧，取消、失败或异常断链必须重置编码器，禁止残留样本跨会话。
  - SeedTTS2 默认不做首音频时间预缓冲：完整 Opus 帧到达即按 FIFO 发布，即使完整字幕尚未就绪也不得阻塞音频；完整回答写入后，再在后续音频或正常收尾边界发布非空 `FIRST`。可选 `stream_prebuffer_ms` 仍允许显式恢复预缓冲；provider 音频 FIFO、设备 FIRST/LAST/stop 必须绑定同一 reply generation，迟到旧 generation 不得结束新回复。
  - SeedTTS2 只有在 `SessionFinished` 后才能发布正常 LAST；下行超时、断链、SessionFailed 和结束异常必须使用 `tts_error:*` 失败原因令 ReplyLifecycle 进入 FAILED，并关闭当前 WebSocket 供下一轮重建，不能伪装成正常播报完成。
  - SeedTTS2 按 sentence_id 维护 FIRST/LAST 状态；正常结束、结束超时、下行超时、WebSocket 断开、SessionFailed 和监听异常都必须走同一幂等收尾。只要已经发布 FIRST，就必须最终发布且只发布一次 LAST；迟到事件不能重复 LAST，残余旧会话事件不能重置当前会话。
  - 设备 TTS 协议层同一 sentence_id 的 start/stop 最多发送一次；主动 abort 已直接发送 stop 时，provider 不得再次补发。
  - 与官方 upstream/main 一致，STT 后立即发送 `tts:start` 并把回复标记为可打断；这里的 `client_is_speaking` 包含尚未产生首音频的等待阶段，不新增 `response_pending`。真实播放边界由 `_tts_started_at` 单独投影。
  - STT pending start、真实 FIRST、Abort stop 和迟到 LAST 共用连接级协议去重；迟到旧 LAST 不得停止下一轮 pending 回复。
  - Abort 保留 interruption 延时链路，并对已有文本的部分助手回复执行一次性收口；聊天消息使用 `turn_id + message_sequence` 还原 user/tool/assistant 顺序，manager-api 的统一排序同时供历史重建和记忆可视化使用。
  - SeedTTS2 默认使用10秒建连、10秒会话启动、30秒会话结束、15秒下行空闲和5秒单次发送预算；线程外层等待必须覆盖内层阶段预算，旧 session_timeout_seconds 不得把结束预算降到30秒以下。
  - 请求头日志按键名屏蔽 Authorization、Proxy-Authorization、Cookie、Set-Cookie、token、api-key、apikey、secret。
  - unregister_connection 只删除仍指向当前关闭对象的键，旧连接不能误删新连接。
关键文件：
  - main/xiaozhi-server/config.yaml
  - main/xiaozhi-server/core/providers/asr/doubao_recording.py
  - main/xiaozhi-server/core/providers/asr/doubao_stream.py
  - main/xiaozhi-server/core/providers/tts/huoshan_double_stream.py
  - main/xiaozhi-server/core/handle/sendAudioHandle.py
  - main/xiaozhi-server/core/handle/wakeupHandle.py
  - main/xiaozhi-server/core/handle/helloHandle.py
  - main/xiaozhi-server/core/handle/receiveAudioHandle.py
  - main/xiaozhi-server/core/handle/abortHandle.py
  - main/xiaozhi-server/core/utils/turn_metrics.py
  - main/manager-api/src/main/java/xiaozhi/modules/agent/service/support/AgentChatHistoryOrder.java
  - main/manager-web/src/components/ChatHistoryDialog.vue
  - main/xiaozhi-server/core/handle/textHandler/listenMessageHandler.py
  - main/xiaozhi-server/core/connection.py
  - main/xiaozhi-server/core/providers/llm/openai/openai.py
  - main/xiaozhi-server/core/device_runtime_registry.py
  - main/xiaozhi-server/tests/test_doubao_recording_asr.py
  - main/xiaozhi-server/tests/test_doubao_stream_asr.py
  - main/xiaozhi-server/tests/test_huoshan_double_stream_tts_config.py
  - main/xiaozhi-server/tests/test_audio_rate_controller_faults.py
  - main/xiaozhi-server/tests/test_wakeup_vad_ignore.py
  - main/xiaozhi-server/tests/test_connection_log_sanitization.py
  - main/xiaozhi-server/tests/test_device_runtime_registry.py
  - main/xiaozhi-server/tests/test_function_call_runtime_mode.py
  - main/xiaozhi-server/tests/test_openai_llm_timeout.py
  - docs/models/voice-pipeline-latency-analysis.md
  - docs/architecture/device-interaction-chain.md
是否允许被官方覆盖：否
本次发布状态：2026-07-29 的 function-call 流播仍只修改并验证源码；未构建镜像、未部署、未重启腾讯云生产服务。用户发布后才进行真实服务端回归，1.1-1.4s 仅为按既有阶段日志估算的可重叠窗口。
真实外部回归：豆包录音ASR 4/4、流式ASR修复前后5/5、SeedTTS2连续5会话、DeepSeek v4-pro/v4-flash原生与仓库provider均成功；当前provider完整ASR→LLM→TTS链路连续3/3成功，平均约3.753秒。真实凭据只用于进程内测试且未落盘；测试完成后应轮换在会话或截图中暴露过的凭据。
官方同步时的处理规则：如果官方修改聊天流式输出、OpenAI function-call、豆包录音/流式 ASR、SeedTTS2、TTS 音频发送、唤醒词处理、欢迎语、连接日志或设备注册表，保留官方 `direct_answer` 增量提取和5字符 JSON 安全尾、携带 tools 时普通 content 即时流播、默认零毫秒 SeedTTS2 首音预缓冲，同时保留官方式完整回答字幕来源、音频不等待字幕、每个 provider `FIRST` 都映射为 `sentence_start`、正常 `LAST` 优先完整回答的聊天记录上报、reply generation 边界隔离、错误终态和 required/单工具能力门控及不兼容 provider 回退；还必须保留异步 HTTP 与总预算超时、流式 WebSocket 分阶段等待保护、本地 VAD 判停发送唯一 LAST、缓存首帧去重、短词识别和按会话身份清理、SeedTTS2 逻辑 `FIRST` 不提前启动上游会话且首段真实 `TEXT/FILE` 只启动一次、空轮 `LAST` 不创建厂商会话、任意非主动打断结束路径幂等收尾、连接级同轮 start/stop 去重、官方 STT 后立即进入可打断语义（不得另造 `response_pending`）、Abort 部分回复与 interruption 指标幂等收口、聊天和记忆重建按 `turn_id + message_sequence` 还原逻辑顺序、Opus尾帧冲刷、默认零毫秒首轮音频丢弃、欢迎语显式开启、唤醒词实际检测文本与实际播放回复双向上报、按请求头键名脱敏和对象身份注销校验。不得重新引入模型决定的停止说话工具、停止词表或独立 LLM 打断分类器；不得在 async provider 中引入 requests/time.sleep，不得让更短外层超时覆盖会话结束预算，不得把 Authorization、token、API key、Cookie 或 secret 写入日志；调整 ASR 超时、TTS 会话启动、分片/预缓冲或编码边界和唤醒回声窗口时必须同步更新默认配置、专项测试、语音延迟文档，并在真实设备上验证 function-call 长回复不断句丢音、FIRST字幕为完整回答、异常后设备恢复监听、连续TTS无串音和重连映射稳定。
```

## 26. 外置声纹服务、智能体隔离与五条上限

```text
定制模块：manager-api 声纹远程客户端与业务约束、xiaozhi-server 实时声纹 provider、Web/Mobile 声纹管理页
改动原因：声纹模型独立部署在另一台服务器，voiceprint-api 0.1.0 起要求必填 user_id 并按用户隔离。小智侧必须从可信智能体绑定关系派生 user_id，不能信任浏览器或设备传入；每个智能体最多绑定 5 个声纹；API Token 必须从 URL 查询参数拆出，只通过 Bearer Header 传输。历史声纹在远程服务中属于 default 作用域，需要保留兼容并允许重新录音后迁移到 agentId 作用域。实时识别假设一句话只有一个说话人，VAD 句末后把当前整段语音转 WAV 并与 ASR 并行识别，对齐官方上游逻辑；历史参数 `server.voiceprint_sample_seconds` 不再下发也不再影响运行时。系统功能开关和智能体绑定声纹必须同时满足才允许下发；没有绑定或总开关关闭时必须零调用、零提示词。可信 `speaker_id` 继续进入聊天记录、长期记忆事件和会话结束后的主动记忆上下文，`speaker_score` 仅保留在指标和聊天上报，不进入主 LLM；主动记忆模型输出中的 speaker_id 必须能在当前会话可信人物集合中验证，单一已识别说话人的事实保存为 private，未识别会话事实保存为 shared。后端不按正文姓名扩大范围。聊天记录和记忆图谱必须展示人物归属。身份必须在每个成功识别轮次同时进入结构化 user message 和动态 system 上下文，避免只靠旧对话历史导致模型否认本轮识别结果；说话人姓名和描述进入 system 前必须转义，避免重复称呼由提示词约束，不能通过丢弃本轮身份实现。
关键文件：
  - main/manager-api/src/main/java/xiaozhi/modules/agent/voiceprint/VoiceprintApiClient.java
  - main/manager-api/src/main/java/xiaozhi/modules/agent/service/impl/AgentVoicePrintServiceImpl.java
  - main/manager-api/src/main/java/xiaozhi/modules/config/service/impl/ConfigServiceImpl.java
  - main/manager-api/src/main/resources/db/changelog/202607131430.sql
  - main/xiaozhi-server/core/utils/voiceprint_provider.py
  - main/xiaozhi-server/core/utils/voiceprint_runtime.py
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
官方同步时的处理规则：如果官方修改声纹注册/识别/删除、配置下发、音频状态重置、ASR并行识别、聊天记录展示、长期记忆事件/召回、对话身份注入或声纹页面，必须保留 `user_id=agentId` 的可信服务端派生、每智能体最多5条的后端与远程双重校验、`remote_user_id=default` 历史兼容、Token仅通过Bearer Header传输、私有/回环/链路本地目标拦截、结构化 speakers 配置、“VAD句末整段语音WAV与ASR并发识别、`server.voiceprint_sample_seconds` 不再驱动截断采样、无绑定声纹零调用”、设备采集期间连续静音至少2秒才完成、注册事务提交后热刷新在线连接 provider、可信 `speaker_id` 贯穿聊天记录与记忆、private 事实不跨声纹召回、shared 由主模型明确选择，以及“每个成功识别轮次都在当前 user JSON 和 system 上下文保留身份，但不得机械称呼姓名或主动播报识别过程”的行为。前端上限提示不能替代后端校验；删除远程失败时不得静默留下占用额度的孤儿声纹；日志不得输出Token、Authorization或带密钥URL。
```

## 27. 语义控制中枢门控的会议模式与完整纪要

```text
定制模块：xiaozhi-server 主 LLM 会议工具、连接级会议记录状态、完整会议纪要生成
改动原因：会议能力登记为 `SYSTEM_PLUGIN_MEETING_MINUTES`，启用后注册真实 `meeting_minutes` function tool。会议开始、记录和结束全部由主 LLM 根据上下文主动调用 `start / record / end`；服务端不维护进入/退出关键词或确定性规则。会议激活后每条 ASR 都作为 pending turn 串行交给主 LLM，该轮只暴露会议工具；provider 支持时使用 `tool_choice=required`，具体 `record/end` 仍由模型决定。只有工具调用成功才改变状态，异常时安全回退为继续记录。最终使用冻结的全部结构化转写同时生成自然口语播报和书面飞书纪要。会议状态、工具审计和会议正文不进入长期记忆事件。
关键文件：
  - main/xiaozhi-server/core/meeting/config.py
  - main/xiaozhi-server/core/meeting/executor.py
  - main/xiaozhi-server/core/meeting/minutes.py
  - main/xiaozhi-server/plugins_func/functions/meeting_minutes.py
  - main/xiaozhi-server/core/handle/receiveAudioHandle.py
  - main/xiaozhi-server/core/utils/modules_initialize.py
  - main/xiaozhi-server/core/connection.py
  - main/xiaozhi-server/core/providers/llm/openai/openai.py
  - main/xiaozhi-server/core/websocket_server.py
  - main/xiaozhi-server/tests/test_meeting_control.py
  - main/xiaozhi-server/tests/test_meeting_llm_control.py
  - main/xiaozhi-server/tests/test_meeting_minutes.py
  - main/manager-api/src/main/resources/db/changelog/202607141735.sql
  - main/manager-api/src/main/resources/db/changelog/202607141910.sql
  - main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml
  - docs/architecture/device-interaction-chain.md
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改插件配置下发、语义控制中枢、主 LLM function calling、ASR 句末处理、TTS 入队或连接生命周期，必须保留“全模块按智能体插件门控、会议 start/record/end 只由主 LLM真实工具调用决定、服务端不维护会议话术词表或确定性进入/退出规则、会议激活时每条 ASR 只交给主 LLM会议工具判断、模型未调用或异常时仅继续记录、进入会议有一次简短确认、最终纪要使用完整冻结转写、会议内容不污染长期记忆”的行为；不得允许普通模型文字模拟状态切换，也不得引入独立 SLM 裁决。会议插件后续修正必须新增迁移并登记到 `db.changelog-master.yaml`，不得修改已发布 changeSet。
```

## 28. 智能体级飞书协作集成

```text
定制模块：manager-api 飞书配置与开放平台客户端、manager-web 智能体绑定、xiaozhi-server 飞书工具与会议纪要投递
改动原因：会议模式生成完整纪要后，需要按智能体绑定关系自动发送到指定飞书群，并允许主 LLM 在显式授权范围内发送/读取默认群消息、创建任务和安排日程；还支持通过飞书事件订阅把目标群新文本消息主动播报到当前智能体的在线空闲设备。绑定不是用户级 OAuth；每个智能体独立保存应用、默认群、负责人、日历、事件 Verification Token 和动作权限。
关键文件：
  - main/manager-api/src/main/java/xiaozhi/modules/agent/feishu/FeishuOpenApiClient.java
  - main/manager-api/src/main/java/xiaozhi/modules/agent/feishu/FeishuDeviceAnnouncementClient.java
  - main/manager-api/src/main/java/xiaozhi/modules/agent/service/impl/AgentFeishuServiceImpl.java
  - main/manager-api/src/main/java/xiaozhi/modules/config/controller/FeishuRuntimeController.java
  - main/manager-api/src/main/resources/db/changelog/202607171130.sql
  - main/manager-api/src/main/resources/db/changelog/202607181705.sql
  - main/manager-web/src/components/role/FeishuIntegrationDialog.vue
  - main/xiaozhi-server/plugins_func/functions/feishu_assistant.py
  - main/xiaozhi-server/core/meeting/executor.py
  - main/xiaozhi-server/core/api/device_notification_handler.py
  - main/xiaozhi-server/core/spoken_notifications.py
  - docs/integrations/third-party-integrations.md
是否允许被官方覆盖：否
官方同步时的处理规则：必须保留智能体级所有权、App Secret 与事件 Verification Token 仅在 manager-api 使用部署级 AES-GCM 密钥分作用域加密保存、前端不回显、Python 不接收凭据、`/config/feishu/**` 同时校验服务密钥和设备到智能体绑定关系、默认群/负责人/日历服务端固定门控、各动作独立授权和群消息不可信内容包裹。工作空间角色模板保存飞书配置时，密钥必须以模板 ID 为 AAD 重新加密，应用时再重加密到目标 agentId 作用域；模板接口、日志和 Python 均不得收到明文或密文。飞书事件回调虽然匿名放行，但必须校验 Verification Token、App ID、目标群、事件类型，执行 event_id 去重和请求限流；主动播报只走带服务密钥的内部设备接口，会议和设备忙碌时不得插播。配置页连接测试、默认群读取和测试消息只能要求凭据已配置，不能被运行时 enabled 开关阻断；设备运行时动作、会议纪要投递和工具注册仍必须要求集成已启用。会议纪要投递失败不得阻塞设备端纪要播报；日志不得输出 App Secret、Verification Token、tenant_access_token、Authorization 或原始凭据响应。
```

## 29. 用户级天气与联网搜索共享配置

```text
定制模块：manager-api 用户插件共享配置、manager-web 功能配置、xiaozhi-server 天气请求
改动原因：天气和联网搜索的外部服务密钥属于用户可复用凭据，不应在每个新智能体的插件映射中重复填写和明文保存。现在用户只配置一次 API Key、天气默认城市/API Host 或搜索源；智能体仍独立决定是否启用插件，并保留描述、返回数量等行为参数。
关键文件：
  - main/manager-api/src/main/java/xiaozhi/modules/userplugin/
  - main/manager-api/src/main/java/xiaozhi/modules/config/service/impl/ConfigServiceImpl.java
  - main/manager-api/src/main/java/xiaozhi/modules/agent/service/impl/AgentServiceImpl.java
  - main/manager-api/src/main/resources/db/changelog/202607171630.sql
  - main/manager-web/src/components/UserPluginConfigDialog.vue
  - main/manager-web/src/components/FunctionDialog.vue
  - main/manager-web/src/views/FunctionPluginManagement.vue
  - main/xiaozhi-server/core/api/plugin_test_handler.py
  - main/xiaozhi-server/plugins_func/functions/get_weather.py
是否允许被官方覆盖：否
官方同步时的处理规则：必须保留“用户级凭据、智能体级启用”的双层边界。资源中心插件目录必须为白名单共享能力提供配置状态、配置入口和真实测试，但不能因此自动启用角色插件。共享配置只能支持白名单插件和字段，使用部署级 AES-GCM 密钥加密，API 只返回非敏感字段和已配置状态；智能体保存、默认创建和读取接口不得继续暴露或写入共享密钥。运行时只能为已绑定插件按 agent.user_id 合并共享配置。测试由 manager-api 解密后通过内部 Bearer 服务密钥调用 xiaozhi-server，凭据不得进入 URL、日志或响应；天气测试必须验证和风城市接口，不得用 Open-Meteo 兜底伪造通过。和风 API Key 必须放在 X-QW-Api-Key 请求头中；自定义 Host 仅允许受校验的 *.qweatherapi.com 或明确公共 Host。
```

## 30. ACR 完整版本绑定与部署资产镜像

```text
定制模块：ACR 发布脚本、生产 install/upgrade、版本化部署资产
改动原因：旧流程中 VERSION 只固定三个业务镜像，生产服务器仍从当前 Git 分支读取 docker-compose.yml 和 install；当镜像新增环境变量、卷或启动依赖而服务器部署资产未同步时，会形成“新镜像 + 旧部署契约”。现在每次发布同时生成 `xiaozhi-deploy-assets:<version>`，包含 Compose、install/upgrade、默认配置、release manifest 和环境变量契约。生产固定入口 `~/xiaozhi-upgrade` 先解析同版本资产镜像，再部署 manifest 指定的三个业务镜像；`latest` 也先解析为资产镜像记录的不可变版本。每次部署都会原子更新固定入口及数据目录内的解析器，仓库脚本只承担首次引导和故障恢复。
关键文件：
  - scripts/acr-build-push
  - deploy/production/Dockerfile.release-assets
  - deploy/production/release-contract.env
  - deploy/production/install
  - deploy/production/upgrade
  - deploy/production/docker-compose.yml
  - deploy/production/README.md
  - docs/deployment/private-docker-release.md
是否允许被官方覆盖：否
官方同步时的处理规则：必须保留“一个版本同时绑定业务镜像和部署资产”的契约；`VERSION=<sha> ~/xiaozhi-upgrade` 不得回退为从服务器当前 Git 分支读取 Compose 后只固定镜像标签。deploy-assets manifest 不得包含密钥，只能保存版本、源码 commit、契约版本、镜像仓库和运行时镜像；`~/xiaozhi-data/.env` 中的真实密码和凭据加密密钥必须继续由 install 生成并跨升级保留。生产服务器只需保留一次性引导脚本、固定升级命令和 Docker/ACR 登录，不构建源码；版本或契约不匹配时 upgrade 必须在启动服务前失败。
```

## 31. 设备单轮语音背景上下文

```text
定制模块：xiaozhi-server listen 协议、单轮会话状态、对话与聊天记录注入、manager-web 聊天记录展示
改动原因：设备在用户开始一轮语音交互时，需要选择性上传文本或结构化背景；背景必须和本轮 STT 一起交给主 LLM，但不能独立触发回复，也不能进入长期记忆。
关键文件：
  - main/xiaozhi-server/core/session/turn_context.py
  - main/xiaozhi-server/core/handle/textHandler/listenMessageHandler.py
  - main/xiaozhi-server/core/providers/asr/base.py
  - main/xiaozhi-server/core/utils/dialogue.py
  - main/xiaozhi-server/core/handle/reportHandle.py
  - main/manager-web/src/components/ChatHistoryDialog.vue
  - docs/architecture/device-interaction-chain.md
  - docs/architecture/prompt-injection-engineering.md
是否允许被官方覆盖：否
官方同步时的处理规则：必须保留 listen/start.context 的可选兼容协议、8 KiB 输入上限、单轮一次性消费、空 ASR/打断不触发且不串轮、日志不记录背景正文、SemanticCenter 与 intent_llm 只读 STT、主 LLM user message 才读取 context、后台聊天记录保留背景而长期记忆事件和 Python Memory provider 排除背景的边界。旧设备不发送 context 时行为必须完全不变。
```

## 32. 智能体级豆包 Seed ASR 热词表

```text
定制模块：manager-web 角色配置、manager-api 智能体配置装配、xiaozhi-server 豆包 Seed ASR provider
改动原因：热词应由智控台资源中心登记并通过官方 API 注册到火山，用户无需手工切换控制台。词库按工作空间隔离，共享模型凭据只在服务端读取。角色配置继续用 asr_hotword_ids 保存最多 10 个候选 ID，并用 asr_hotword_id 标记当前生效项；官方限制一个识别请求只支持一张词表，所以 manager-api 只把当前生效项注入 doubao_stream 或 doubao_recording 的运行时 boosting_table_id。Python 按官方 request.corpus.boosting_table_id 层级发送，同时在同一 corpus 对象保留独立的 context 字段。切换当前生效项后通过运行配置刷新链路在线生效。
关键文件：
  - main/manager-api/src/main/java/xiaozhi/modules/hotword/
  - main/manager-api/src/main/java/xiaozhi/modules/agent/support/ManagedAsrHotwordGuard.java
  - main/manager-api/src/main/resources/db/changelog/asr-hotword-management.sql
  - main/manager-web/src/views/AsrHotwordManagement.vue
  - main/manager-web/src/components/role/RoleEditDialog.vue
  - main/manager-web/src/components/role/AsrHotwordField.vue
  - main/manager-web/src/utils/roleConfigDraft.js
  - main/manager-api/src/main/java/xiaozhi/modules/agent/entity/AgentEntity.java
  - main/manager-api/src/main/java/xiaozhi/modules/agent/support/AgentAsrHotwordSelection.java
  - main/manager-api/src/main/java/xiaozhi/modules/config/service/impl/ConfigServiceImpl.java
  - main/manager-api/src/main/resources/db/changelog/202607241445.sql
  - main/manager-api/src/main/resources/db/changelog/202608021500.sql
  - main/xiaozhi-server/core/providers/asr/doubao_stream.py
  - main/xiaozhi-server/core/providers/asr/doubao_recording.py
  - docs/models/model-management.md
  - docs/architecture/device-interaction-chain.md
是否允许被官方覆盖：否
官方同步时的处理规则：必须保留“共享 ASR 模型、智能体级热词选择”的边界；候选 ID 只能存放在智能体配置，只有当前生效 ID 可以覆盖运行时 typed_config，不得写回共享模型配置，也不得把多个 ID 拼接或作为数组伪装下发。豆包 Seed ASR 2.0 必须发送 request.corpus.boosting_table_id；context_text 映射 request.corpus.context，不能用词表 ID 代替上下文。非豆包 ASR 和留空角色不得发送热词表，初始化日志不得输出上下文正文或完整请求体。托管词库必须保留工作空间读写权限、服务端凭据隔离、显式发布与应用、失败状态和在用删除保护；不得把火山账号下的所有词表暴露给普通工作空间，也不得把保存草稿当作发布成功。
```

## 33. 聊天记录删除后的容量同步

```text
定制模块：manager-web 聊天记录弹窗、首页智能体容量卡片
改动原因：单条消息、整段会话或全部聊天记录删除成功后，后端会同步删除历史行并由智能体列表实时聚合 storage_bytes；聊天弹窗原先只刷新自身内容，没有通知首页重新读取聚合值，导致容量卡片必须手动刷新网页才更新。现在三种删除统一发出 history-changed 事件，首页后台重新读取权威智能体列表，并在搜索期间保留当前筛选结果；连续刷新只接受最新请求结果，防止乱序响应把旧容量重新写回；卡片列表使用 agentId 稳定键，避免数组位置复用旧组件状态。
关键文件：
  - main/manager-web/src/components/ChatHistoryDialog.vue
  - main/manager-web/src/views/home.vue
  - main/manager-web/tests/chat-history-delete-reliability.test.cjs
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方修改聊天记录删除入口、智能体列表加载或首页搜索，必须保留“删除接口成功后统一通知首页、容量从后端实时聚合结果重新读取、搜索状态不因后台刷新而被清空、乱序响应不能覆盖最新结果、卡片使用智能体 ID 稳定键”的契约；不得在浏览器端通过减去消息大小推算容量，因为会话批量删除、并发写入和服务端存储规则会造成累计偏差。
```

## 34. 已认证设备 WebSocket 原文直读 TTS

```text
定制模块：xiaozhi-server WebSocket 文本协议、在线连接 TTS 队列、任务状态和聊天记录上报
改动原因：已绑定机器人需要通过当前已认证 WebSocket 主动提交一段文字，并使用该连接已经初始化的智能体 TTS provider、音色和 Opus 下行链路原样播报。HTTP 请求中的 deviceId 不能证明调用者拥有该设备，因此设备直连场景不再新增无鉴权 HTTP 入口或额外共享密钥。
关键文件：
  - main/xiaozhi-server/core/external_tts.py
  - main/xiaozhi-server/core/handle/textHandler/externalTtsMessageHandler.py
  - main/xiaozhi-server/core/handle/textMessageProcessor.py
  - main/xiaozhi-server/core/providers/tts/base.py
  - main/xiaozhi-server/core/handle/sendAudioHandle.py
  - main/xiaozhi-server/core/handle/abortHandle.py
  - main/xiaozhi-server/core/handle/reportHandle.py
  - main/xiaozhi-server/tests/test_external_tts.py
  - docs/architecture/device-interaction-chain.md
是否允许被官方覆盖：否
官方同步时的处理规则：必须保留 external_tts 只在完成 WebSocket 认证和设备绑定后处理、身份来自当前 ConnectionHandler 而不是消息中的 deviceId、直接复用 conn.tts 且不新建全局 provider、单个 TEXT 队列项保留原文、不经过 LLM/语义/MCP/表情/动作/Dialogue、assistant 聊天记录沿用 chat_history_conf（值为 2 时同时保存合成音频）而长期记忆事件跳过、deviceId+requestId 等价的设备级十分钟幂等、忙碌默认拒绝和 interrupt=true 走 abort_generation、通话/会议/声纹采集禁止插播、状态只声明 server_audio_sent 而不冒充物理播放完成，以及日志不记录 external_tts 正文的边界。不得重新开放通过任意 deviceId 控制在线设备的无鉴权 HTTP 接口。
```

## 35. 连接能力门禁与回复 generation 收敛

```text
定制模块：xiaozhi-server 连接初始化、listen 协议、LLM/TTS 回复生命周期、external_tts 忙碌诊断
改动原因：初始化期间的 listen.detect 曾可在 LLM 尚未就绪时发送 tts/start，并把 client_is_speaking 永久遗留为 true，导致后续 interrupt=false 的 external_tts 一直被拒绝。单个 _modules_ready 也不能证明 TTS/ASR 音频通道已经成功打开。
关键文件：
  - main/xiaozhi-server/core/connection.py
  - main/xiaozhi-server/core/session/runtime_readiness.py
  - main/xiaozhi-server/core/session/reply_lifecycle.py
  - main/xiaozhi-server/core/handle/receiveAudioHandle.py
  - main/xiaozhi-server/core/handle/sendAudioHandle.py
  - main/xiaozhi-server/core/handle/textHandler/listenMessageHandler.py
  - main/xiaozhi-server/core/external_tts.py
  - main/xiaozhi-server/core/providers/tts/base.py
  - main/xiaozhi-server/tests/test_reply_lifecycle_readiness.py
  - docs/architecture/device-interaction-chain.md
  - docs/architecture/runtime-flows.md
是否允许被官方覆盖：否
官方同步时的处理规则：必须保留 binding_ready、tts_ready、conversation_ready、audio_input_ready 的分能力门禁；TTS/ASR 通道成功打开前不得设置 ready；初始化异常不得伪装成功。listen.detect 在 conversation_ready 前按相同文本去重并进入最多 8 条、TTL 10 秒的连接队列，失败、过期或关闭后不得回放。STT 展示不得隐式创建不一定发声的回复；每个 tts/start 必须绑定一个 generation，并以 stop、abort 或 connection_closed 恰好一次收敛；client_is_speaking 只能从活跃生命周期派生。LLM 提交/执行、无语音工具结果和 TTS 合成/发送失败必须终止当前 generation。external_tts 默认继续使用 interrupt=false，只有用户明确抢占时使用 true；busy 响应保留稳定 busyReason 和无正文诊断日志，通话、会议与声纹采集继续禁止抢占。
```

## 36. 智能体级固定播报技能

```text
定制模块：manager-web 技能配置、manager-api 智能体技能事实与运行配置下发、xiaozhi-server 确定性语义匹配与固定 TTS
改动原因：展厅讲解和企业介绍需要由运营人员按智能体配置稳定话术；完整话术或一组关键词同时出现时直接播报固定文本，不消耗 LLM，未命中必须继续原有 Intent/主 LLM 链路。
关键文件：
  - main/manager-web/src/components/role/AgentSkillDialog.vue
  - main/manager-web/src/utils/agentSkillMatcher.js
  - main/manager-api/src/main/java/xiaozhi/modules/agent/skill/
  - main/manager-api/src/main/resources/db/changelog/202607281600.sql
  - main/manager-api/src/main/java/xiaozhi/modules/config/service/impl/ConfigServiceImpl.java
  - main/xiaozhi-server/core/semantic/skill_registry.py
  - main/xiaozhi-server/core/semantic/skill_matcher.py
  - main/xiaozhi-server/core/semantic/semantic_center.py
  - main/xiaozhi-server/tests/test_semantic_skills.py
  - docs/architecture/semantic-control-center.md
是否允许被官方覆盖：否
官方同步时的处理规则：必须保留技能以 agent_id 为所有权边界、管理 CRUD 的智能体/Workspace 权限校验、只向当前设备绑定智能体下发已启用技能，以及 `phrase` 单话术包含匹配和 `all_keywords` 组内 AND/组间 OR 的确定性契约。多命中依次按 priority、term 数量、term 总长度、phrase、sort、id 决胜；Web 模拟结果必须与 Python 一致。技能只在空闲状态触发，会议、声纹采集、通话和游戏保持优先；命中后不得调用 Intent/LLM，必须复用当前连接的 TTS generation、FIRST/TEXT/LAST、AEC 打断和聊天记录上报，并跳过长期记忆。TTS 失败时不得回退 LLM；无配置、无效配置或未命中必须完全保持旧链路。配置保存后通过受保护的运行时通知和下一轮配置指纹检查热更新，不得要求设备重连，也不得破坏连接级隔离。
```

## 37. 多端登录会话与智能体运行配置热更新

```text
定制模块：manager-api 登录 Token、智能体/模型配置事件、xiaozhi-server 在线连接运行时
改动原因：原 sys_user_token 对 user_id 使用唯一约束，每次登录覆盖旧 Token，导致同一账号在第二个浏览器或设备登录后第一端被强制退出；同时音色、提示词、模型、插件和固定播报技能只在设备连接时装配，后台保存后必须重连设备。
关键文件：
  - main/manager-api/src/main/java/xiaozhi/modules/security/
  - main/manager-api/src/main/java/xiaozhi/modules/agent/runtime/
  - main/manager-api/src/main/java/xiaozhi/modules/agent/service/impl/AgentServiceImpl.java
  - main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelConfigServiceImpl.java
  - main/manager-api/src/main/resources/db/changelog/202608011030.sql
  - main/xiaozhi-server/core/api/runtime_config_refresh_handler.py
  - main/xiaozhi-server/core/session/runtime_config.py
  - main/xiaozhi-server/core/session/runtime_config_reloader.py
是否允许被官方覆盖：否
官方同步时的处理规则：必须保留同一用户多条独立 Token、当前会话退出与全会话密码失效的边界；不得恢复登录覆盖旧 Token。必须保留智能体配置事务提交后的受保护内部刷新通知、下一轮 `listen/start` 配置指纹兜底、空闲轮次边界 provider 热替换、失败恢复旧配置和正常路径设备 WebSocket 不重连的约定。`external_tts` 不产生 `listen/start`，因此接受新的网页真实音频任务前必须在连接空闲边界执行同一配置指纹检查，并在刷新后重新读取当前 `conn.tts`；刷新失败时继续使用旧 provider，不能丢失播报。刷新通知只能标记待更新，不能在用户录音、ASR 消费或 TTS 播放中途直接替换工作线程；旧线程退出超时时必须关闭异常连接，避免重复消费者。运行时日志只能记录 agent/device、原因和模块摘要，不能记录模型密钥、Token 或完整私有配置。
```

## 38. 大模型会话级语音控制与上下文重置

```text
定制模块：manager-api 运行时音色目录、xiaozhi-server 会话级 TTS override、必要内置 Function Plugin、Dialogue/语义软状态
改动原因：用户需要让主 LLM 在不修改后台智能体配置、不影响其他设备连接的前提下，主动切换当前会话音色、语速、音量和音调，并能明确清空当前短期上下文重新对话。
关键文件：
  - main/manager-api/src/main/java/xiaozhi/modules/config/service/impl/ConfigServiceImpl.java
  - main/manager-api/src/main/java/xiaozhi/modules/timbre/service/
  - main/xiaozhi-server/core/session/tts_session_options.py
  - main/xiaozhi-server/plugins_func/functions/session_controls.py
  - main/xiaozhi-server/core/providers/tools/server_plugins/plugin_executor.py
  - main/xiaozhi-server/core/utils/dialogue.py
  - main/xiaozhi-server/tests/test_session_controls.py
是否允许被官方覆盖：否
官方同步时的处理规则：2026-09-24 已取消对话工具 set_session_voice，禁止通过必要工具列表、旧 Intent 配置或声明式插件恢复，执行入口也必须拒绝残留调用；传统和 Realtime 均不向模型开放该工具。后台默认音色、音色测试和设备音量控制保留，旧数据库目录记录不代表运行时工具可用。reset_conversation_context 继续作为必要工具，只清 Dialogue 和连接软状态，保留 system prompt，不删除后台聊天记录、长期记忆或配置；会议、设备通话和声纹采集期间必须拒绝。底层 SessionTTSOptions 的内部参数映射及模型隔离仍保留，不代表恢复语音换音色功能。
```

## 39. 管理后台音色真实合成测试

```text
定制模块：manager-web 音色管理弹窗、manager-api 音色测试服务
改动原因：手动登记的音色 ID 可能与其关联 TTS 模型的产品、resource_id 或协议不兼容；只保存字段无法发现“握手成功但没有下行音频”的问题。现在已保存音色可按行执行真实测试，后端复制关联模型运行配置并用 private_voice 覆盖当前音色，通过 xiaozhi-server benchmark API 调用真实 Python provider。只有返回可播放音频才通过，页面展示试听、首音频和总耗时以及真实错误；测试不修改共享模型配置、模型启用状态或模型总体测试状态。
关键文件：
  - main/manager-web/src/components/TtsModel.vue
  - main/manager-web/src/apis/module/timbre.js
  - main/manager-api/src/main/java/xiaozhi/modules/timbre/service/TimbreTestService.java
  - main/manager-api/src/main/java/xiaozhi/modules/timbre/service/impl/TimbreTestServiceImpl.java
  - main/manager-api/src/main/java/xiaozhi/modules/timbre/controller/TimbreController.java
  - docs/models/model-management.md
是否允许被官方覆盖：否
官方同步时的处理规则：必须保留“音色测试调用真实运行时 provider、private_voice 仅覆盖测试副本、产生浏览器可播放音频才算通过”的契约；不得退回字段校验、握手检查或伪造音频，不得让单个音色测试更新/禁用整个 TTS 模型。火山 mars/moon BigTTS 音色必须与 volc.service_type.10029 模型对应，不能错误绑定到 seed-tts-2.0。
```

## 40. 传统拼接与端到端 Realtime 双轨会话

```text
定制模块：xiaozhi-server 会话路由与 Realtime provider、manager-api 模型目录/配置/评测、manager-web 模式提示
改动原因：智能体既要保留 VAD -> ASR -> LLM -> TTS 的传统可组合链路，也要支持 Qwen Omni Realtime、豆包端到端实时语音等直接处理音频输入和音频输出的 Realtime 模型。两种模式需要复用一个 llmModelId、设备认证、工具门控和聊天上报，但不能把不同协议强行塞进普通 LLM provider，也不能在实时链路失败时静默改变本轮语义。自然打断、上游上下文重建、会话音色和原生工具调用统一由 adapter 能力声明：Qwen 支持工具 schema 与结果回注；旧版豆包二进制协议支持中断、重建和音色但不回注工具；新增豆包3.0 `doubao_duplex` 使用独立 JSON 协议、API Key鉴权与原生工具回注，不能覆盖旧版配置。3.0只接语音，测试必须发送真实语音并收到完整回复音频；不把文字或打招呼合成伪装成端到端推理。
关键文件：
  - main/xiaozhi-server/core/conversation/
  - main/xiaozhi-server/core/providers/realtime/
  - main/xiaozhi-server/core/api/session_context_handler.py
  - main/xiaozhi-server/core/session/conversation_context.py
  - main/xiaozhi-server/core/benchmark/runner.py
  - main/xiaozhi-server/core/api/vision_handler.py
  - main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelCatalogServiceImpl.java
  - main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/RealtimeModelConfigGuard.java
  - main/manager-api/src/main/java/xiaozhi/modules/model/support/RealtimeAdapterCapabilityRegistry.java
  - main/manager-api/src/main/java/xiaozhi/modules/device/service/DeviceConversationContextService.java
  - main/manager-api/src/main/resources/db/changelog/
  - main/manager-web/src/components/ModelEditDialog.vue
  - main/manager-web/src/components/model/RealtimeModelNotice.vue
  - main/manager-web/src/components/role/RoleEditDialog.vue
  - docs/architecture/project-architecture.md
  - docs/architecture/runtime-flows.md
  - docs/models/model-management.md
是否允许被官方覆盖：否
官方同步时的处理规则：必须保留 selected_module.LLM + model_config.<id>.typed_config 的单一选择契约；conversation_mode=realtime 使用独立 Realtime adapter，缺省继续走传统拼接。编辑已有模型时，明确模板必须优先于厂商名/模型名推断；模板、adapter、conversation_mode、请求路径 provider 和端点协议必须在前端提示并由 manager-api 保存/测试入口强制校验，禁止再形成 `realtime + openai + HTTP Chat Completions` 的矛盾配置，且必须在外部请求前拒绝以防 API Key 外发。补偿迁移只修复 Realtime 元数据、普通参数和测试状态，保留密钥表中的 api_key 原文且不回显。实时模式接管当前会话音频；自然打断、上下文重建、会话音色、图片和工具协议只能按 adapter 能力启用，工具仍执行统一服务端门控；打断必须保留刚开始的用户音频，上下文清空必须创建新的上游 session。角色级 `realtimeVoiceId` 只覆盖兼容 adapter 的当前 Realtime `typed_config.voice`，必须与传统 `ttsVoiceId` 分离，不得回写共享模型配置；固定音色目录和自定义音色 ID 都由 adapter 能力注册表声明，禁止在页面散落厂商判断。支持原生工具的 Realtime adapter 必须在会话初始化时独立确保统一工具运行时就绪，不能依赖传统 Intent provider；Realtime 首片音频有可用增量文本时先发送预览字幕，完整文本生成后必须在 `tts/stop` 前再次发送完整回答，不能让旧固件只停留在首字。没有工具结果回注协议的 adapter 必须拒绝主 LLM function_call，不能靠提示词 JSON 伪装。传统 VAD/ASR/TTS/VLLM 配置保留供手动切回。实时建连、响应、图片或工具失败不得静默切换为传统半轮；端点必须使用受支持的 wss 官方主机并防止 API Key 外发。Realtime 可以注入建立会话时的静态摘要并上报事件，但不等同于传统逐轮动态记忆查询，也不能隐式承担知识库切片、Judge、短期总结或主动记忆维护的同步文本 LLM。单模型测试必须建立真实 Realtime 会话并记录首文本/首音频/总耗时；自动测试必须按 adapter 覆盖路由、音频转换、能力拒绝、自然/显式 Abort、上下文重建、会话音色、关闭和模式切换。
```

## 41. 设备多场景与按设备在线切换

```text
定制模块：manager-api 设备场景关系/切换审计、xiaozhi-server 按设备热更新与会话隔离、Web/Mobile 场景管理
改动原因：一台物理设备需要在日常助手、英语老师、儿童陪伴等已有智能体之间切换，但不能复制模型、插件、知识库、密钥和长期记忆运行架构。
关键文件：
  - main/manager-api/src/main/java/xiaozhi/modules/device/
  - main/manager-api/src/main/resources/db/changelog/202608040930.sql
  - main/xiaozhi-server/core/session/scene_switch.py
  - main/xiaozhi-server/core/session/runtime_config_reloader.py
  - main/manager-web/src/components/scene/DeviceSceneDialog.vue
  - main/manager-mobile/src/pages/device/components/DeviceScenePanel.vue
  - docs/architecture/device-scene-management.md
是否允许被官方覆盖：否
官方同步时的处理规则：必须保留 ai_device.agent_id 作为当前生效智能体唯一事实源、device_id+agent_id 唯一场景关系、同工作空间与资源所有权校验、每设备最多20个场景、switch_request_id 幂等和 config_version 单调防旧请求覆盖。在线切换必须在安全轮次边界先初始化候选管线，失败恢复旧管线并仅在版本仍匹配时回滚数据库；同智能体热更新保留会话，跨智能体必须更换 session_id 并隔离对话、工具、游戏、会议、声纹元数据和临时 TTS override。场景和模板只能引用已授权资源，禁止保存聊天历史、长期记忆事实或声纹样本；角色模板中的飞书凭据只能使用模板作用域 AES-GCM 密文，不得把明文、密文或解密能力下发给 Web/Python，设备场景仍不得复制第三方凭据。服务端状态回传除共享密钥外还必须匹配已登记的 device/request/agent/version 事实，日志不得记录提示词、热词正文或凭据。
```

## 42. 工作空间共享角色模板快照

```text
定制模块：manager-web 角色配置页、manager-api 工作空间模板编排与 ai_agent_template 快照
改动原因：运营人员需要把当前角色页面中的人设、模型、音色、热词、插件、知识库、上下文源、固定技能、标签、替换词、声纹绑定和飞书集成保存成可复用模板，并在确认后立即切换其他角色，而不是只使用只能保存昵称和提示词的系统默认模板。官方模板还需要携带平台统一维护的 TTS 音色和官方知识库；模板中心必须在创建实例前提供不产生模型费用的只读预览，而不是把创建动作直接塞进信息稀疏的卡片。
关键文件：
  - main/manager-web/src/views/roleConfig.vue
  - main/manager-web/src/components/role/RoleEditDialog.vue
  - main/manager-web/src/components/role/RoleBasicSettingsPanel.vue
  - main/manager-web/src/components/role/roleVoiceSettingsLayout.scss
  - main/manager-api/src/main/java/xiaozhi/modules/agent/service/impl/AgentWorkspaceTemplateServiceImpl.java
  - main/manager-api/src/main/resources/db/changelog/202608041610.sql
  - main/manager-api/src/main/resources/db/changelog/202608041650.sql
  - docs/architecture/workspace-organization-management.md
是否允许被官方覆盖：否
官方同步时的处理规则：必须保留系统模板与工作空间模板的隔离、来源和目标智能体的 workspace 写权限校验、版本检查及事务应用。智能体中心必须把实例与模板作为同级入口，普通用户可看到官方模板、审核通过的社区模板和当前工作空间模板；卡片只进入预览，预览 API 只能返回安全摘要且不得调用模型，用户确认后才创建。基于模板创建必须在单一事务中完成配额校验、创建和应用，应用失败不得留下默认配置的半成品智能体，模板中的名称不得覆盖用户为新实例输入的名称。系统模板管理必须至少保留一条记录，历史空表通过后续 Liquibase 补种不含凭据的基础模板；默认模板数据异常时 manager-api 仍须以基础配置启动，禁止因初始化失败进入健康检查重启循环。超级管理员本人及其团队成员通过统一官方资源维护权限编辑系统默认模板；官方模板允许绑定已启用且测试通过的 TTS 模型、属于该模型的音色和启用中的官方知识库，保存、预览和应用时均须重新校验。工作空间模板可在同一工作空间内保留语音绑定；普通用户投稿为跨工作空间公共模板时仍必须剥离 TTS、音色和实时音色，防止携带不可移植资源。声纹必须以 `workspace_id` 固化资源归属，通过独立绑定表在同工作空间角色间复用，模板只保存 ID 引用且不得保存音频样本或生物特征；飞书 App Secret 与 Verification Token 必须从来源 agentId 作用域解密后立即以模板 ID 为 AAD 重新加密，应用时再重加密到目标 agentId，任何模板 API、日志或 Python 配置不得暴露明文或密文。设备绑定、聊天记录、长期记忆事实和上下文源认证请求头不得进入模板。模型、插件、知识库、替换词和声纹引用应用前必须重新验证；失效模型保留目标当前值，失效关联跳过并向页面返回明确警告。模板应用完成后只按事务提交后的权威角色配置刷新页面和在线设备，不能由前端伪造最终配置。角色配置 UI 必须把紧凑模板选择器与任务页签放在同一控制栏；基础设定的短名称使用有限宽度紧凑行，角色介绍在其下方占据完整编辑宽度，禁止左右高矮列造成空白；语音链路的短选项必须限制宽度和分区间距，热词候选默认折叠；`summaryMemory` 只在旧本地短摘要模式显示，不能在主动记忆模式渲染禁用空框。
```

## 43. 资源中心人物档案与设备本地人脸识别

```text
定制模块：manager-web 资源中心、manager-api 人物不可变照片与智能体发布、xiaozhi-server 人脸库增量协议
改动原因：运营人员需要独立维护人物照片，先绑定智能体、确认后再显式发布，不能在上传时直接选择设备或隐式生效；带本地算力的设备必须持久化人脸库，按智能体发布版本只同步 upsert/delete 差异，在本地识别后上报稳定人物 ID 并获取确定性动作，同时为未来服务端识别保留明确但默认关闭的能力接口。
关键文件：
  - main/manager-web/src/components/resource/ResourceCenterNav.vue
  - main/manager-web/src/views/PersonProfileManagement.vue
  - main/manager-web/src/views/FunctionPluginManagement.vue
  - main/manager-api/src/main/java/xiaozhi/modules/person/
  - main/manager-api/src/main/resources/db/changelog/202608041730.sql
  - main/manager-api/src/main/resources/db/changelog/202608261130.sql
  - main/xiaozhi-server/core/device_face_profiles.py
  - docs/architecture/person-resource-and-face-sync.md
是否允许被官方覆盖：否
官方同步时的处理规则：必须保留人物档案与后台登录用户的概念隔离、workspace 所有权、不可变照片资产、人物-智能体期望/发布双状态、智能体单调发布版本、/config 服务密钥之后基于设备当前 agent_id 的再鉴权和设备识别事件幂等。设备身份必须来自当前已认证 WebSocket，不得信任消息体 macAddress 或缓存 agentId；上传只创建草稿资源，显式发布才改变运行事实。v2 设备必须持久化本地人脸库，版本相同返回 unchanged 且不读图片，版本变化只传 upsert/delete，收到 complete 后才能原子推进本地版本；在线发布只发送 sync_available 提示。只声明 features.face_profiles=true 的 v1 设备可保留一次连接内全量兼容，但不能作为新协议。照片上传必须继续校验扩展名/MIME/实际图片/大小/像素和路径边界，只保存归一化运行文件，不把照片、特征向量或事件样本带入角色模板。服务端识别 capability 默认必须返回不支持，预留 POST 不读取或保存图片；正式启用前必须单独完成模型、加密、留存、限流、评测与合规设计。人物 UI 按建档、上传照片、绑定智能体和显式发布组织；设备在线、固件支持、实际落盘版本和最近识别状态在 API 提供真实事实前不得由前端猜测。
```

## 44. 联网搜索单次检索提示工程

```text
定制模块：xiaozhi-server 联网搜索 Function Plugin 与工具结果递归策略
改动原因：联网搜索需要优先提高首次查询质量，不能依赖重复搜索补救，否则会放大延迟和额度消耗。主 LLM 应通过领域中立的工具描述，在唯一一次搜索中补全已明确的上下文指代并保留对象、时间、地点和事实目标；北京时间必须继续作为相对时间和跨日事实的统一解释基准。
关键文件：
  - main/xiaozhi-server/plugins_func/functions/web_search.py
  - main/xiaozhi-server/core/providers/tools/follow_up_policy.py
  - main/xiaozhi-server/tests/test_web_search.py
  - docs/architecture/prompt-injection-engineering.md
  - docs/reports/web-search-prompt-evaluation-2026-08-06.md
是否允许被官方覆盖：否
官方同步时的处理规则：必须保留北京时间作为相对时间和跨日事实的统一解释基准；空结果也要向主 LLM 提供模型提交查询、实际供应商查询、北京时间、搜索源和结构化状态，不能退回一句无上下文的“未找到”。服务端不得增加行业词表、命名实体特判或领域路由。同一用户请求只允许一次 `web_search`；首次查询必须补全已明确的上下文指代并保留对象、时间、地点和事实目标。工具完成后必须关闭当前递归轮的搜索能力；结果为空、跑偏、过旧、证据不足、冲突、配置错误、网络错误或超时时均不得重复搜索，也不得用模型旧知识、常识推断或预测补齐。网页正文和摘要始终属于不可信外部数据，不能覆盖系统规则或诱导工具调用。
```

## 45. 资源中心统一工具提示词目录

```text
定制模块：manager-web 资源中心、manager-api Plugin 目录与配置热刷新、xiaozhi-server 统一工具 schema
改动原因：连接级内置工具、路由工具以及由飞书、知识库或 Intent 白名单启用的 Server Plugin 过去可能真实进入模型 tools schema，却不在资源中心展示；运营人员也无法从统一入口查看和修正工具触发说明。
关键文件：
  - main/manager-web/src/views/FunctionPluginManagement.vue
  - main/manager-web/src/components/resource/PluginResourceCard.vue
  - main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelProviderServiceImpl.java
  - main/manager-api/src/main/java/xiaozhi/modules/agent/service/impl/AgentPluginMappingServiceImpl.java
  - main/manager-api/src/main/java/xiaozhi/modules/config/service/impl/ConfigServiceImpl.java
  - main/manager-api/src/main/resources/db/changelog/202608061600.sql
  - main/xiaozhi-server/core/providers/tools/server_plugins/plugin_executor.py
  - main/xiaozhi-server/core/providers/tools/unified_tool_manager.py
  - main/xiaozhi-server/core/connection.py
是否允许被官方覆盖：否
官方同步时的处理规则：必须保留资源中心作为静态工具提示词唯一目录、catalog_only 工具不进入角色勾选、tool_prompt_overrides 与 plugins 业务配置分离、按工具名覆盖 function description、动态能力说明后追加以及在线会话安全轮次刷新。提示词编辑不得启用工具、绕过 Intent/设备/MCP 门控、改变参数 Schema、覆盖插件业务参数或削弱 MCP SSRF 与权限校验。外部动态工具没有同名目录覆盖时必须继续使用 tools/list 或 descriptor 原始说明。
```

## 46. 设备端 ASR/TTS 混合语音路由

```text
定制模块：manager-api 设备语音策略、Web/Mobile 设备设置、xiaozhi-server 语音能力协商与设备 TTS façade
改动原因：不同设备和场景需要独立选择服务端或设备端语音识别与合成，同时继续复用服务端 SemanticCenter、LLM、工具、聊天记录和 generation 生命周期，不能把设备能力伪装成云端 ASR/TTS provider，也不能因固件能力缺失或播放失败在半轮静默切换链路。
关键文件：
  - main/manager-api/src/main/java/xiaozhi/modules/device/
  - main/manager-api/src/main/java/xiaozhi/modules/config/service/impl/ConfigServiceImpl.java
  - main/manager-api/src/main/resources/db/changelog/202608061730.sql
  - main/xiaozhi-server/core/speech/
  - main/xiaozhi-server/core/handle/helloHandle.py
  - main/xiaozhi-server/core/handle/textHandler/listenMessageHandler.py
  - main/xiaozhi-server/core/handle/textHandler/ttsAckMessageHandler.py
  - main/xiaozhi-server/core/session/runtime_config_reloader.py
  - main/manager-web/src/components/device/DeviceSpeechSettingsDialog.vue
  - main/manager-mobile/src/pages/device/components/DeviceSpeechSettingsPanel.vue
  - docs/architecture/device-interaction-chain.md
  - docs/architecture/runtime-flows.md
  - docs/architecture/data-and-contracts.md
是否允许被官方覆盖：否
官方同步时的处理规则：必须保留设备级 speech_input_mode/speech_output_mode 与运行时 speech_routing.input/output 的 server/device/auto 契约，默认 server/server；设备能力只来自当前已认证 WebSocket 的 hello features.speech，策略与能力解析后的 speech_route 才是当前连接实际路由。显式 device 缺能力必须报 incompatible_route，auto 只在能力完整时选择设备端；设备 TTS 必须同时声明 playback ACK，没有 ACK 时不能声称设备已播放。设备 ASR 只接受 listen.detect 的 source=device_asr、final=true、受校验 turn_id 和非空文本，必须保留幂等缓存、速率限制、speech_asr_ack 和正文日志脱敏。设备 TTS 必须继续通过现有 generation/TTS 队列发送 text/text_end，等待 played/failed/aborted tts_ack；只有 played 正常完成，失败、Abort、文件输入或超时不能伪造完成，也不得在同一半轮切回服务端合成。四种传统输入/输出组合必须继续复用 SemanticCenter、LLM、工具和聊天边界；Realtime 与任一实际设备端语音路由不兼容，不得混合。后台策略更新只能在空闲安全轮次构造候选管线后热切换，失败恢复旧管线；固件能力变化需要重连重新协商。管理端更新必须保留 Workspace 写权限与事务提交后按设备刷新，日志不得记录设备 ASR/TTS 正文、Token、密钥或完整私有配置。
```

## 47. 保留 Harness 后台，移除管理端界面

```text
定制模块：manager-api 维护控制面、production maintenance-agent；manager-web 已移除 Harness UI
改动原因：用户不再需要任何 Harness 界面。移除知识库和记忆管理中的按钮、原生 Console、策略、审批、审计组件及前端请求，但不擅自停止后台策略或删除服务数据。
关键文件：
  - main/manager-api/src/main/java/xiaozhi/modules/maintenance/
  - main/manager-web/src/views/KnowledgeFileUpload.vue
  - main/manager-web/src/views/MemoryManagement.vue
  - main/maintenance-agent/
  - deploy/production/docker-compose.yml
是否允许被官方覆盖：否
官方同步时的处理规则：不得重新加入 Harness UI 或自动恢复其前端请求；普通记忆管理、知识编辑、动态数据源、召回测试必须保留。移除界面不改变部署拓扑、后台接口、策略、提案、审计和原生会话数据；已启用策略不会自动停止，后台停用需要另行授权。Manager API 继续作为权限、配额、审批和最终写入权威。知识维护按 dataset opt-in，记忆维护按 agent opt-in；记忆不能联网或 ADD，UPDATE/ARCHIVE/DELETE 必须人工批准并保留 revision。原生 Console 后台桥只接受经过校验的待审批提案，不能因界面缺失自动批准。
维护策略只保存已启用且测试通过的托管 DeepSeek 模型 ID，运行凭据仅存在本轮进程内存，不复制 API Key。保留共享密钥与绑定 audit run 的 HMAC 能力验证、SSRF、公网 URL/可信域名和配额边界。维护镜像和缓存策略不变。
知识提案批准后保持 APPLYING，事务提交后调用现有连接器，只有抓取、切片和原子发布成功才回写 APPLIED；失败为 FAILED，不伪装入库。REMOVE_SOURCE 必须有精确 sourceId 且人工批准，自动模式不得删除来源。custom_prompt 不能覆盖安全、审批和配额合同。后台日志继续脱敏，不能保存或透传正文、完整提示词、隐藏推理或凭据。
```

## 48. 智能体运行监控摘要

```text
定制模块：manager-web 智能体中心运行监控
改动原因：用户需要在后台查看智能体的近期运行情况，但不能把 Python/容器 stdout、提示词、用户正文或凭据直接透传到浏览器。
关键文件：
  - main/manager-web/src/components/home/AgentRuntimeMonitorDialog.vue
  - main/manager-web/src/components/DeviceItem.vue
  - main/manager-web/src/views/home.vue
  - main/manager-api/.../modules/agent/controller/AgentController.java（复用现有会话查询权限链路）
是否允许被官方覆盖：否
官方同步时的处理规则：运行监控当前只读取已有的、按智能体权限校验的会话摘要接口，展示绑定设备数、最近连接时间、会话数量和最近 20 条会话活动；前端每 5 秒轮询一次，关闭弹窗或切换智能体时必须停止轮询并丢弃过期响应。该页面不是原始日志查看器，不得展示用户正文、完整提示词、Authorization、Token、API Key、模型密钥或容器 stdout。设备“近期活跃”只代表管理端记录的最近连接时间，不得声称设备当前在线。若未来需要 ASR/LLM/TTS/MCP 分阶段诊断，必须新增后端结构化、脱敏、限量的运行事件接口，并继续沿用现有资源所有权和权限校验，不能直接开放文件或容器日志。
```

## 49. 曲库提示词与磁盘实时一致

```text
定制模块：xiaozhi-server 服务器音乐播放插件
改动原因：`MUSIC_CACHE` 是进程内全局缓存，原先只在 `initialize_music_handler()` 首次调用时构建，并且只有在真正执行播放的 `handle_music_command()` 里才按目录 mtime 和 `refresh_time` 判断是否重建。生成 `<music_library>` 曲库上下文和 `play_music` 工具描述的 `list_music_prompt_entries()` 直接返回这份缓存，从不核对磁盘，于是删除歌曲后模型仍按旧曲库回答“给你放一首《…》”，而真正播放时报“未找到MP3音乐文件”，必须等一次播放或重启进程才恢复。现在把失效判断抽成 `_refresh_music_cache_if_stale()`（目录 mtime、元数据 mtime、`refresh_time` 三者任一命中即重建），`initialize_music_handler()` 每次返回缓存前都调用它，提示词与播放共用同一份按磁盘刷新的缓存，`handle_music_command()` 中重复的 mtime 判断同步删除。
关键文件：
  - main/xiaozhi-server/plugins_func/functions/play_music.py
  - main/xiaozhi-server/tests/test_music_intent.py
是否允许被官方覆盖：否
官方同步时的处理规则：曲库缓存（`music_files`、`music_file_names`、`music_metadata`）同时服务播放选择和 `<music_library>`／工具描述提示词，必须在任何取用点之前按磁盘现状刷新：顶层目录 mtime 变化、`.xiaozhi-music-metadata.json` mtime 变化、或距上次扫描超过 `refresh_time`（默认 60 秒）三者任一命中都要重建。不得只在播放路径刷新，也不得为省一次目录扫描而让提示词长期保留已删除歌曲。子目录内增删不改变顶层目录 mtime，因此 `refresh_time` 兜底扫描不可删除。曲库元数据按客户曲库在后台生成，不得提交进仓库。
```

## 50. 工作空间设备中心与直接切换智能体

定制模块：manager-web 的 `DeviceCenter.vue`、`device/DeviceAgentCard.vue`、`utils/deviceCenter.js`。
设备管理导航按工作空间汇总设备卡片，支持搜索、智能体筛选和添加设备；卡片通过现有设备场景接口直接切换智能体。不得改成解绑重绑或绕过服务端场景权限与模型验证。切换结果必须以服务端审计为准，保留失败、回滚及等待状态；不得用最近连接时间推断在线。同步时保留原设备设置入口和新增设备中心。详见 `docs/architecture/device-scene-management.md`。

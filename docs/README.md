# 文档分类索引

本目录用于管理项目规范、部署说明、设备固件、MCP、模型能力、第三方集成和参考资料。AI 编程工具和开发者不得把 `docs/` 下所有文件都当作开工必读；必须先判断任务类型，再读取对应分类文档。

本文档是文档入口。每个条目都说明该文档解决的问题、适用场景，以及 AI 是否需要在对应任务中读取。

## 1. AI 开发必读

AI 编程工具在修改代码、配置、部署脚本或文档前，必须先读取：

```text
AGENTS.md
README.md
docs/README.md
docs/standards/development-specification.md
docs/standards/ai-coding-agent-instructions.md
```

如果存在本地定制记录，还必须读取：

```text
docs/local-customizations.md
```

如果修改具体模块，还必须读取对应模块 README：

```text
main/manager-api/README.md
main/manager-web/README.md
main/manager-mobile/README.md
```

如果任务涉及数据库，还必须检查：

```text
main/manager-api/src/main/resources/db/changelog/
main/manager-api/src/main/resources/db/changelog/db.changelog-master.yaml
```

如果任务涉及运行配置、端口、环境变量或本地/服务器差异，还必须检查：

```text
scripts/dev
main/xiaozhi-server/config.yaml
main/xiaozhi-server/config_from_api.yaml
main/manager-api/src/main/resources/application.yml
main/manager-api/src/main/resources/application-dev.yml
```

如果任务涉及云端运行链路、后台配置下发、提示词注入、插件工具、MCP 或设备实时对话，还必须读取：

```text
docs/architecture/project-architecture.md
docs/architecture/cloud-runtime-architecture.md
docs/architecture/semantic-control-center.md
docs/architecture/prompt-injection-engineering.md
```

## 2. 强制规范

这些文档是规则，不是建议。任何开发、部署、同步官方上游、整理文档的任务都必须遵守。

- [standards/development-specification.md](./standards/development-specification.md) - 项目开发总规范。规定本地仓库、私有远程仓库、官方上游仓库的职责边界；规定标准远端命名、分支规则、提交流程、数据库变更、配置隔离、阿里云部署、生产发布和官方更新同步规则。AI 修改代码前必须读取。
- [standards/ai-coding-agent-instructions.md](./standards/ai-coding-agent-instructions.md) - AI 编程工具执行规范。规定 AI 在新开发功能、修改功能、修复问题、同步官方更新、整理文档时必须读取哪些文件、如何识别真实私有远端、如何推送当前分支、可以自动执行哪些操作、哪些操作必须先获得用户授权。所有 AI 编程任务必须读取。
- [standards/config-runtime-policy.md](./standards/config-runtime-policy.md) - 配置与运行产物治理策略。用于区分默认模板、示例配置、环境私有配置、运行时生成配置，规定 `config/examples/` 和 `runtime/` 的提交边界。
- [standards/upstream-sync-policy.md](./standards/upstream-sync-policy.md) - 官方上游同步策略。用于同步官方 `upstream` 前确认官方源码、本地定制和私有运行边界，处理高风险冲突和同步后验证。
- [standards/documentation-policy.md](./standards/documentation-policy.md) - 文档治理策略。用于新增、移动、重命名、索引文档时确认分类、命名、链接检查和部署资产边界。
- [standards/README.md](./standards/README.md) - 规范目录入口。用于快速确认 `standards/` 下每份规则文档的职责和使用顺序，适合开发者或 AI 在进入规范目录时先阅读。
- [local-customizations.md](./local-customizations.md) - 本地定制记录。用于记录相对官方仓库的长期本地改动，官方同步、冲突处理、重构插件或整理本地扩展时必须读取。

## 3. 架构与运行链路

用于理解端云协同、后台配置下发、Python 实时服务、提示词注入、插件工具、MCP 和设备运行边界。AI 修改实时对话、插件、提示词、MCP、OTA 接入、设备连接、后台配置下发时必须读取。

- [architecture/project-architecture.md](./architecture/project-architecture.md) - 项目完整架构与文档导航。用于理解模块边界、端云链路、配置下发、提示词/工具/MCP 注入、运行产物、部署边界和开发治理边界。
- [architecture/cloud-runtime-architecture.md](./architecture/cloud-runtime-architecture.md) - 云端运行架构与配置链路。用于理解 `manager-web`、`manager-mobile`、`manager-api`、`xiaozhi-server` 和设备端的边界，说明后台配置如何变成设备行为、实时语音链路如何运行、工具/MCP/天气/OTA 如何接入。
- [architecture/semantic-control-center.md](./architecture/semantic-control-center.md) - 语义控制中枢架构。用于理解确定性语音控制、文本标准化、语义路由、状态管理、策略判断、拒识和旧 LLM/function-call 兜底之间的关系。
- [architecture/long-term-memory-maintenance.md](./architecture/long-term-memory-maintenance.md) - 长期记忆维护架构。用于理解 manager-api MySQL 事实层、Python 事件上报与查询注入、Web 记忆管理页、显式记住/忘记、审计和后续向量/图谱增强边界。
- [architecture/model-benchmark-and-assistant.md](./architecture/model-benchmark-and-assistant.md) - 模型专业评测与配置智能助手架构。用于理解模型批量真实评测、LLMJudge、真实设备运行时评测、模型配置助手、接入草案写入、残留草案清理和提交治理边界。
- [architecture/model-selection-and-vendor-catalog.md](./architecture/model-selection-and-vendor-catalog.md) - 模型选择与厂商目录架构。用于把讯飞、豆包等厂商产品、模型系列、协议、适配状态、推荐场景和真实测试要求整理成可选择目录，避免用户在模型配置页猜 adapter。
- [architecture/current-code-map.md](./architecture/current-code-map.md) - 当前代码地图与梳理报告。用于快速了解当前仓库模块结构、运行链路、未提交能力、风险和验证矩阵，是后续开发前的代码导航。
- [architecture/semantic-interruption-and-e2e-voice-model-analysis.md](./architecture/semantic-interruption-and-e2e-voice-model-analysis.md) - 语义打断与端到端语音模型演进分析。用于评估当前打断机制局限、语义打断方案和端到端语音模型演进路线。
- [architecture/function-plugin-architecture.md](./architecture/function-plugin-architecture.md) - 功能插件架构。用于理解智能体功能库如何统一管理天气、新闻、音乐播放、HomeAssistant、讲段子、热梗、成语接龙、你说我猜等能力，区分系统能力、互动玩法和系统能力二级分类。
- [architecture/stateful-game-session.md](./architecture/stateful-game-session.md) - 状态机游戏会话逻辑。用于理解"你说我猜""我想你猜""成语接龙"的触发、状态保存、每轮判定、提示、退出和扩展规则。
- [architecture/prompt-injection-engineering.md](./architecture/prompt-injection-engineering.md) - 提示词注入工程。用于理解系统提示词、工具描述、声明式插件提示词、状态机游戏规则如何进入大模型，以及为什么状态机玩法不再依赖后台大段 prompt。
- [architecture/device-interaction-chain.md](./architecture/device-interaction-chain.md) - 设备交互完整链路。用于理解 ESP32 设备从 WebSocket 连接到音频上行/下行、IoT 描述符注册、Device MCP、OTA 升级和连接生命周期管理的完整流程。
- [architecture/memory-system-chain.md](./architecture/memory-system-chain.md) - 记忆系统完整链路。用于理解 Python Memory Provider → Java PowerMem 双阶段提取决策 → MySQL 持久化 → LLM 上下文注入的端到端记忆管道。
- [architecture/mcp-chains.md](./architecture/mcp-chains.md) - MCP 三类链路架构。用于理解外部 MCP Endpoint、服务器侧 MCP、设备侧 MCP 的工具发现、注册、调用和 BaseMCPClient 共享基类。
- [architecture/knowledge-base-chain.md](./architecture/knowledge-base-chain.md) - 知识库完整链路。用于理解文件上传 → LocalKnowledge/RAGFlow 分块向量化 → 混合检索 → LLM 工具注入的端到端知识库流程。
- [architecture/workspace-organization-management.md](./architecture/workspace-organization-management.md) - 工作空间组织管理与数据隔离。用于理解用户注册默认工作空间、成员角色权限、智能体/设备/记忆/知识库等业务资源共享范围、服务端鉴权和跨工作空间泄露防护。

## 4. 本地开发环境

用于开发机初始化、源码编译环境准备、IDE 调试前的依赖安装和本地服务启动顺序。AI 只有在修改本地开发环境、源码编译说明、开发机依赖或环境排查文档时，才需要读取本分类文档。

- [development/ubuntu-source-development-environment.md](./development/ubuntu-source-development-environment.md) - Ubuntu 源码开发环境安装说明。用于在 Ubuntu 22.04/24.04 本地开发机上安装 JDK 21、Maven、Node.js、pnpm、Conda、MySQL、Redis、FFmpeg 和项目依赖，并说明本地配置、模型目录、启动顺序和验证命令。
- [development/local-dev-workflow.md](./development/local-dev-workflow.md) - 本地开发工作流。用于使用 `scripts/dev` 统一启动、停止、查看状态、查看日志和执行环境体检，替代零散启动命令作为日常入口。
- [development/manual-startup.md](./development/manual-startup.md) - 手动开发启动说明。用于快速查看 `api`、`web`、`server` 的手动启动入口、访问地址、日志目录和 PID 目录。

## 5. 部署与运维

用于服务器安装、Docker 构建、全模块部署、自动更新、生产运行和部署端口配置。AI 只有在修改部署、Docker、服务器脚本、端口、Nginx、生产发布流程时，才需要读取本分类文档。

- [deployment/private-docker-release.md](./deployment/private-docker-release.md) - 本项目唯一生产部署入口。源码仍完整推送到私有 Git 仓库，本地或构建机使用完整源码构建 `xiaozhi-server` / `xiaozhi-web` 镜像并推送到阿里云 ACR；生产服务器只通过 sparse-checkout 拉取 `deploy/production` 最小部署资产，再拉取已发布镜像并用 Docker Compose 启动。包含 `scripts/acr-build-push` 并行构建、默认自动复用 `xiaozhi-server-base` 缓存、运行时镜像自动同步/跳过和排查日志入口。
- [deployment/deployment-asset-policy.md](./deployment/deployment-asset-policy.md) - 部署资产治理策略。用于区分 `docs/deployment/`、`deploy/`、`scripts/` 的职责，规定 Docker、服务器安装升级和 release 发布资产的归档边界。
- [deployment/Deployment.md](./deployment/Deployment.md) - 官方单 Server Docker 兼容说明。仅用于参考官方默认部署方式，不作为本项目私有生产部署主入口。
- [deployment/Deployment_all.md](./deployment/Deployment_all.md) - 官方全模块 Docker 兼容说明。仅用于参考官方默认部署方式，不作为本项目私有生产部署主入口。
- [deployment/dev-ops-integration.md](./deployment/dev-ops-integration.md) - 历史源码部署参考。仅用于理解旧流程，不作为本项目生产入口。
- [deployment/docker-build.md](./deployment/docker-build.md) - 历史本地 Docker 构建参考。仅用于排查旧镜像命名或官方兼容问题，不作为本项目生产入口。
- [deployment/local-docker-deploy-guide.md](./deployment/local-docker-deploy-guide.md) - 本地 Docker 部署指南。用于本地或测试环境构建镜像、Docker Compose 启动、初始化配置和排查，不替代生产 ACR 部署入口。
- [deployment/paddlespeech-deploy.md](./deployment/paddlespeech-deploy.md) - PaddleSpeech TTS 部署与接入指南。用于安装 PaddleSpeech 服务、启动 TTS 服务并把它接入小智服务端的场景。

部署文档优先级：

```text
唯一生产部署入口：deployment/private-docker-release.md
部署资产边界：deployment/deployment-asset-policy.md
官方兼容参考：deployment/Deployment.md / Deployment_all.md，不作为本项目生产入口
历史源码升级：deployment/dev-ops-integration.md，不作为本项目生产入口
```

部署资产与脚本入口：

- [../deploy/README.md](../deploy/README.md) - `deploy/` 目录治理边界，声明 `docker/`、`server/`、`release/` 三个分域及其与 `docs/deployment/`、`scripts/` 的关系。
- [../deploy/docker/README.md](../deploy/docker/README.md) - Docker 构建与容器运行资产归档边界。
- [../deploy/server/README.md](../deploy/server/README.md) - 服务器安装、升级、回滚与运行时目录初始化资产归档边界。
- [../deploy/release/README.md](../deploy/release/README.md) - 镜像发布、版本打标、私有仓库推送与发布校验资产归档边界。
- [../deploy/production/README.md](../deploy/production/README.md) - 最小生产部署包说明。描述 `/opt/xiaozhi-data` 持久化目录、标准生产命令和容器间网络调用规则。
- [../scripts/README.md](../scripts/README.md) - 脚本入口说明。列出常用本地开发、ACR 构建推送和生产拉取部署命令，以及脚本分组与生产约束。

## 6. 设备与固件

用于 ESP32 固件、设备配置、OTA、MQTT 网关和设备互呼。AI 只有在修改设备协议、OTA、MQTT、设备呼叫或固件相关逻辑时，才需要读取本分类文档。

- [device/firmware-build.md](./device/firmware-build.md) - ESP32 固件编译指南。用于准备 OTA 地址、配置编译环境、生成自定义固件并让设备连接自己的服务器。
- [device/firmware-setting.md](./device/firmware-setting.md) - 使用已编译固件配置自定义服务器指南。用于不重新编译固件，只通过配网、OTA 地址和设备设置把现有固件指向自建服务端。
- [device/ota-upgrade-guide.md](./device/ota-upgrade-guide.md) - 单模块部署的固件 OTA 自动升级配置指南。用于准备固件文件、配置 OTA 服务、让设备通过服务端自动升级。
- [device/device-call-guide.md](./device/device-call-guide.md) - 设备间相互呼叫插件使用指南。用于配置设备互呼能力、理解启用前提、插件配置和调用流程。
- [device/mqtt-gateway-integration.md](./device/mqtt-gateway-integration.md) - MQTT 网关部署教程。用于部署 MQTT 网关、连接设备消息通道、配置网关与小智服务端之间的通信。
- [device/websocket-mcp-boot-connection-change.md](./device/websocket-mcp-boot-connection-change.md) - 设备开机后常驻 WebSocket 与 MCP 可见性改动说明。用于理解设备端主动连接、重连、空闲常驻连接和 MCP 初始化时序。

## 7. MCP 与工具调用

用于 MCP 接入点、MCP 工具、设备信息获取、视觉能力和上下文注入。AI 只有在修改 MCP、工具调用、视觉接口、上下文注入相关功能时，才需要读取本分类文档。

- [mcp/mcp-endpoint-enable.md](./mcp/mcp-endpoint-enable.md) - MCP 接入点部署使用指南。用于部署 MCP endpoint 服务、启动程序、配置服务端与智能体的 MCP 入口。
- [mcp/mcp-endpoint-integration.md](./mcp/mcp-endpoint-integration.md) - MCP 接入点功能接入指南。用于给智能体接入计算器等 MCP 工具，区分全模块部署和单模块部署的配置方式。
- [mcp/mcp-get-device-info.md](./mcp/mcp-get-device-info.md) - MCP 方法获取设备信息说明。用于开发 MCP 工具时获取设备上下文、识别设备来源和处理设备相关参数。
- [mcp/mcp-vision-integration.md](./mcp/mcp-vision-integration.md) - 视觉模型使用指南。用于开启服务端视觉模型、选择视觉模型供应商、配置视觉接口并排查网络可用性。
- [mcp/context-provider-integration.md](./mcp/context-provider-integration.md) - 上下文源使用教程。用于为智能体注入外部上下文、理解上下文源接口规范、配置服务端调用上下文提供方。
- [mcp/no-code-http-tools.md](./mcp/no-code-http-tools.md) - 可视化 MCP HTTP 工具。用于理解智控台配置 HTTP 工具、manager-api 保存配置、Python 桥接脚本注册 MCP tools 的完整链路和安全边界。

## 8. 模型、语音与记忆

用于 ASR、TTS、声纹、记忆、知识库、RAG 和模型性能测试。AI 只有在修改 ASR、TTS、声纹、记忆、RAG、性能测试相关功能时，才需要读取本分类文档。

- [models/voiceprint-integration.md](./models/voiceprint-integration.md) - 声纹识别启用指南。用于部署声纹识别服务、创建数据库表、配置服务端声纹能力并启用说话人识别。
- [models/fish-speech-integration.md](./models/fish-speech-integration.md) - Fish Speech 集成说明。用于把 Fish Speech 作为语音合成能力接入小智服务端，处理 TTS 服务部署和配置项。
- [models/huoshan-streamTTS-voice-cloning.md](./models/huoshan-streamTTS-voice-cloning.md) - 火山引擎双流式 TTS 与音色克隆配置教程。用于开通火山服务、获取音色资源 ID、配置流式语音合成和音色克隆。
- [models/index-stream-integration.md](./models/index-stream-integration.md) - IndexStreamTTS 使用指南。用于部署 IndexStreamTTS 环境、配置 Conda 环境、启动服务并接入流式 TTS。
- [models/powermem-integration.md](./models/powermem-integration.md) - PowerMem 记忆组件集成指南。用于给智能体接入长期记忆能力，包含组件安装、功能特性和服务配置。
- [models/local-knowledge-integration.md](./models/local-knowledge-integration.md) - 本地知识库与大模型切片指南。用于配置 `RAG_LocalKnowledge`、在知识库界面配置阿里云百炼/OpenAI 兼容切片模型、重新解析旧文档和排查本地知识库召回为空。
- [models/memory-and-knowledge-governance.md](./models/memory-and-knowledge-governance.md) - 记忆系统与知识库治理保证。用于约束 `managed_memory`、`maintenance_llm`、PowerMem 借鉴点、知识库 LLM 切片模型选择、UI 逻辑、数据库迁移和后续验证边界。
- [models/model-management.md](./models/model-management.md) - 模型管理存储与保存架构。用于对照智控台模型配置页面，理解配置名称、模型编码、上游模型、厂商产品、普通参数、密钥参数、模板字段、保存接口、数据库表和运行时下发协议。
- [models/model-management-architecture-analysis.md](./models/model-management-architecture-analysis.md) - 模型管理与配置体系完整分析。用于梳理模型类型、适配器、协议、新旧配置链路和模型注册发现机制的历史问题。
- [models/model-config-catalog.md](./models/model-config-catalog.md) - 标准模型配置目录与治理说明。用于梳理 ASR、TTS、LLM、VLLM、SLM、Memory、Intent、RAG、VAD、声纹和插件 RAG 的厂商、产品、能力、接入方式、标准字段、参数/密钥分表和运行时 `model_config` 契约。
- [models/doubao-tts-voice-selection-analysis.md](./models/doubao-tts-voice-selection-analysis.md) - 豆包 TTS 音色选择问题分析。用于理解管理端音色列表只显示单一音色的数据库来源和后续修复方向。
- [models/memory-summary-display-issue-analysis.md](./models/memory-summary-display-issue-analysis.md) - 短期记忆总结显示问题分析。用于排查 `mem_local_short` 保存路径与角色配置界面显示不一致的问题。
- [models/voice-pipeline-latency-analysis.md](./models/voice-pipeline-latency-analysis.md) - 语音管道延迟分析。用于拆解 ASR、LLM、TTS、音频发送和 UI 展示的延迟组成。
- [models/volume-system-analysis.md](./models/volume-system-analysis.md) - 音量系统混乱分析。用于区分设备扬声器音量、TTS 合成音量和浏览器播放音量的边界。
- [models/funasr-sensevoice-resource-benchmark.md](./models/funasr-sensevoice-resource-benchmark.md) - FunASR / SenseVoiceSmall 本地语音识别资源与并发测试报告。用于确认 ACR 生产镜像中本地 ASR 的最低内存、推荐 CPU/内存配置和多人同时识别时的延迟曲线。
- [models/ragflow-integration.md](./models/ragflow-integration.md) - RAGFlow 集成指南。用于部署 RAGFlow、检查 MySQL/Redis、配置知识库问答和服务端 RAG 调用。
- [models/performance_tester.md](./models/performance_tester.md) - 模型性能测试工具使用指南。用于测试 ASR、大语言模型、非流式 TTS、流式 TTS、视觉模型的延迟和稳定性。

## 9. 插件与第三方集成

用于天气、短信、Home Assistant、新闻源等外部服务能力。AI 只有在修改对应插件、第三方 API 或配置项时，才需要读取本分类文档。

- [integrations/weather-integration.md](./integrations/weather-integration.md) - 天气插件使用指南。用于申请和风天气 API Key、配置天气服务、启用天气查询插件。
- [integrations/ali-sms-integration.md](./integrations/ali-sms-integration.md) - 阿里云短信集成指南。用于添加短信签名、短信模板、创建短信账号、开通权限并配置短信发送能力。
- [integrations/homeassistant-integration.md](./integrations/homeassistant-integration.md) - Home Assistant 集成指南。用于把小智 ESP32 开源服务端接入 Home Assistant，实现智能家居设备控制。
- [integrations/newsnow_plugin_config.md](./integrations/newsnow_plugin_config.md) - NewsNow 新闻源插件配置指南。用于配置新闻源、通过 Web 管理界面维护新闻插件、调整新闻查询来源。

## 10. 通用参考

用于问题排查、贡献说明和图片资源。AI 只有在整理文档、排查问题或更新 README 时，才需要读取本分类文档。

- [reference/FAQ.md](./reference/FAQ.md) - 常见问题排查文档。用于处理语音识别异常、TTS 错误、超时、服务启动失败、配置不生效等常见问题。
- [reference/system-issues-and-optimization-backlog.md](./reference/system-issues-and-optimization-backlog.md) - 系统问题与优化清单。用于集中查看音量、语音延迟、TTS 音色、记忆显示、聊天记录和模型管理等已识别问题。
- [reference/contributor_open_letter.md](./reference/contributor_open_letter.md) - 开发者公开信与贡献指南。用于理解官方项目目标、贡献方式、提交内容要求和社区协作方式。
- [images/](./images/) - 文档图片资源目录。用于部署架构图、截图和说明图片；修改引用图片或移动文档时必须检查图片路径。

## 11. AI 读取规则

AI 必须按任务读取文档：

```text
所有任务：AGENTS.md + README.md + docs/README.md + docs/standards/development-specification.md + docs/standards/ai-coding-agent-instructions.md
模块任务：对应模块 README
数据库任务：manager-api db/changelog
配置任务：scripts/dev + config.yaml / config_from_api.yaml / application*.yml
架构/提示词/插件/MCP/语义控制任务：docs/architecture/* + docs/architecture/prompt-injection-engineering.md
配置/运行产物治理任务：docs/standards/config-runtime-policy.md
文档治理任务：docs/standards/documentation-policy.md
官方同步任务：docs/standards/upstream-sync-policy.md
开发环境任务：docs/development/*
部署任务：docs/deployment/*
部署资产任务：docs/deployment/deployment-asset-policy.md + deploy/*
设备任务：docs/device/*
MCP 任务：docs/mcp/*
模型任务：docs/models/*
集成任务：docs/integrations/*
文档任务：docs/README.md + docs/standards/documentation-policy.md + 对应分类文档
```

AI 禁止：

- 无任务目标时批量读取全部文档。
- 为了完成小修改加载所有集成教程。
- 未读强制规范就修改代码。
- 直接使用系统 `python`、`python3`、`pip` 或 `pytest` 验证 `main/xiaozhi-server`。
- 修改官方文档位置导致旧链接失效。
- 把生产数据库真实连接信息、密码、Token、AccessKey 写入 Git。
- 未识别当前分支和真实私有远端就执行 push。

## 12. 文档移动规则

官方原有文档已按主题移动到分类目录。移动后必须维护相对链接，避免破坏图片路径和文档互链。

模块目录 README 可以留在模块根目录，作为该模块开发入口。例如：

```text
main/README.md
main/manager-api/README.md
main/manager-web/README.md
main/manager-mobile/README.md
```

通用专题、架构、部署教程、排障记录、设备/MCP/模型/集成说明必须进入 `docs/` 对应分类。不要在 `main/` 下新增通用 `doc/` 或 `docs/` 目录。

本地新增的强制规范统一放在：

```text
docs/standards/
```

新增或移动文档时必须遵守：

```text
docs/standards/documentation-policy.md
```

本地运行产物统一放在：

```text
runtime/
```

部署资产后续统一归档到：

```text
deploy/
```

后续如果要移动官方文档，必须先统一检查并修复所有内部链接。

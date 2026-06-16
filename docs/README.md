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
docs/integrations/prompt-injection-engineering.md
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
- [architecture/cloud-runtime-architecture.md](./architecture/cloud-runtime-architecture.md) - 云端运行架构与配置链路。用于理解 `manager-web`、`manager-mobile`、`manager-api`、`xiaozhi-server`、`digital-human` 和设备端的边界，说明后台配置如何变成设备行为、实时语音链路如何运行、工具/MCP/天气/OTA 如何接入。
- [architecture/semantic-control-center.md](./architecture/semantic-control-center.md) - 语义控制中枢架构。用于理解确定性语音控制、文本标准化、语义路由、状态管理、策略判断、拒识和旧 LLM/function-call 兜底之间的关系。
- [architecture/current-code-map.md](./architecture/current-code-map.md) - 当前代码地图与梳理报告。用于快速了解当前仓库模块结构、运行链路、未提交能力、风险和验证矩阵，是后续开发前的代码导航。

## 4. 本地开发环境

用于开发机初始化、源码编译环境准备、IDE 调试前的依赖安装和本地服务启动顺序。AI 只有在修改本地开发环境、源码编译说明、开发机依赖或环境排查文档时，才需要读取本分类文档。

- [development/ubuntu-source-development-environment.md](./development/ubuntu-source-development-environment.md) - Ubuntu 源码开发环境安装说明。用于在 Ubuntu 22.04/24.04 本地开发机上安装 JDK 21、Maven、Node.js、pnpm、Conda、MySQL、Redis、FFmpeg 和项目依赖，并说明本地配置、模型目录、启动顺序和验证命令。
- [development/local-dev-workflow.md](./development/local-dev-workflow.md) - 本地开发工作流。用于使用 `scripts/dev` 统一启动、停止、查看状态、查看日志和执行环境体检，替代零散启动命令作为日常入口。
- [development/manual-startup.md](./development/manual-startup.md) - 手动开发启动说明。用于快速查看 `api`、`web`、`server` 的手动启动入口、访问地址、日志目录和 PID 目录。

## 5. 部署与运维

用于服务器安装、Docker 构建、全模块部署、自动更新、生产运行和部署端口配置。AI 只有在修改部署、Docker、服务器脚本、端口、Nginx、生产发布流程时，才需要读取本分类文档。

- [deployment/Deployment.md](./deployment/Deployment.md) - 单 Server Docker 部署指南。用于只部署 `xiaozhi-server` 服务端的场景，包含服务器目录、配置文件、模型文件、Docker Compose、运行和重启步骤。
- [deployment/Deployment_all.md](./deployment/Deployment_all.md) - 全模块 Docker 部署指南。用于同时部署 Server、管理后台、数据库、Redis 等完整服务栈的场景，包含懒人脚本和手动部署两种方式。
- [deployment/dev-ops-integration.md](./deployment/dev-ops-integration.md) - 全模块源码部署自动升级方法。用于从源码目录进行部署、拉取更新、执行自动升级脚本和维护服务器运行目录的场景。
- [deployment/docker-build.md](./deployment/docker-build.md) - 本地编译 Docker 镜像方法。用于官方镜像架构不匹配、需要自建 `server` 或 `web` 镜像、需要在本机或服务器构建镜像的场景。
- [deployment/private-docker-release.md](./deployment/private-docker-release.md) - 私有 Docker 镜像发布与生产部署。用于把本仓库二次开发代码构建为私有镜像、推送到私有镜像仓库，并在生产服务器用 Docker Compose 拉取运行。
- [deployment/deployment-asset-policy.md](./deployment/deployment-asset-policy.md) - 部署资产治理策略。用于区分 `docs/deployment/`、`deploy/`、`scripts/` 的职责，规定 Docker、服务器安装升级和 release 发布资产的归档边界。
- [deployment/all-in-one-digital-human-setup.md](./deployment/all-in-one-digital-human-setup.md) - 一体机数字人部署配置指南。用于数字人一体机环境初始化、网络配置、服务启动和数字人部署流程。
- [deployment/paddlespeech-deploy.md](./deployment/paddlespeech-deploy.md) - PaddleSpeech TTS 部署与接入指南。用于安装 PaddleSpeech 服务、启动 TTS 服务并把它接入小智服务端的场景。

部署文档优先级：

```text
私有生产发布：deployment/private-docker-release.md
部署资产边界：deployment/deployment-asset-policy.md
官方兼容部署：deployment/Deployment.md / Deployment_all.md
历史源码升级：deployment/dev-ops-integration.md
```

## 6. 设备与固件

用于 ESP32 固件、设备配置、OTA、MQTT 网关和设备互呼。AI 只有在修改设备协议、OTA、MQTT、设备呼叫或固件相关逻辑时，才需要读取本分类文档。

- [device/firmware-build.md](./device/firmware-build.md) - ESP32 固件编译指南。用于准备 OTA 地址、配置编译环境、生成自定义固件并让设备连接自己的服务器。
- [device/firmware-setting.md](./device/firmware-setting.md) - 使用已编译固件配置自定义服务器指南。用于不重新编译固件，只通过配网、OTA 地址和设备设置把现有固件指向自建服务端。
- [device/ota-upgrade-guide.md](./device/ota-upgrade-guide.md) - 单模块部署的固件 OTA 自动升级配置指南。用于准备固件文件、配置 OTA 服务、让设备通过服务端自动升级。
- [device/device-call-guide.md](./device/device-call-guide.md) - 设备间相互呼叫插件使用指南。用于配置设备互呼能力、理解启用前提、插件配置和调用流程。
- [device/mqtt-gateway-integration.md](./device/mqtt-gateway-integration.md) - MQTT 网关部署教程。用于部署 MQTT 网关、连接设备消息通道、配置网关与小智服务端之间的通信。

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
- [models/ragflow-integration.md](./models/ragflow-integration.md) - RAGFlow 集成指南。用于部署 RAGFlow、检查 MySQL/Redis、配置知识库问答和服务端 RAG 调用。
- [models/performance_tester.md](./models/performance_tester.md) - 模型性能测试工具使用指南。用于测试 ASR、大语言模型、非流式 TTS、流式 TTS、视觉模型的延迟和稳定性。

## 9. 插件与第三方集成

用于天气、短信、Home Assistant、新闻源等外部服务能力。AI 只有在修改对应插件、第三方 API 或配置项时，才需要读取本分类文档。

- [integrations/weather-integration.md](./integrations/weather-integration.md) - 天气插件使用指南。用于申请和风天气 API Key、配置天气服务、启用天气查询插件。
- [integrations/ali-sms-integration.md](./integrations/ali-sms-integration.md) - 阿里云短信集成指南。用于添加短信签名、短信模板、创建短信账号、开通权限并配置短信发送能力。
- [integrations/homeassistant-integration.md](./integrations/homeassistant-integration.md) - Home Assistant 集成指南。用于把小智 ESP32 开源服务端接入 Home Assistant，实现智能家居设备控制。
- [integrations/newsnow_plugin_config.md](./integrations/newsnow_plugin_config.md) - NewsNow 新闻源插件配置指南。用于配置新闻源、通过 Web 管理界面维护新闻插件、调整新闻查询来源。
- [integrations/bar-drink-image-recommendation.md](./integrations/bar-drink-image-recommendation.md) - 酒类推荐图片展示集成。用于配置 `recommend_drink` 插件向硬件端 MCP 图片展示工具发送酒水外观图，包含图片来源、工具参数和缓存约定。
- [integrations/function-plugin-architecture.md](./integrations/function-plugin-architecture.md) - 功能插件架构。用于理解智能体功能库如何统一管理天气、新闻、音乐播放、HomeAssistant、讲段子、热梗、成语接龙、你说我猜等能力，区分系统能力、互动玩法和系统能力二级分类。
- [integrations/stateful-game-session.md](./integrations/stateful-game-session.md) - 状态机游戏会话逻辑。用于理解“你说我猜”“我想你猜”“成语接龙”的触发、状态保存、每轮判定、提示、退出和扩展规则。
- [integrations/prompt-injection-engineering.md](./integrations/prompt-injection-engineering.md) - 提示词注入工程。用于理解系统提示词、工具描述、声明式插件提示词、状态机游戏规则如何进入大模型，以及为什么状态机玩法不再依赖后台大段 prompt。

## 10. 数字人与唤醒词

用于数字人启动、唤醒词模型和相关体验配置。AI 只有在修改数字人、唤醒词、展示端体验相关逻辑时，才需要读取本分类文档。

- [digital-human/digital-human-wakeword.md](./digital-human/digital-human-wakeword.md) - 数字人启动与唤醒词配置说明。用于下载唤醒词模型、配置数字人启动方式、处理数字人与语音唤醒之间的联动。
- [deployment/all-in-one-digital-human-setup.md](./deployment/all-in-one-digital-human-setup.md) - 一体机数字人部署配置指南。用于数字人一体机的系统初始化、网络配置、服务安装和部署流程。

## 11. 通用参考

用于问题排查、贡献说明和图片资源。AI 只有在整理文档、排查问题或更新 README 时，才需要读取本分类文档。

- [reference/FAQ.md](./reference/FAQ.md) - 常见问题排查文档。用于处理语音识别异常、TTS 错误、超时、服务启动失败、配置不生效等常见问题。
- [reference/contributor_open_letter.md](./reference/contributor_open_letter.md) - 开发者公开信与贡献指南。用于理解官方项目目标、贡献方式、提交内容要求和社区协作方式。
- [images/](./images/) - 文档图片资源目录。用于部署架构图、截图和说明图片；修改引用图片或移动文档时必须检查图片路径。

## 12. AI 读取规则

AI 必须按任务读取文档：

```text
所有任务：AGENTS.md + README.md + docs/README.md + docs/standards/development-specification.md + docs/standards/ai-coding-agent-instructions.md
模块任务：对应模块 README
数据库任务：manager-api db/changelog
配置任务：scripts/dev + config.yaml / config_from_api.yaml / application*.yml
架构/提示词/插件/MCP/语义控制任务：docs/architecture/* + docs/integrations/prompt-injection-engineering.md
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

## 13. 文档移动规则

官方原有文档已按主题移动到分类目录。移动后必须维护相对链接，避免破坏图片路径和文档互链。

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

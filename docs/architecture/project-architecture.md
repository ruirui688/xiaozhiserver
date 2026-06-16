# 项目完整架构与文档导航

本文是 `xiaozhi-esp32-server` 的项目级架构总览，用来回答“这个仓库由哪些边界组成、一次设备对话经过哪些链路、配置和提示词如何进入运行时、哪些文件属于源码或运行产物”。它不替代专项文档：

- 云端实时链路、配置下发和 MCP 三类链路细节见 [云端运行架构与配置链路](./cloud-runtime-architecture.md)。
- 确定性语音控制、状态管理、拒识和旧 LLM 兜底链路见 [语义控制中枢架构](./semantic-control-center.md)。
- 提示词、工具 schema、MCP description、状态机游戏的注入细节见 [提示词注入工程](../integrations/prompt-injection-engineering.md)。
- 本地二次开发长期保护点见 [本地定制记录](../local-customizations.md)。

## 1. 总体架构

本项目是一套端云协同语音智能体平台，不是单一后台系统。生产主链路由设备、Python 实时服务、Java 管理后台、Web/移动管理端、数据与部署层共同组成。

```text
ESP32 设备 / MQTT 网关
  -> OTA 获取连接地址和设备配置入口
  -> WebSocket 或 MQTT/UDP 接入 xiaozhi-server
  -> Python 实时对话、模型调用、工具调度、音频回传

manager-web / manager-mobile
  -> 管理智能体、模型、音色、提示词、插件、知识库、设备、OTA、MCP
  -> 调用 manager-api

manager-api
  -> 配置事实来源、设备绑定、模型编排、系统参数、OTA、MCP Endpoint
  -> MySQL / Redis
  -> 向 xiaozhi-server 下发设备私有运行配置
```

设备最终行为由四类输入共同决定：

```text
设备固件能力和 OTA 地址
后台保存的智能体、模型、插件、MCP、上下文源和设备绑定配置
xiaozhi-server 会话内组装的 system prompt、messages、tools 和状态机
部署环境中的私有配置、端口、域名、证书、模型文件和运行数据
```

## 2. 模块边界

| 模块 | 主要职责 | 边界规则 |
| --- | --- | --- |
| `main/xiaozhi-server` | Python 核心实时服务；处理设备 WebSocket/MQTT 接入、Opus 音频、VAD、ASR、LLM、TTS、Memory、Intent、插件、IoT、MCP、OTA HTTP 和视觉接口 | 直接参与设备实时对话；配置文件只能保存模板或默认值，私有运行配置不得提交 |
| `main/manager-api` | Java Spring Boot 管理后台；保存用户、设备、智能体、模型、音色、知识库、插件、系统参数、OTA、MCP Endpoint | 是全模块部署下的配置事实来源；数据库变更必须走 changelog |
| `main/manager-web` | Vue Web 智控台；提供管理界面 | 不直接参与实时语音；只通过 API 改变后台配置 |
| `main/manager-mobile` | 移动端智控台 | 复用管理后台能力；不直接参与实时语音 |
| `main/digital-human` | 数字人、音频交互和唤醒词联调辅助 | 不是 ESP32 生产实时链路判定依据 |
| `docs/` | 架构、部署、开发、设备、集成、治理文档 | 文档应记录真实链路和长期约定，不保存密钥、生产地址或用户数据 |
| `scripts/`、Dockerfile、Compose | 本地开发和部署自动化 | 属于运维边界，修改会影响启动、构建和生产发布流程 |

重要原则：前端页面保存成功，只代表配置进入后台；设备是否变化取决于 Python 何时重新读取私有配置、设备是否重连、缓存是否刷新，以及当前模型是否真的采用了对应提示词或工具。

视觉链路补充：

| 参数 | 归属 | 说明 |
| --- | --- | --- |
| `server.vision_explain` | `sys_params` 后台参数 | 设备拍照后真正访问的视觉解释接口地址。全模块部署时由 `manager-api` 下发给 `xiaozhi-server`。 |
| `server.http_port` | `sys_params` 后台参数 | 视觉接口和 OTA 共享监听端口。 |
| 本地 `data/.config.yaml` 中的 `server.vision_explain` | 本地覆盖值 | 仅在填写真实可达地址时覆盖后台值；占位符、`null` 或 `你的...` 会被忽略。 |

## 3. 端云链路

设备启动后的主链路：

```text
设备读取 OTA URL
  -> 请求 OTA 接口
  -> 获取 WebSocket 或 MQTT 连接参数、激活/固件/网关信息
  -> 建立到 xiaozhi-server 的实时连接
  -> hello / 设备特征 / IoT 或 MCP 能力声明
  -> 上传 Opus 音频帧
  -> VAD 切分语音段
  -> ASR 转文字
  -> 语义控制中枢处理高频控制、状态机上下文和拒识
  -> 未处理时由 Intent 或主 LLM 判断是否调用工具
  -> LLM 生成回复或工具执行
  -> TTS 合成音频
  -> 音频下发设备播放
```

OTA 是设备进入云端的入口，不只是固件升级入口。单模块部署时 OTA 通常由 Python HTTP 端口提供；全模块部署时 OTA 由管理后台和 Python 配置共同决定。设备端如果 NVS 中保留旧 OTA 地址，重新编译固件也可能继续连旧服务器，需要清理 NVS 或重新配网。

WebSocket 是实时对话主通道；MQTT/UDP 通过网关扩展设备接入方式，但最终仍要进入 xiaozhi-server 的实时处理链路。

## 4. 配置下发链路

全模块部署下，智能体配置的事实来源在 `manager-api` 和数据库中：

```text
manager-web / manager-mobile
  -> manager-api 保存智能体、模型、提示词、插件、MCP、上下文源、设备绑定
  -> MySQL / Redis / 系统参数
  -> xiaozhi-server 按设备 MAC、智能体和密钥拉取私有运行配置
  -> 初始化 selected_module、VAD、ASR、LLM、TTS、Memory、Intent、Plugins、MCP
  -> 每轮对话按当前会话状态组装模型请求
```

下发配置通常包括：

| 配置 | 运行时用途 |
| --- | --- |
| `selected_module` | 指定 ASR、LLM、TTS、Memory、Intent、VAD、VLLM、SLM、RAG 等模块 |
| `prompt` | 智能体角色提示词，进入系统提示词增强链路 |
| `summaryMemory` | 记忆摘要或历史偏好上下文 |
| `plugins` | 服务端插件、声明式互动插件、工具描述覆盖和参数 |
| `mcp_endpoint` | 外部 MCP Endpoint 地址 |
| `context_providers` | 动态上下文源 |
| `voiceprint` | 声纹说话人信息 |
| TTS 私有音色、语速、音量、语言 | 控制合成音频表现 |

单模块 Python 部署不依赖后台管理全链路，主要通过 `config.yaml`、`config_from_api.yaml`、`data/.config.yaml` 或 MCP 本地配置文件提供运行配置。它适合本地调试和单设备验证，不适合作为多设备、多智能体配置治理的长期形态。

## 5. 提示词、工具和 MCP 注入链路

主 LLM 看到的内容不是一段单独 prompt，而是服务端按轮次组合的请求：

```text
system prompt
  = 后台角色提示词
  + agent-base-prompt.txt 基础模板
  + 日期、时间、位置、天气、上下文源、语言、设备信息等动态上下文

messages
  = 对话历史
  + memory 查询结果
  + summaryMemory
  + voiceprint speakers
  + tool result

tools
  = 服务端插件工具描述
  + 服务器侧 MCP tools/list
  + 外部 MCP Endpoint tools/list
  + 设备端 MCP tools/list
  + 设备 IoT descriptors
```

工具是否触发由模型或意图模块决定，Python 负责准备工具 schema、执行工具、处理结果。当前主要有两种判断方式：

| 模式 | 判断者 | 注入形式 | 适用边界 |
| --- | --- | --- | --- |
| 语义控制中枢 | Python Router / StateManager / PolicyEngine | 高置信度规则、连接状态 | 停止、拒识等高频控制 |
| `function_call` | 主 LLM | OpenAI 兼容 `tools` 字段 | 主模型支持原生 function calling |
| `intent_llm` | 独立意图 LLM | 函数名、描述和参数拼成意图提示词，要求返回 JSON | 主模型工具调用不稳定或希望小模型先判意图 |

MCP 必须区分三类：

| MCP 类型 | 工具来源 | 执行位置 |
| --- | --- | --- |
| 外部 MCP Endpoint | 后台或配置下发的 Endpoint 返回 `tools/list` | Python 连接 Endpoint 后调用 |
| 服务器侧 MCP | Python 本地 MCP server 配置返回 `tools/list` | Python 作为 MCP client 调用 |
| 设备端 MCP | 设备声明 `features.mcp=true` 后返回 `tools/list` | Python 向设备发 `tools/call` |

纯语言游戏不再由 Python 服务端状态机接管，应交给主 LLM 根据对话上下文自然处理。需要排查提示词和工具时，优先沿 [提示词注入工程](../integrations/prompt-injection-engineering.md) 中的 `PromptManager`、`Dialogue`、`UnifiedToolHandler`、Intent、MCP executor 链路检查。

## 6. 运行产物边界

仓库内必须区分“源码模板”和“运行产物”：

| 类型 | 示例 | 是否应提交 |
| --- | --- | --- |
| 源码和模板配置 | `main/xiaozhi-server/config.yaml`、`config_from_api.yaml`、`main/manager-api/src/main/resources/application*.yml`、`main/manager-web/.env.development` | 可以提交，但不得含真实密钥 |
| 私有运行配置 | `main/xiaozhi-server/data/.config.yaml`、生产 `.env`、真实数据库/Redis/云服务密钥 | 禁止提交 |
| 本地 MCP 配置 | `data/.mcp_server_settings.json`、环境特定 MCP 地址和 token | 视内容处理；含真实地址或密钥时禁止提交 |
| 运行数据和缓存 | `logs/`、`tmp/`、`runtime/`、上传文件、缓存、PID、socket | 禁止提交 |
| 构建产物 | `target/`、`dist/`、`node_modules/`、前端缓存 | 禁止作为源码变更提交 |
| 模型和媒体资产 | 本地下载模型、唤醒词、音乐、测试音频 | 只有明确作为项目资产且无版权/隐私风险时才提交 |

配置治理的核心规则是：默认值进模板，示例值进示例文件，真实值进私有配置或部署平台密钥；运行时生成的内容不得反向污染源码。

## 7. 部署边界

项目存在两种主要部署形态：

| 形态 | 组成 | 适合场景 | 关注点 |
| --- | --- | --- | --- |
| 单模块 Python | 只运行 `xiaozhi-server`，配置主要来自文件 | 本地调试、模型验证、单设备接入 | 配置简单但后台治理弱 |
| 全模块部署 | `xiaozhi-server` + `manager-api` + `manager-web` + MySQL + Redis，可配合 Nginx、Docker Compose、私有镜像 | 生产、多设备、多智能体、OTA、管理后台 | 配置链路长，需要保持 Java、Python、数据库、前端和部署参数一致 |

默认端口边界：

```text
8000  xiaozhi-server WebSocket
8003  xiaozhi-server HTTP / OTA / 视觉接口
8002  manager-api / manager-web 管理后台
3306  MySQL
6379  Redis
8004  MCP Endpoint 服务常见端口
```

生产环境通常还会增加 HTTPS/WSS、Nginx 反向代理、域名、证书、私有 Docker 镜像、外部数据库备份和日志采集。生产服务器应运行发布产物和私有配置，不应作为日常功能开发环境。

## 8. 开发治理边界

开发治理要保护三件事：

```text
官方源码可同步
本地定制不被覆盖
生产配置和运行数据不进 Git
```

治理规则：

- 普通功能开发、官方同步、部署变更应使用不同任务分支，禁止把无关改动混入同一提交。
- 同步官方前必须读取 [本地定制记录](../local-customizations.md)，涉及插件、提示词、状态机、配置下发、MCP、启动脚本、天气容错等文件时必须人工合并。
- 修改配置、数据库、Docker、设备协议、OTA、WebSocket、MCP、PromptManager、UnifiedToolHandler、Intent、TTS 入队或打断处理时，必须补充对应验证。
- 文档更新应说明“真实链路”和“治理边界”，不要复制部署教程或源码注释全文。
- 多智能体并行改仓库时，只修改自己责任文件；发现他人未提交改动时不得回滚、格式化或覆盖。

官方同步的具体流程见 [官方上游同步策略](../standards/upstream-sync-policy.md)。

## 9. 排查导航

| 问题 | 优先阅读 |
| --- | --- |
| 后台改了但设备没变化 | [云端运行架构与配置链路](./cloud-runtime-architecture.md) 的配置下发、OTA、排查顺序 |
| 模型没有看到提示词或工具 | [提示词注入工程](../integrations/prompt-injection-engineering.md) 的 system prompt、tools、intent_llm、MCP 注入 |
| 插件、MCP、状态机游戏被官方更新影响 | [本地定制记录](../local-customizations.md) 和 [官方上游同步策略](../standards/upstream-sync-policy.md) |
| 不确定某文件能否提交 | 开发规范、配置运行策略和本文“运行产物边界” |
| 不确定单模块还是全模块部署 | 本文“部署边界”和 `docs/deployment/` 下对应部署文档 |

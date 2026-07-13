---
status: canonical
owner: architecture
last_verified: 2026-07-13
source_paths:
  - main/xiaozhi-server
  - main/manager-api
  - main/manager-web
  - main/manager-mobile
  - deploy/production
---

# 项目完整架构

本文是项目级架构的唯一总览，回答系统由什么组成、边界在哪里、一次设备对话和一次配置变更如何流动。实现细节由 [架构中心](./README.md) 下的专题文档维护。

## 1. 系统定位

`xiaozhi-esp32-server` 是端云协同语音智能体平台，分为两个运行平面：

- 实时数据面：ESP32 与 `xiaozhi-server` 保持实时连接，完成音频、语义、模型和工具调用。
- 管理控制面：Web/Mobile 通过 `manager-api` 管理智能体、设备、模型、知识库、记忆、OTA 和权限，并向实时服务提供运行配置。

```mermaid
flowchart LR
    User[设备用户] --> Device[ESP32 设备]
    Operator[管理员 / 运营人员] --> Web[manager-web]
    Operator --> Mobile[manager-mobile]
    Device <-->|WebSocket 音频与控制| Realtime[xiaozhi-server]
    Device -->|HTTP OTA / 视觉| Realtime
    Web -->|HTTP API| Control[manager-api]
    Mobile -->|HTTP API| Control
    Realtime -->|服务密钥 HTTP| Control
    Control --> MySQL[(MySQL)]
    Control --> Redis[(Redis)]
    Realtime --> Providers[ASR / LLM / TTS / 外部服务]
    Realtime --> Tools[插件 / MCP / IoT]
```

## 2. 容器与模块边界

```mermaid
flowchart TB
    subgraph Client[客户端]
        ESP[ESP32]
        Browser[浏览器]
        App[移动端 / 微信小程序]
    end

    subgraph Runtime[实时数据面]
        WS[WebSocket :8000]
        PyHTTP[Python HTTP :8003]
        Session[连接会话与语音流水线]
        Provider[Provider 适配层]
        ToolRuntime[统一工具运行时]
        WS --> Session
        PyHTTP --> Session
        Session --> Provider
        Session --> ToolRuntime
    end

    subgraph Management[管理控制面]
        WebUI[manager-web :8002]
        API[manager-api :8002/xiaozhi]
        Config[配置装配与业务服务]
        WebUI --> API --> Config
    end

    subgraph Data[数据层]
        DB[(MySQL :3306)]
        Cache[(Redis :6379)]
    end

    ESP --> WS
    ESP --> PyHTTP
    Browser --> WebUI
    App --> API
    Session -->|拉取设备运行配置 / 上报事件| API
    Config --> DB
    Config --> Cache
```

| 模块 | 核心职责 | 不应承担的职责 |
| --- | --- | --- |
| `main/xiaozhi-server` | 实时连接、VAD/ASR/LLM/TTS、会话语义、工具/MCP、Python HTTP | 用户后台管理、业务数据事实存储 |
| `main/manager-api` | 配置事实、权限、业务编排、数据库迁移、运行配置下发 | 伪造模型运行结果、处理实时音频 |
| `main/manager-web` | Web 管理交互与结果展示 | 直接调用 provider、实现服务端鉴权 |
| `main/manager-mobile` | H5/iOS/Android/微信管理交互 | 复制一套独立后端业务规则 |
| MySQL | 持久业务事实和 Liquibase 管理的结构 | 会话内高频状态 |
| Redis | 缓存、短生命周期状态和配置加速 | 明文密钥的长期事实来源 |

## 3. 设备实时主链路

```mermaid
sequenceDiagram
    participant D as ESP32
    participant O as OTA/HTTP :8003
    participant W as WebSocket :8000
    participant C as ConnectionHandler
    participant A as manager-api
    participant M as 模型与工具

    D->>O: OTA 请求 / 获取连接参数
    D->>W: 建立 WebSocket，发送 hello 与设备能力
    W->>W: 默认执行 Token 认证或设备白名单校验
    W->>C: 创建独立连接会话
    C->>A: 按设备身份拉取私有运行配置
    A-->>C: selected_module + model_config + prompt + plugins...
    C->>C: 初始化 VAD / ASR / LLM / TTS / Memory / Intent
    D->>C: 上传 Opus 音频帧
    C->>M: VAD -> ASR -> 语义控制 -> Intent/LLM -> 工具
    M-->>C: 文本或工具结果
    C->>M: TTS 合成
    C-->>D: 下发 Opus 音频与控制消息
```

实时链路的稳定顺序是：

```text
连接认证 -> 设备配置装配 -> 音频接收 -> VAD -> ASR
-> 语义控制中枢 -> Intent / 主 LLM -> 工具或 MCP
-> TTS -> 音频回传 -> 事件与聊天记录上报
```

高置信度停止、退出等控制先进入语义控制中枢；未处理的输入才进入旧 Intent 和主 LLM。工具统一由服务端插件、Server MCP、Device IoT、Device MCP、MCP Endpoint 五类来源聚合，冲突时先注册者保留。

## 4. 配置控制链路

Web 或 Mobile 保存配置只表示控制面事实已改变，不等于当前设备会话立即改变。配置生效还受缓存刷新、Python 重新拉取和设备重连影响。

运行时模型契约为：

```text
selected_module.<moduleType> = modelId
model_config.<modelId>.model_type = moduleType
model_config.<modelId>.typed_config = provider 初始化配置
```

Java 的 `ModelRuntimeAssembler` 负责从通用字段、普通参数和密钥参数组装 `typed_config`；Python 的 `normalize_runtime_config()` 负责标准化并为旧调用点构造兼容视图。新增模型配置能力必须保持这条契约，不能让前端字段或 Java 实体直接泄漏到 provider。

其他主要下发内容包括：

| 字段 | 运行时用途 |
| --- | --- |
| `prompt` | 智能体角色与基础系统提示词输入 |
| `summaryMemory` | 兼容记忆摘要上下文 |
| `plugins` | 已启用插件及智能体级参数 |
| `mcp_endpoint` | 外部 MCP Endpoint |
| `context_providers` | 动态上下文源 |
| `voiceprint` | 外置声纹服务配置、`user_id=agentId` 和最多 5 条说话人信息 |

完整时序见 [运行时链路](./runtime-flows.md)，字段责任见 [数据与契约](./data-and-contracts.md)。

## 5. 能力地图

| 领域 | 控制面 | 实时面 | 权威专题 |
| --- | --- | --- | --- |
| 模型 | 配置、目录、测试、评测任务 | 真实 provider 初始化与调用 | [模型评测与助手](./model-benchmark-and-assistant.md) |
| 语音 | 模型和音色配置 | VAD、ASR、TTS、打断 | [设备交互链路](./device-interaction-chain.md) |
| 工具 | 插件启用与参数 | 统一工具注册、冲突和执行 | [功能插件](./function-plugin-architecture.md) |
| MCP | Endpoint 和工具配置 | Server/Device/Endpoint 三类 MCP | [MCP 链路](./mcp-chains.md) |
| 记忆 | 事实、候选、审计、查询 | 事件上报与上下文注入 | [记忆系统](./memory-system-chain.md) |
| 知识库 | 文件、分块、检索与权限 | 检索工具调用并回注 LLM | [知识库链路](./knowledge-base-chain.md) |
| Workspace | 成员、角色和资源归属 | 配置拉取时的资源授权边界 | [工作空间隔离](./workspace-organization-management.md) |

## 6. 数据所有权

- `manager-api + MySQL` 是用户、智能体、设备绑定、模型、知识库、长期记忆和 Workspace 的事实来源。
- `xiaozhi-server` 拥有连接级会话状态、音频队列、当前对话和工具客户端生命周期。
- Redis 是加速层；缓存失效不能改变 MySQL 的事实语义。
- 设备拥有固件、NVS 中的连接入口和设备侧能力描述，但设备上报不能绕过服务端工具门控。
- `config.yaml` 是可提交模板；真实密钥和环境私有配置只能进入受保护的运行配置。

## 7. 安全与信任边界

- 设备 WebSocket 认证默认开启；只有显式白名单设备可旁路 Token。
- Python HTTP 的视觉与设备 MCP 接口使用 Bearer Token 或共享密钥；OTA 保持匿名入口并配套限流。
- Java 管理接口默认进入 Shiro OAuth2 过滤器；匿名和服务端接口必须显式登记。
- `/config/**` 由 `ServerSecretFilter` 校验共享密钥，但资源型接口仍必须校验 agent、device 或 workspace 归属。
- 用户输入 URL 必须经过 SSRF 校验；文件路径必须防止路径穿越；日志不得记录原始凭据。
- 生产入口必须通过 TLS 反向代理提供 HTTPS/WSS，数据库和缓存不应暴露到公网。

详细控制见 [安全架构](./security-architecture.md)。

## 8. 部署形态

| 形态 | 组成 | 用途 |
| --- | --- | --- |
| 本地单模块 | `xiaozhi-server` + 文件配置 | provider 和设备链路开发验证 |
| 本地全模块 | 四个业务模块 + MySQL + Redis | 跨模块开发与配置联调 |
| 生产容器 | 发布镜像 + MySQL + Redis + TLS 反向代理 | 多设备正式运行 |

生产唯一操作入口是 [ACR 镜像生产部署](../deployment/private-docker-release.md)。网络、存储和恢复边界见 [部署架构](./deployment-architecture.md)。

## 9. 故障定位入口

| 现象 | 首查方向 |
| --- | --- |
| 后台保存后设备未变化 | 配置缓存、设备绑定、Python 拉取和重连时机 |
| 设备无法连接 | OTA 地址、TLS/WSS、WebSocket Token、设备白名单 |
| ASR/LLM/TTS 无输出 | 当前 `selected_module`、`typed_config`、provider 初始化和超时 |
| 工具没有出现 | Intent 白名单、工具源连接、服务端门控和同名冲突 |
| 数据跨用户可见 | Workspace header 校验、资源实体归属、读写权限 |
| 知识库或记忆未生效 | 智能体绑定、检索范围、超时降级和上下文注入 |

开发前从 [架构中心](./README.md) 选择对应模块和领域文档，不应依赖临时分析报告或手写代码行数判断当前实现。

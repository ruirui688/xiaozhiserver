---
status: canonical
owner: runtime
last_verified: 2026-07-28
source_paths:
  - main/xiaozhi-server/app.py
  - main/xiaozhi-server/core/connection.py
  - main/xiaozhi-server/core/semantic
  - main/manager-api/src/main/java/xiaozhi/modules/config
  - main/manager-api/src/main/java/xiaozhi/modules/agent/skill
---

# 运行时链路

本文集中描述跨模块运行时序；provider、MCP、记忆和知识库的内部算法由各领域专题维护。

## 1. 服务启动

```mermaid
sequenceDiagram
    participant P as xiaozhi-server
    participant A as manager-api
    participant D as MySQL/Redis
    P->>P: 读取 config.yaml 与私有覆盖
    P->>A: 可选拉取 server-base 配置
    A->>D: 读取系统参数与缓存
    A-->>P: 服务端基础配置
    P->>P: 标准化运行配置并初始化共享模块
    P->>P: 启动 WebSocket :8000 与 HTTP :8003
```

`app.py` 确保 `server.auth_key` 有可用值并持久化到私有运行文件。该密钥不能写入文档或提交到 Git。

## 2. 设备启动与连接

```text
设备读取 OTA 入口
-> 请求 OTA / 获取 WebSocket 地址和 Token
-> 连接 /xiaozhi/v1/
-> 默认执行 Token 校验，显式白名单可旁路
-> hello 上报 device-id、client-id 和 features
-> 创建独立 ConnectionHandler
-> 按设备身份从 manager-api 拉取私有配置
-> 初始化连接级模型、记忆、Intent、插件和 MCP
```

设备能力只是一种输入。Device IoT 与 Device MCP 工具仍须经过服务端门控、注册优先级和冲突处理。

## 3. 配置从后台进入会话

```mermaid
sequenceDiagram
    participant UI as Web / Mobile
    participant API as manager-api
    participant DB as MySQL / Redis
    participant PY as xiaozhi-server
    participant D as ESP32
    UI->>API: 保存智能体、固定播报技能、模型、插件或知识配置
    API->>DB: 持久化事实并刷新相关缓存
    D->>PY: 新建或重建连接
    PY->>API: 携带设备身份请求运行配置
    API->>API: 校验设备与智能体归属
    API->>DB: 装配当前配置
    API-->>PY: selected_module + model_config + 上下文配置
    PY->>PY: normalize_runtime_config()
    PY->>PY: 初始化当前连接 provider
```

当前会话是否热更新取决于具体配置类型。排查“保存成功但设备未变化”时依次检查：数据库事实、Redis 缓存、设备绑定、Python 拉取结果、连接是否重建、provider 是否使用了新配置。

聊天记录配置是连接级可热更新状态。智能体保存 `chatHistoryConf` 或切换记忆模型后，manager-api 在事务提交后通过受保护的内部 HTTP 接口按 `agentId` 通知 xiaozhi-server；所有在线连接立即采用有效的 `chat_history_conf`。开启记录时按需启动上报线程，文本/音频模式从下一条新消息生效；关闭记录时停止接收新上报，但已进入队列的记录仍会排空。内部通知失败不回滚已经提交的智能体配置，设备下次重连会重新读取数据库状态。

智能体固定播报技能通过 `semantic_skills` 随设备私有配置下发，当前连接不会热替换这组规则；后台保存后需要设备重连，由新建连接重新拉取并编译规则。没有配置或没有命中时不得改变旧对话行为。

连接初始化按能力发布 `binding_ready / tts_ready / conversation_ready / audio_input_ready`，不能再用一个
完成标志推断所有 provider 和音频通道均可用。通道打开协程必须成功返回后才能设置对应 ready；初始化异常
记录失败原因且不发布 ready。初始化期间的 `listen/detect` 进入连接级有界 TTL 队列，只有对话能力就绪后
才在连接事件循环回放。

## 4. 一轮语音对话

```mermaid
sequenceDiagram
    participant D as ESP32
    participant S as Connection Session
    participant ASR as VAD / ASR
    participant SEM as SemanticCenter
    participant LLM as Intent / LLM
    participant TOOL as Plugin / MCP / IoT
    participant TTS as TTS
    D->>S: Opus 音频帧
    S->>ASR: 队列、VAD 分段、语音识别
    ASR-->>S: 用户文本
    S->>SEM: 标准化、固定技能匹配、状态路由与策略
    alt 空闲态固定播报技能命中
        SEM->>TTS: 固定文本，不调用 Intent/LLM
        TTS-->>D: 当前智能体音色播报
    else 高置信控制命中
        SEM-->>S: 直接控制或回复
    else 普通对话
        S->>LLM: 对话历史、记忆、提示词与 tools
        opt 模型选择工具
            LLM->>TOOL: 统一工具调用
            TOOL-->>LLM: 直接回复或二次生成上下文
        end
        LLM-->>S: 流式回复文本
    end
    S->>TTS: 分句合成与音频队列
    TTS-->>D: Opus 音频及播放边界
```

TTS 播放期间，支持 AEC 的设备检测到用户开口后立即停止旧 LLM/TTS 输出，用户音频继续进入 ASR；非 AEC 设备在完整 ASR 到达后停止播报。

固定播报技能支持完整话术包含匹配和关键词组合匹配。关键词组内全部关键词必须出现、顺序不限，不同规则之间为 OR；冲突按优先级、命中关键词数量、关键词总长度、完整话术优先、排序和技能 ID 稳定决胜。技能只在空闲状态触发，会议、声纹采集、通话和游戏状态保持各自既有优先级。技能问答进入聊天记录但跳过长期记忆事件。

回复轮次由 generation 级 `ReplyLifecycle` 管理。STT 显示本身不创建回复；只有确认本轮会产生语音时才发送
`tts/start`。LLM 任务、工具或 TTS 异常统一终止当前 generation，清理队列并尽力发送 `tts/stop`；正常
LAST、Abort 或连接关闭负责其他终态，因此 `client_is_speaking` 只是活跃生命周期的派生视图。

## 5. 工具发现与执行

注册优先级固定为：Server Plugin、Server MCP、Device IoT、Device MCP、MCP Endpoint。同名工具由先注册者保留，后注册者跳过并记录 warning。

```text
工具源 -> ToolDefinition -> UnifiedToolHandler
-> function_call / intent_llm / 语义控制直接调用
-> ToolManager 路由到对应 executor
-> Action.RESPONSE 直接进入 TTS
   或 Action.REQLLM 回注上下文后再次请求 LLM
```

完整实现见 [功能插件](./function-plugin-architecture.md) 和 [MCP 链路](./mcp-chains.md)。

## 6. 记忆与知识库

- 记忆：Python 上报对话事件，Java 维护事实、候选与审计；下一轮查询结果注入 LLM 上下文。
- 知识库：Web 上传文件，Java 解析、分块和检索；Python 工具调用检索 API，再把结果交给 LLM。
- 两条链路都必须受 agent、device、dataset 和 workspace 作用域限制；超时或外部模型失败不得关闭主 WebSocket。

## 7. 会话关闭

关闭顺序应覆盖：取消后台任务、停止报告线程、清空 ASR/TTS 队列、关闭工具与 MCP 客户端、释放 provider、注销设备连接映射、关闭 WebSocket。旧连接晚关闭时不能删除已由新连接替换的注册表记录。

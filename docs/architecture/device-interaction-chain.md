---
status: supporting
owner: xiaozhi-server
last_verified: 2026-10-15
source_paths:
  - main/xiaozhi-server/config.yaml
  - main/xiaozhi-server/core
  - main/manager-api/src/main/java/xiaozhi/modules/config
  - main/manager-api/src/main/java/xiaozhi/modules/device
  - main/manager-api/src/main/java/xiaozhi/modules/mobile
  - main/manager-web/src/components/device
  - main/manager-mobile/src/pages/device
---

# 设备交互完整链路

本文档说明 ESP32 设备从 WebSocket 连接到音频上行/下行、IoT 描述符注册、Device MCP 工具发现、OTA 固件升级的完整链路，以及连接生命周期管理。

先读：[项目完整架构](project-architecture.md)、[运行时链路](runtime-flows.md)、[提示词注入工程](prompt-injection-engineering.md)。

一台设备关联多个智能体及在线无重连切换的契约见[设备多场景管理](device-scene-management.md)。
设备本地人脸识别的人物清单、照片下发和识别事件动作见[人物资源与设备人脸同步](person-resource-and-face-sync.md)。

## 1. 链路人总览

```text
ESP32 设备
  │
  ├── WebSocket 连接 (ws://host:8000/xiaozhi/v1/)
  │     ├── Hello 消息 (features, device-id, client-id)
  │     ├── 语音能力协商 (features.speech → speech_route)
  │     ├── 音频上行 (binary Opus → 服务端 VAD/ASR，或设备端 ASR 最终文本)
  │     ├── 音频下行 (服务端 TTS → Opus，或文本 → 设备端 TTS + playback ACK)
  │     ├── IoT 描述符 (type: "iot", descriptors: [...])
  │     ├── MCP 消息 (type: "mcp", JSON-RPC 2.0)
  │     └── 控制消息 (listen, abort, wakeup)
  │
  ├── HTTP 端点 (port 8003)
  │     ├── POST /xiaozhi/ota/          固件版本检查
  │     ├── GET  /xiaozhi/ota/download/  固件下载
  │     └── GET/POST /device/mcp/*       Device MCP REST API
  │
  └── manager-api HTTP 配置下发
        ├── GET /config/{device-id}       私有运行时配置
        ├── POST /config/memory/events    记忆事件上报
        └── POST /agent/chat-history/report  聊天记录上报
```

OTA 响应在设备已经绑定智能体时额外包含 `device_config`：`assistant_name` 取智控台智能体昵称
`ai_agent.agent_name`，`wake_words[0]` 默认与昵称完全一致，`revision` 取智能体更新时间的毫秒时间戳。
设备未绑定智能体、智能体不存在或昵称为空时省略整个字段。该响应不包含 manager-api 服务端密钥。

## 2. WebSocket 连接建立

### 2.1 入口

```text
main/xiaozhi-server/app.py:80-81
  → WebSocketServer(config, logger).start()
```

`WebSocketServer` 在 `core/websocket_server.py` (193行) 中实现，管理所有活跃连接的全局注册表。

### 2.2 连接处理流程

```text
websocket_server.py:85-159  _handle_connection()
  1. 提取 device-id（header > query param）
  2. 认证验证（AuthManager JWT verify，可选）
  3. 创建 ConnectionHandler(shared_modules, config, server)
  4. handler.handle_connection(websocket)
  5. finally: 强制关闭 WebSocket（3种回退策略）
```

### 2.3 ConnectionHandler 初始化

```text
connection.py:96-226  ConnectionHandler.__init__()
  - 深拷贝全局 config（每连接隔离）
  - 初始化状态：VAD 缓冲区、ASR 队列、TTS 队列
  - 创建 ThreadPoolExecutor(max_workers=5)
  - 初始化 SemanticCenter、GameSessionManager
  - 初始化 ReportThreadManager
  - 创建 binding_ready / tts_ready / conversation_ready / audio_input_ready 能力门禁
  - 创建 ReplyLifecycle（generation 级回复状态）
  - 创建 _abort_lock threading.Lock（保护 client_abort）
```

### 2.4 配置拉取（后台异步）

```text
connection.py:227-298  handle_connection()
  → connection.py:707-777  _initialize_private_config_async()
    - HTTP GET /config/{device-id} (device-id + client-id 请求头)
    - DeviceNotFoundException → need_bind = True
    - 成功后：deep-merge model_config, selected_module, prompt, voiceprint,
              summaryMemory, mcp_endpoint, context_providers, plugins
    → connection.py:779  _apply_private_runtime_config()
    → 线程池中创建按设备实例: LLM, TTS, VAD, ASR, Memory, Intent
```

**同步保护**：后台初始化不再用单个 `_modules_ready` 代表所有能力。设备绑定完成后设置
`binding_ready`；TTS provider 和音频通道真实打开后设置 `tts_ready`；LLM、TTS、提示词和工具
完成后设置 `conversation_ready`；VAD、ASR 和输入通道完成后设置 `audio_input_ready`。二进制麦克风
音频只检查 `audio_input_ready`，`external_tts` 只检查 `tts_ready`，`listen/detect` 只检查
`conversation_ready`。任何初始化异常都会记录 `initialization_error`，且不得发布 ready 状态。

初始化期间到达的 `listen/detect` 不直接执行也不静默丢弃：连接按相同文本去重，最多保存 8 条，
每条 TTL 10 秒；`conversation_ready` 后在原连接事件循环回放。过期消息、初始化失败和连接关闭均不回放。

首次激活设备时，manager-api 在绑定事务提交后按设备 ID 调用 `/device/runtime-config/refresh`，并要求立即刷新。
仍保持未绑定 WebSocket 的连接允许这次强制刷新重新拉取私有配置；成功后清除 `need_bind` 并发布各能力门禁，
设备无需为了让声纹采集看到在线连接而重启。若物理连接已经断开，则无法由服务端热刷新，仍由固件正常重连策略兜底。

### 2.5 唤醒后的首轮收音

```text
listen.detect / ASR 最终文本
  → session/wake_events.py 分类（空内容 / 唤醒事件 / 用户正文）
  → startToChat：先过滤空内容与同次唤醒的另一入口
  → enable_greeting → 缓存（显式开启时）或主 LLM 实时回应
```

- `wakeup_vad_ignore_ms` 默认 `0`，唤醒后第一包音频直接进入 VAD/ASR，不再固定丢弃 2 秒。
- 默认 `enable_greeting=true`、`enable_wakeup_words_response_cache=false`。前者控制是否回应，后者只选择缓存加速；缓存关闭时直接使用真实唤醒原话调用主 LLM，跳过语义技能与意图模型，不再伪造“嘿，你好呀”，也不启用本轮工具调用。
- `enable_greeting=false` 时 detect 和独立 ASR 唤醒均静默，不创建用户聊天或回复轮次，不打断当前播报。普通“你好”和后续真实指令不受此开关影响。
- 空白、纯标点和 JSON 空 `content` 在播报打断、语义处理和回复 generation 之前返回。唤醒事件保留原话；“你好小智，打开摄像头”清洗为真实指令。
- 去重状态属于当前连接和 `session_id`，记录输入 `turn_id` 与回复 generation；同来源同 turn 重试被过滤。旧 detect 与服务端 ASR 没有共享事件 ID，采用最多 10 秒的一次性相反来源配对，覆盖现有 readiness 队列预算。配对消费后、超时后或 session 变化后可再次唤醒；不依据文本永久去重，不清除进行中的录音，也不为实时问候开启音频忽略窗口。
- 旧协议边界：超过 10 秒才到达的对侧事件可能被视为新唤醒；若前次只有一种来源，10 秒内另一次真实唤醒只出现相反来源，则无法与迟到副本可靠区分。同来源且无 turn ID 的重复消息也无法保证幂等。完全消除歧义需要固件提供跨 detect/ASR 的共享唤醒 ID；本次不修改固件。
- 管理员显式打开缓存时保留旧缓存播放能力及音频上报；只有这条兼容路径仍使用 `wakeup_vad_ignore_ms` 回声保护。该参数不是双回复问题的去重方案。
- 忽略窗口使用 `time.monotonic()` 判断，不创建延迟任务；配置非法、负数或非有限值时安全回退为 0。

**配置来源与上线步骤**：未配置 manager-api 时，默认 `config.yaml` 被 `data/.config.yaml` 递归覆盖。启用 manager-api 时，两项唤醒参数来自智控台参数管理的 `sys_params`，经 `ConfigServiceImpl.buildConfig()` 按 `boolean` 下发；本地默认文件和私有文件中的这两项不会覆盖 API。角色私有配置和现有角色热刷新不管理这两项公共参数。

管理员应在参数管理保存 `enable_wakeup_words_response_cache=false` 与 `enable_greeting=true`（类型均为 `boolean`）；保存接口同步更新参数缓存并重建 Redis `server:config`，无需直接改数据库或清 Redis。Python 已启动进程和已有连接持有公共配置快照，保存参数本身不能保证所有在线连接立即生效；在获准发布的维护窗口，通过 [ACR 发布入口](../deployment/private-docker-release.md) 部署已验证镜像、重新加载服务并让设备重连。不要仅改容器默认配置，也不要把角色级热刷新当作公共参数生效保证。

2026-09-21 只读核验：生产镜像 `64d990c6` 与开发基线一致，五个故障关键文件 SHA-256 相同；数据库的两项参数当时均为 `true`。本次源码修复没有修改生产。发布前记录原参数和镜像版本并按发布规范备份；回滚使用保留的 `VERSION=64d990c6` 版本包，同时恢复原参数，不能仅退镜像却忽略配置变化。旧版本仍含本次双回复缺陷。日志验收查看 `source / kind / reason / generation`，不打印用户正文或凭据；真机验收需同时验证唤醒后立即说指令和主动打断。

#### 唤醒延迟观测（开发态，2026-09-21）

本轮只读重新核验生产镜像为 `270e1ca4`，六个重点文件（connection、runtime_readiness、
receiveAudioHandle、prompt_manager、config_loader、manage_api_client）与开发基线 SHA-256 一致。
以下观测改动尚未部署，不能据此宣称线上已经加速；上段 `64d990c6` 是此前修复时的历史核验。

复用 `SessionTelemetry` 输出 `event=session_latency`，以连接创建时的单调时钟为共同原点，
记录 `offset_ms`；`connection.enter` 是进入连接处理的标记，不是设备开始唤醒的时刻。
`session_id` 隔离连接，`reply.submit.wake/text` 的 `generation` 与 `reply.bound` 对接，后者
关联 `turn_id`、`sentence_id`。没有绑定的未知音频句子不归到当前新轮次。首次连接与后续唤醒
按同一 session 中的事件顺序和 `init.conversation_ready` 区分；多条未配对 detect 同时到达时，
不能仅按时间强行归因到某轮（旧协议没有共享唤醒 ID）。

| 标记 | 测量边界 |
|---|---|
| `detect.received / queued / released / expired / cleared` | 原始入站只记录一次，回放不重记入站；排队耗时独立记录 |
| `config.models / config.correct_words` 的 start/end | 两个 API 的完整调用，包含客户端重试；两者并行，不能相加 |
| `init.create_submitted / create_enter / create.*` | 线程池提交、开始、各组件工厂调用；共享组件复用不冒充创建 |
| `init.components_enter / tts_channels / asr_channels / *_ready` | 实例就绪阶段；ASR 通道调用不等于云握手 |
| `init.intent_tools / init.memory / init.prompt_enhancement` | 初始化父阶段，包含其内部等待；非独立 Device MCP 握手的精细拆分 |
| `prompt.location / weather / dynamic_context` | 查询阶段及位置/天气缓存命中；动态上下文当前没有缓存，未执行的查询不记耗时 |
| `memory.query` | 当前轮记忆检索总耗时；未跳过托管/本地记忆或说话人身份 |
| `llm.request / llm.first_valid_text` | 调用 provider 到非空、经过工具协议过滤的正文；不把空分片或工具参数当正文，支持 direct_answer 正文 |
| `tts.first_text_dequeued` | 文本消费者取出首段文本，**不是**提交上游或音频到达 |
| `tts.first_text_submit / first_upstream_audio` | 火山双流 provider 的首次文本发送调用、首个非空上游音频响应（编码前） |
| `tts.first_encoded_audio / audio.first_send_completed` | 首个编码帧、WebSocket 音频 send 成功返回（包含 MQTT 网关路径）；失败发送不记录成功 |

所有 span 为包含子阶段的区间，不能把父子区间或并行 API 相加。`completed` 只表示调用返回，
不表示业务成功（部分现有 API 会自行捕获异常并返回空）；异常退出记 `interrupted` 并原样抛出。
首音频原始上游观测只覆盖本次火山双流链路，其他 provider 不能拿编码帧时间冒充网络首音频。
WebSocket send 返回不代表 ESP32 缓冲耗尽或扬声器发声；本轮没有设备声学测量。
日志不包含正文、提示词、记忆、IP、配置值或密钥；日志后端失败不改变对话结果。

**证据与取舍**：从当前容器启动至只读检查时，过滤后的历史日志只有 1 次 detect 排队、
1 次记忆检索（251 ms）及 1 次 LLM 首分片（738 ms）。用户提供的同轮秒级时间线显示，
detect 后到放行约 2 秒，首次发送日志约在下一秒；无法拆出天气、位置、动态上下文各自占比，
也不能把同秒已完成的 1.228 秒配置拉取全部计入 detect 后等待。
没有后续唤醒样本、优化后实测样本（n=0）、均值或 P95。故本轮仅补观测，未调整缓存、
就绪门禁、记忆语义、超时、模型、去重或音频消费行为。中央 connection 仅新增观测调用，
计时与去重记录放在已有 SessionTelemetry / TurnMetrics，未增加调度状态机。

**后续验证与回滚**：获准部署观测版本后，在相同设备、角色、模型、网络及缓存设置下，
分别采集多轮新连接唤醒、同连接后续唤醒；只关联明确的轮次，单列失败/过期样本。
按区间端点找关键路径，确认无关查询确实阻塞后再做单项优化并复测。
当前只完成离线测试，未调用付费模型或真机测试。无配置/数据库迁移，生产没有变化，无需回滚；
若日后上线本观测版本，可按发布流程退回 `270e1ca4`，保留已有实时 LLM 唤醒和单次回复修复。

开发改动清单（均位于 `main/xiaozhi-server/`，另有本文）：
`config/config_loader.py`；`core/connection.py`；`core/observability/session_telemetry.py`；
`core/session/runtime_readiness.py`；`core/handle/{receiveAudioHandle,sendAudioHandle}.py`；
`core/utils/{modules_initialize,prompt_manager,turn_metrics}.py`；
`core/providers/tts/{base,huoshan_double_stream}.py`；`tests/test_wake_latency_observation.py`。
没有修改 config.yaml、manage_api_client 的请求策略、生产文件或数据库。

2026-09-21 开发验证：在 `xiaozhi-esp32-server` Conda 环境运行
`python -m compileall -q .` 成功；`pytest -o addopts='--tb=short -q'` 为 **707 passed**，
4 项既有 websockets 弃用警告。命令均通过 `conda run -n xiaozhi-esp32-server` 执行，
设置 `PYTHONNOUSERSITE=1 PYTHONPATH=` 隔离用户包和 ROS 路径。
新增 12 项离线用例覆盖嵌套单调计时、API 并行、失败/超时/取消、关闭与过期、并发连接和旧句子隔离、
日志脱敏及失败发送不得标记成功、文本提交不得标记音频到达；原有唤醒去重、紧接指令、
静默配置、记忆、热更新及生命周期回归均随全量套件通过。`git diff --check` 通过。
初始工作区干净；未提交、未推送、未删除文件或清理构建缓存。

#### 无音区交互注意力 MVP

真实车机接入不保证提供可靠的主驾、副驾或后排音区，因此交互注意力首期不能把音区作为必要输入。MVP 只使用
明确唤醒或按键触发、连接级交互状态、有限时间窗口、上一轮问答、当前 ASR 文本与 ASR 置信度，判断当前语音是否
面向小智。声纹只能作为可选身份特征，不能替代“是否对小智说话”的判断。

默认配置位于 `main/xiaozhi-server/config.yaml`：

```yaml
interaction_attention:
  enabled: true
  mode: shadow
  armed_window_ms: 8000
  follow_up_window_ms: 6000
  awaiting_reply_window_ms: 12000
  directed_threshold: 0.88
  undirected_threshold: 0.30
  barge_in_policy: balanced
  asr_confidence_floor: 0.20
```

判断结果固定为三态：

- `DIRECTED`：高置信度面向小智，可以继续进入语义控制；车控仍需单独通过资源权限、状态和参数校验。
- `UNDIRECTED`：背景或人与人对话，不进入 SemanticCenter、Intent、LLM、聊天记录或长期记忆。
- `UNKNOWN_ADDRESSING`：证据不足，不能猜测执行；`enforce` 模式按失败关闭处理，与 `UNDIRECTED` 使用相同阻断边界。

时间窗口的语义为：明确唤醒后最多等待 8 秒接收首条指令；普通连续对话最多保留 6 秒；小智明确提问后最多等待
12 秒接收短回答。窗口只提供上下文证据，不会单独把语音判定为 `DIRECTED`。低于
`asr_confidence_floor=0.20` 的识别结果不得进入后续判断。

运行时已在 `startToChat()` 进入 SemanticCenter 之前执行门控，并从 `listen.start/listen.detect` 接收可选
`interaction` 元数据。`mode=shadow` 只记录三态决策、置信度和原因，保持当前语义、LLM、聊天、记忆和打断行为不变；
影子数据校准完成后才能启用 `enforce`。`enforce` 下判断异常、无结果或非法结果失败关闭，禁止静默放行背景语音。

### 2.6 单轮语音背景上下文

设备可在一轮录音开始时，通过现有 `listen/start` 消息选择性上传背景：

```json
{
  "type": "listen",
  "state": "start",
  "mode": "auto",
  "context": {
    "page": "商品详情",
    "productId": "123"
  }
}
```

`context` 支持字符串、JSON 对象或数组，序列化后最多 8 KiB。服务端按连接保存一个待消费值；新的
`listen/start` 会替换或清空旧值，下一次有效 ASR 一次性消费。背景本身不会调用语义、Intent、LLM
或 TTS；空语音、识别失败、打断和无 ASR 配置时不得把背景带入下一轮。

确定性语义控制和 `intent_llm` 只读取 STT。主 LLM 收到的 user message 沿用现有结构化格式并增加
`context`，例如 `{"content":"帮我介绍一下","context":{"productId":"123"}}`。该消息保留在当前
会话历史并进入后台聊天记录；长期记忆事件和旧 Memory provider 只使用原始 `content`，不接收背景。
服务端日志只记录背景类型和字节数，不记录背景正文。

### 2.7 设备语音能力协商

支持端侧 ASR/TTS 的固件在 hello 中声明版本化能力：

```json
{
  "type": "hello",
  "features": {
    "speech": {
      "version": 1,
      "input": ["device_asr"],
      "output": ["device_tts"],
      "asr_final_text": true,
      "tts_playback_ack": true
    }
  }
}
```

manager-api 按设备持久化 `speech_input_mode / speech_output_mode`，管理接口使用
`speechInputMode / speechOutputMode`，私有运行配置下发为：

```json
{
  "speech_routing": {
    "input": "auto",
    "output": "server"
  }
}
```

输入与输出分别支持三种策略：`server` 强制服务端；`device` 要求设备能力完整，缺失时失败；`auto` 优先设备端，
能力缺失时选择服务端。端侧输出除了 `device_tts` 还必须声明 `tts_playback_ack`，否则 `auto` 回退服务端、`device`
明确失败。Python 在 hello 与私有配置到齐后下发实际结果：

```json
{
  "type": "speech_route",
  "session_id": "session-id",
  "version": 1,
  "input": "device",
  "output": "server",
  "inputPolicy": "auto",
  "outputPolicy": "server",
  "helloTimedOut": false
}
```

显式策略无法满足或与 Realtime 冲突时下发：

```json
{
  "type": "speech_route",
  "session_id": "session-id",
  "version": 1,
  "status": "error",
  "reason": "incompatible_route",
  "detail": "device does not support required ASR"
}
```

错误 `detail` 只使用固定、截断的兼容原因，不包含私有配置或正文。旧固件没有 `features.speech`，数据库和运行时缺省
也都是 `server/server`；hello 等待超时后按未声明能力处理，并在成功结果中设置 `helloTimedOut=true`。策略保存后可在
空闲轮次安全热更新并复用本连接已经冻结的能力；固件能力发生变化必须重连重新协商。Realtime 已端到端接管音频，
实际路由任一侧为 `device` 都会拒绝初始化；这类智能体必须显式配置 `server/server`。

已认证设备也可以为自己的输入侧提交受限策略变更：

```json
{
  "type": "speech_route_request",
  "request_id": "route-001",
  "inputPolicy": "server"
}
```

`inputPolicy` 只接受 `server/device/auto`。xiaozhi-server 忽略消息中的任何设备标识，只使用当前已认证
WebSocket 连接的 `device_id`，并通过受 `server.secret` 保护的 manager-api `/config/**` 边界持久化；单连接
请求有界限流。成功 ACK 的 `pending=true` 只表示配置已保存并等待安全轮次刷新，实际生效结果仍以随后下发的
`speech_route` 为准。保存失败、未启用 manager-api 配置或路由不兼容时不会静默改变当前路由。

## 3. 音频上行链路（ASR）

### 3.1 音频帧接收

```text
connection.py:408-420  _route_message()
  - 二进制消息 → asr_audio_queue.put(message)
  - MQTT 网关路径：解析 16 字节头（时间戳 + 长度），乱序重排（最多 20 条缓存）
```

### 3.2 VAD 分段

```text
providers/asr/base.py:44-59  asr_text_priority_thread（守护线程）
  → handle/receiveAudioHandle.py:20-36  handleAudioMessage()
    → conn.vad.is_vad(conn, audio)      Silero VAD ONNX 推理
    → conn.asr.receive_audio(conn, audio, have_voice)
```

**Silero VAD** (`providers/vad/silero.py:58-117`):
- Opus 解码 → PCM → 512 采样滑动窗口 → ONNX 推理
- 双阈值：`vad_threshold`(0.5) / `vad_threshold_low`(0.2)
- 3 帧窗口确认 (`frame_window_threshold`)
- 句尾静音检测统一使用 VAD 模型的 `min_silence_duration_ms`
- AEC 设备的播报打断依据句首语音检测，不等待该句尾阈值

豆包流式 ASR 2.0 是句尾分段的特例：本地 Silero 仍负责句首检测、pre-roll 和 AEC barge-in，
但自动/实时模式不会在本地 `client_voice_stop` 后向豆包发送 LAST。初始化请求携带
`end_window_size`（默认 200ms），持续发送尾部音频，直到豆包内部 VAD 返回
`utterance.definite=true` 后才提交本轮文本。设备显式 `listen/stop`（例如手动按键拾音）仍发送 LAST。

`client_is_speaking` 仍表示当前回复（包括 LLM/TTS 尚未产生首包的等待阶段）可以被用户打断，
但现在是 `ReplyLifecycle` 活跃状态的派生值，不再是可被异常分支永久遗留的布尔变量。每轮回复使用单调
递增的 generation，状态在 `PENDING_LLM / SYNTHESIZING / STREAMING / STOPPING` 间推进，并且每个
`tts/start` 只能以 `COMPLETED / FAILED / INTERRUPTED / connection_closed` 之一终结。STT 展示与回复
启动已拆开：会议只记录等静默分支只发 STT；普通对话先确认 `conversation_ready`、LLM 和 TTS，再创建
generation 并发送 `tts/start`。AEC 设备下一次 VAD 确认开口时立即 Abort，非 AEC 设备仍在完整 ASR
后 Abort。真实首音频边界只由 `_tts_started_at > 0` 表示。

### 3.3 ASR 语音转文字

```text
handle/receiveAudioHandle.py  handleAudioMessage()
  - 完整音频继续进入 ASR
providers/asr/base.py  handle_voice_stop()
  - VAD 句末后把当前整段语音转成 16k 单声道 WAV
  - asyncio.gather(speech_to_text(), voiceprint.identify_speaker())
  → receiveAudioHandle.py:45-179  startToChat(conn, text)
```

声纹识别调用独立部署的 `voiceprint-api`：全模块部署以智能体 ID 作为远程 `user_id`，每个智能体最多 5 条；历史数据可保留 `user_id=default`，Python 按结构化说话人记录的作用域分组识别并选择最高分。每句话假设只有一个说话人，VAD 检测到句末后上传当前整段语音 WAV 做识别，和官方上游声纹识别链路保持一致，不再截取前 N 秒，也不再使用 `server.voiceprint_sample_seconds`。Token 只通过 Bearer Header 传输，默认远程总超时 5 秒；失败或超时只降级为无身份。智能体没有绑定声纹时不初始化 provider，也不发送识别请求。

ASR 基类仍通过工作线程桥接异步 provider；provider 内部不能再使用同步 HTTP 或 `time.sleep()`，否则慢请求会阻塞共享事件循环并放大队列积压。

豆包 Seed ASR 2.0 支持智能体级火山热词表。角色配置用 `asr_hotword_ids` 保存最多 10 个候选表，
用 `asr_hotword_id` 保存当前生效表；manager-api 在设备私有
配置装配阶段只对 `doubao_stream` / `doubao_recording` 的 `typed_config` 注入
`boosting_table_id`，Python 再发送为 `request.corpus.boosting_table_id`。热词 ID 不写入共享模型配置，
因此使用同一 ASR 模型的不同智能体可以分别选择不同热词表；留空或选择其他 ASR 时不发送。
火山协议规定一个识别请求只支持生效一张词表，候选列表仅用于管理和快速切换，不能整体作为数组下发。
保存新的当前生效项会触发在线运行配置刷新，下一轮识别直接使用新表。

资源中心托管词库的发布会创建独立火山版本，只有显式应用到智能体才替换当前 ID 并触发上述刷新。
发布失败不覆盖旧版；历史词表 ID 仍由词库版本记录追踪，解除及删除必须检查全部历史引用。
管理流程、权限和配额见[模型管理热词契约](../models/model-management.md#智能体级豆包-asr-热词)。

`context_text` 映射到火山 `request.corpus.context`，值为 JSON 字符串，可用于直传热词或对话上下文。
它与 `request.corpus.boosting_table_id` 在同一 `corpus` 对象中共存，不能用词表 ID 代替上下文，
也不能在添加其中一个字段时覆盖另一个；日志只记录是否启用，不记录上下文正文。

豆包录音识别 `providers/asr/doubao_recording.py` 使用原生异步提交与轮询：

```text
POST submit (aiohttp, http_timeout_seconds)
  → 若响应已含文本则返回
  → await asyncio.sleep(query_interval_seconds)
  → POST query（单次 timeout 不超过剩余总预算）
  → 最长等待 query_timeout_seconds
```

默认值为 `http_timeout_seconds=10`、`query_interval_seconds=0.2`、`query_timeout_seconds=15`。日志只记录阶段、耗时、查询次数、结果长度和错误类型，不记录识别正文或凭据。

豆包流式识别 `providers/asr/doubao_stream.py` 以本地 Silero VAD 作为自动/实时模式的结束判定，并使用分阶段超时保护：

- 本地 VAD 设置 `client_voice_stop` 后，provider 先发送当前静音尾帧，再发送一次 LAST 空音频帧；接收任务继续等待火山返回最终 `definite` 结果。
- 新会话建立时会回放 `conn.asr_audio` 末尾最多 10 帧。若当前帧已经包含在这批缓存中，`current_audio_sent` 会阻止它在缓存回放后再次发送。
- `init_timeout_seconds=10`：限制初始化请求发出后等待首个服务端响应的时间。
- `response_timeout_seconds=15`：限制发送 LAST 前等待下一条中间结果的时间，每收到一个结果后重新计时。
- `final_response_timeout_seconds=5`：从发送 LAST 的时点开始计算等待最终结果的总预算，不会因中间状态重复延长。
- 自动模式收到非空 `definite` 文本时，只要求当前会话存在音频帧即可进入 `handle_voice_stop`，短词不再受原先 `len(audio_data) > 15` 的限制；手动模式的无文本结束保护仍保留原有帧数条件。
- 每个结果转发任务固定持有创建时的 WebSocket 和停止事件。旧任务结束时只关闭自己的 WebSocket；仅当共享 `asr_ws` 仍指向该会话时，才清空 provider 状态和连接音频，避免旧会话清理覆盖重连后的新会话。
- 初始化、中间结果或最终结果超时，以及 401、断链和初始化失败，都会关闭对应 WebSocket 并复位该会话状态。

真实标准音频测试中，录音 ASR 4/4 成功，流式 ASR 修复前后共 5/5 成功；无效凭据分别在 298ms、195ms 返回 401，没有出现无限等待。

### 3.4 会议记录模式

会议模式保持普通 VAD/ASR 分段，但不设置任何进入或退出固定话术。未激活时，用户发言进入主 LLM，由模型决定是否调用 `meeting_minutes(action=start)`；会议激活后，每条 ASR 串行等待上一轮裁决完成，该轮只暴露 `meeting_minutes` 并设置 `tool_choice=required`，由模型结合上下文决定 `record` 或 `end`。

```text
未激活会议
  -> 用户 ASR 进入主 LLM
  -> 主 LLM 根据完整上下文决定是否调用 meeting_minutes(action=start)
  -> 工具成功后服务端切换真实状态并 TTS 简短确认
每个完整 ASR 句子
  -> 会议激活时作为 pending turn 进入主 LLM
  -> 当前轮只暴露 meeting_minutes 工具，并要求主 LLM 必须真实调用一次
  -> 主 LLM 调用 record 时写入正文，调用 end 时丢弃当前控制发言并生成纪要
  -> 模型未调用或异常时只安全回退为继续记录
```

服务端不维护会议关键词分类器或预设 start/end 规则，只向主 LLM 提供真实 `meeting_minutes` 工具、当前会议状态和待处理 ASR。支持 `tool_choice` 的主模型必须在 `record/end` 中真实选择；不支持、调用格式异常或请求失败时，服务端只做“继续记录当前发言”的安全兜底，不得自行猜测用户是否要退出。会议轮持有连接级串行锁直到主 LLM 和工具执行结束，避免下一条 ASR 覆盖 pending turn。最终生成两种结果：设备播报 2-4 句自然口语总结，飞书接收只包含有实际信息的分段纯文本书面纪要；不得把控制话语、个人随口计划或空的“待确认”章节硬塞进纪要。会议状态、工具审计和会议正文默认不进入长期记忆事件。智控台角色配置通过 `SYSTEM_PLUGIN_MEETING_MINUTES` 启用该能力；单模块配置通过 `meeting.enabled` 控制。

### 3.5 设备端 ASR 最终文本

实际输入路由为 `device` 时，服务端不初始化 VAD/ASR/声纹输入管线，二进制麦克风包不进入传统 ASR。设备必须通过
现有 `listen.detect` 上报最终结果，不接收 partial 作为对话输入：

```json
{
  "type": "listen",
  "state": "detect",
  "source": "device_asr",
  "final": true,
  "turn_id": "turn-001",
  "text": "帮我打开客厅灯"
}
```

当前校验边界：

- `turn_id` 必填，长度 1 至 128，只允许 ASCII 字母数字和 `_.:-`；同一连接内作为 LLM 提交幂等键。
- `text` 去除首尾空白后长度为 1 至 2000；5 秒窗口最多接受 8 条最终文本。
- 已接受 `turn_id` 保留 10 分钟，缓存最多 256 条；重复键不会再次进入 SemanticCenter、Intent 或 LLM。
- `listen/start.context` 仍由下一条有效设备 ASR 一次性消费，空文本、拒绝或重复不得把背景串入错误轮次。

服务端对每条设备 ASR 请求返回：

```json
{
  "type": "speech_asr_ack",
  "session_id": "session-id",
  "turn_id": "turn-001",
  "accepted": true,
  "duplicate": false
}
```

拒绝响应增加 `reason`，包括路由未选择、非最终文本、正文/turn ID 非法、重复或限流。初始化期间仍使用连接级有界
TTL 队列延迟 `listen.detect`，设备 ASR 按 `turn_id` 去重。协议日志把识别正文替换为长度占位，只记录 turn ID、接受状态
和原因；设备身份始终来自当前已认证 WebSocket，不能由消息体指定。

## 4. 音频下行链路（TTS）

### 4.1 TTS 触发路径

多条路径向 `conn.tts.tts_text_queue` 发送 `TTSMessageDTO`：

| 来源 | 位置 | 说明 |
|------|------|------|
| `conn.chat()` | connection.py:1050-1463 | LLM 流式 chunk → TTS |
| `SemanticCenter._speak()` | semantic_center.py:162-187 | 确定性回复 |
| `intentHandler.speak_txt()` | intentHandler.py:253-272 | 意图处理回复 |
| `handle_llm_function_result()` | connection.py:1622 | 工具调用结果 |

### 4.2 TTS 处理流水线

```text
providers/tts/base.py:375-416  tts_text_priority_thread（守护线程）
  - 按 sentence_id 过滤旧消息
  - 标点分段 → to_tts_stream() → 调用 TTS API
  → providers/tts/base.py:418-479  _audio_play_priority_thread
    - Opus 编码 → run_coroutine_threadsafe → WebSocket 发送
```

SeedTTS2 双流 provider 为每个 `sentence_id` 维护独立的 `FIRST/LAST` 音频边界状态。正常收到 `EVENT_SessionFinished` 时先用 `is_end=True` 冲刷不足 60ms 的 PCM 尾帧；结束等待超时、下行空闲超时、WebSocket 断开、`EVENT_SessionFailed` 或监听任务异常退出时丢弃不完整 PCM 尾帧。只要本轮已经发布 `FIRST`，所有这些路径都会通过同一幂等收尾函数发布且只发布一次 `SentenceType.LAST`；迟到的结束事件不得重复产生 `LAST`，非当前会话的残余事件不得重置当前活跃会话。

`tts_text_queue` 中的 `SentenceType.FIRST` 是本地逻辑轮次边界，不等同于火山上游的
`EVENT_StartSession`。主 LLM 使用官方 `direct_answer` 虚拟工具表示“本轮直接回答”。普通 function-call
轮不使用关键词或服务端预设意图选择工具；provider 支持时发送 `tool_choice=required`，由模型自主选择
`direct_answer` 或真实工具，并发送 `parallel_tool_calls=false` 保证单出口。模型选择 `direct_answer` 后，服务端从流式增长的
`response` 中只保留 5 字 JSON 安全尾，其余稳定增量立即向 TTS 投递，不再设置首段、后续段或自然标点
聚合门槛。会议静默裁决轮仍禁止提前出声，并在 provider 支持时保留 `tool_choice=required`。缺少单工具约束
能力的 provider 可显式关闭 `supports_tool_choice`，退回自由 content/tool_calls；缺少并行工具参数能力时关闭
`supports_parallel_tool_calls`，并对 `direct_answer` 保持完整响应确认回退。

无论文本来自实时 `direct_answer` 还是兼容回退，SeedTTS2 都使用懒启动约定：逻辑 `FIRST` 只重置本轮流状态、登记 `sentence_id` 并准备幂等边界；
第一段经清洗后非空的 `ContentType.TEXT` 或路径有效的 `ContentType.FILE` 到达时，才为该轮启动一次上游会话。后续文本
和文件复用同一会话，逻辑 `LAST` 只结束已经实际启动的上游会话；整轮没有真实内容时不得为了收尾再
创建空会话。该变化只调整厂商会话的启动时点，设备侧仍以实际音频对应的 `FIRST/LAST` 建立
`tts start/stop`，并继续保证同一 `sentence_id` 最多各发送一次。

SeedTTS2 默认不再等待时间预缓冲；完整 Opus 帧到达后立即按 FIFO 发布，不因等待字幕而阻塞音频流。
设备字幕遵循官方契约，只能由 `store_tts_text()` 保存的完整回答产生 `FIRST`，不得把首个 LLM 增量
当作正式字幕；完整文本在首音频后才就绪时，provider 在后续音频或正常收尾边界补发 `FIRST`。
`stream_prebuffer_ms` 仍可由私有配置显式设为正数恢复音频预缓冲。
正常 `LAST` 只能在火山
`SessionFinished`、Opus 尾帧冲刷完成并进入 provider FIFO 后发布；设备协议 `stop` 仍等待发送控制器
排空。FIRST、音频、LAST 和 stop 同时携带同一 reply generation，旧 generation 的迟到边界会被丢弃。
下行超时、断链、SessionFailed 或结束异常使用带 `tts_error:*` 原因的失败终态，关闭并废弃当前
WebSocket，由下一轮重新建连，不再按正常播报完成收敛。

`ConnectionHandler.chat()` 的返回值也是回复生命周期契约的一部分：纯 `direct_answer` 只有在解析、
提交非空 response 并入队 `LAST` 后才能返回 `True`。`startToChat()` 会把其他返回值解释为
`llm_no_spoken_result` 并终止当前 generation，因此成功分支禁止使用裸 `return`；解析不到有效 response
时则不得伪造 `LAST`，应返回空结果让失败路径收敛。

设备控制协议在连接级额外记录当前 `sentence_id` 的 `tts start/stop` 发送状态，同一轮重复的 `FIRST/LAST` 不得形成第二个 `start/stop`。STT 后已经发送的 pending `start` 在真实 `FIRST` 到达时只绑定 `sentence_id`，不重复发送；Abort 已发送 `stop` 后，旧 provider 迟到的 `LAST` 也不得停止新一轮 pending 回复。因此 provider 异常不能再让设备永久停留在 `speaking`，也不能形成重复 start/stop。

Abort 会先给当前 `TurnMetrics` 记录 `outcome.status=aborted` 和 interruption 步骤，再清理生成队列。已经形成
文本的部分助手回复由 Abort 与正常 `LAST` 竞争同一个幂等收口权，只允许一方消费指标并上报一次；这样
聊天记录不会因为打断而丢失已听到的回复或整条延时链路。仅在等待阶段生成、但从未跨过真实 TTS
`FIRST` 边界的文本不会伪装成用户已经听到的助手回答。

字幕生命周期与聊天记录上报生命周期必须分离。流式 provider 只能在完整字幕 `FIRST` 发布后清理字幕映射，但完整
助手文本必须按 `sentence_id` 保留到正常 `LAST` 或 Abort 成功进入上报队列。真实音频 `FIRST` 到达时按句
标记“已经开始播放”；Abort 必须先确认该句已经播放且存在可上报文本，再与正常 `LAST` 原子竞争最终上报权，
不得用连接级旧时间戳推断当前句已播放，也不得在文本缺失时提前占用上报权。只有上报队列确认接收后才清理
该句文本与状态；队列关闭或入队异常时应释放上报权，并保留文本及已经消费的指标快照，允许迟到的正常
`LAST` 或后续重试完成持久化而不丢失延时链路。

SeedTTS2 超时按阶段拆分：建连默认 10 秒、会话启动默认 10 秒、会话结束默认 30 秒、会话内连续无下行默认 15 秒、单次事件发送默认 5 秒。旧 `session_timeout_seconds` 仅兼容映射启动预算；结束预算最低保持 30 秒。线程侧等待预算必须覆盖对应协程阶段，不能由更短的外层 `tts_timeout` 提前取消健康长语音。

### 4.3 已认证设备原文直读

已经完成 WebSocket 认证和设备绑定的连接可以直接提交原文 TTS，不再使用额外 HTTP
端点或共享密钥。设备发送：

```json
{
  "type": "external_tts",
  "requestId": "task-web-run-123-task-1",
  "text": "需要机器人原样播报的文字。",
  "interrupt": false
}
```

- `requestId` 必填，长度 1–128；同一设备十分钟内重复提交只返回原任务，不重复播报。
- `text` 必填，长度 1–1000；服务端以单个 `ContentType.TEXT` 队列项提交，不添加前缀，
  不经过 LLM、语义控制、MCP、表情或动作，也不写入 `Dialogue`。
- TTS 使用当前连接已初始化的 `conn.tts`，因此继续使用智能体绑定的 provider、音色、
  语速和现有 Opus/WebSocket 下行链路。
- 实际播报沿用智能体 `chat_history_conf`：值为 `1` 时保存 assistant 文本，值为 `2` 时
  同时保存文本和合成音频，值为 `0` 时尊重现有隐私配置、不写聊天记录；无论哪种配置都显式
  跳过长期记忆 source event。状态中的 `chatHistory` 和 `chatHistoryAudio` 会返回实际启用情况。
- `interrupt=false` 时，设备正在收音、等待回复、合成或播放均返回 `busy`；
  `interrupt=true` 通过既有 Abort 和 `abort_generation` 结束旧轮次后再提交。通话、会议和
  声纹采集属于受保护模式，不允许强制插播。
- `external_tts.text` 日志只记录长度，不记录正文。
- `busy` 响应额外返回稳定的 `busyReason`：`assistant_reply_active`、`user_voice_active`、
  `tts_text_queue_pending`、`tts_audio_queue_pending` 或 `external_tts_job_active`。日志记录 requestId、
  回复 generation/state/age、两类队列大小和外部任务状态，但不记录播报正文。

服务器通过同一 WebSocket 主动推送状态：

```json
{
  "type": "external_tts",
  "event": "status",
  "success": true,
  "jobId": "tts-job-...",
  "sentenceId": "sentence-...",
  "requestId": "task-web-run-123-task-1",
  "status": "streaming",
  "stage": "sending_audio",
  "progressPercent": 60,
  "voiceSource": "device_agent",
  "exactText": true,
  "chatHistory": true,
  "chatHistoryAudio": true,
  "completionBoundary": "server_audio_sent",
  "devicePlaybackConfirmed": false
}
```

状态包含 `queued / synthesizing / streaming / completed / failed / interrupted`。设备也可发送
`{"type":"external_tts","action":"status","jobId":"tts-job-..."}` 查询当前状态；只能查询
当前设备自己的任务。服务端 TTS 路由的 `completed` 只表示服务端音频已经通过 WebSocket 发送完成；
设备 TTS 路由则必须收到本节定义的 `played` ACK 才能完成。

### 4.4 设备端 TTS 文本与播放确认

实际输出路由为 `device` 时，`DeviceTTSProvider` 只作为现有 TTS 队列的兼容 façade，不调用服务端合成厂商，
也不生成或伪造 Opus 音频。回复仍先发送一次既有 `tts/start`，随后每个文本分片发送：

```json
{
  "type": "tts",
  "state": "text",
  "session_id": "session-id",
  "sentence_id": "sentence-id",
  "generation": 12,
  "sequence": 1,
  "text": "好的，我现在打开客厅灯"
}
```

同一句的 `sequence` 从 1 单调递增；文本全部下发后发送：

```json
{
  "type": "tts",
  "state": "text_end",
  "session_id": "session-id",
  "sentence_id": "sentence-id",
  "generation": 12,
  "sequence": 1
}
```

`text_end` 只表示服务端文本发送完毕。设备完成物理播放后必须回：

```json
{
  "type": "tts_ack",
  "sentence_id": "sentence-id",
  "generation": 12,
  "event": "played"
}
```

`event` 只允许 `played / failed / aborted`。`played` 才让 ReplyLifecycle 进入 `COMPLETED`、上报 assistant 文本并发送一次
`tts/stop`；`failed` 进入失败，`aborted` 进入中断。ACK 等待由
`speech_routing.device_tts_ack_timeout_seconds` 控制，默认 30 秒；超时终态原因为 `device_tts_ack_timeout`。
ACK 必须带服务端下发的 sentence 与 generation；迟到、重复或不匹配的 ACK 被忽略，
不能结束新回复。用户 Abort 会解除当前 ACK 等待并沿用同一 generation 的 stop 去重。

端侧 TTS 当前只支持 `ContentType.TEXT`；音乐或其他 `ContentType.FILE` 明确以
`device_tts_file_unsupported` 失败，不在当前半轮改用服务端 TTS。设备 TTS 不支持服务端 provider 的音色、语速、音量和
音调 session override；这类控制仍属于设备固件自身能力。聊天记录只有收到 `played` 后才保存 assistant 文本，且没有服务端
合成音频可附加；`external_tts` 同样等待 playback ACK 才能报告完成。

### 4.5 智控台真实音频旁路监听

设备管理页通过 manager-api 的 `POST /device/{id}/audio-preview-ticket` 获取 60 秒短期票据，
再连接 `wss://host/xiaozhi/audio-preview/`。票据必须经过当前用户的设备 workspace 读取权限校验，
并在 WebSocket 建立后的首条 JSON 消息中提交；Token 不进入 URL 或日志。

`sendAudioHandle._do_send_audio()` 只在设备 WebSocket 成功发送原始 Opus 包后，才通过有限内存队列
复制同一字节帧给预览连接。复制使用非阻塞入队：监听页变慢、断线或队列溢出只会丢弃预览积压并发送
`discontinuity`，不得等待、取消或改变设备下行发送。每台设备同时只允许一个预览连接；预览只包含
下行音频，不包含麦克风上行，也不在服务端保存录音。

预览控制帧包含 `ready / stream_start / stream_stop / discontinuity / listener_busy / error`，二进制帧为
裸 Opus 单帧。Web 端使用项目内置 `opus-decoder` 解码，并按 `stream_start` 提供的采样率播放和绘制波形。

### 4.6 流控

```text
utils/audioRateController.py  AudioRateController
  - 前 5 帧立即发送 (PRE_BUFFER_COUNT)
  - 后续帧按 60ms 精确定时发送
  - client_abort 时通过 CancelledError 取消
```

- 整轮 TTS 不设置固定播放时长上限；收到 `LAST` 后，等待预算按队列中剩余音频帧的真实播放时长动态计算，并额外保留单包发送超时与收尾容差。
- WebSocket 半开保护作用于单个音频包发送。单包超过 `audio_send_timeout_seconds` 仍未完成时，后台发送任务失败并清理队列，避免异常连接永久占用会话；正常的长语音不会因为总时长超过 20 秒被截断。

### 4.7 对话延时链路协议

聊天记录中的延时指标使用 `schema_version=3`，新增实际解码时长、RMS/SNR 声学门控和分离的 EOS/ASR 完成耗时；管理端导出与页面继续兼容 `schema_version=2`，不兼容更早的单值 `latency_ms` 口径。
新协议只有两个权威入口：

```text
stages       VAD / ASR / 声纹等输入阶段
trace.steps  按实际发生顺序记录 LLM / 工具 / TTS / 音频发送步骤
```

典型工具调用轮次：

```text
LLM #1 depth=0
  -> TOOL #1
  -> LLM #2 depth=1
  -> TTS
  -> AUDIO_DELIVERY
```

`trace.totals.llm_model_ms` 只累计模型请求本身；
`trace.totals.model_tool_chain_ms` 从第一次 LLM 请求开始计时，到最后一次 LLM 完成，包含中间工具等待；
二者不得混用。

TTS 必须区分以下边界：

| 字段 | 含义 |
| --- | --- |
| `request_to_first_audio_ms` | 文本提交给 TTS provider 到首个音频帧 |
| `provider_generation_ms` | 文本提交到 provider 明确完成整段生成 |
| `audio_duration_ms` | 按 Opus 60ms 帧数估算的输出音频时长 |
| `report_ready_ms` | 文本提交到聊天记录指标可上报 |
| `audio_delivery.from_tts_request_ms` | 文本提交到服务端 WebSocket 音频发送队列排空 |

`audio_delivery.boundary=server_websocket_send_complete` 只表示服务端发送完成。
当前设备协议没有扬声器播放完成 ACK，因此必须同时记录
`device_playback_confirmed=false`，不得把该字段解释为设备实际播放完成。

运行时按 `turn_id` 隔离每个输入轮次，并在助手开始回复时把 `sentence_id`
绑定到对应轮次。LLM、工具、TTS 和音频发送完成事件都必须写入这组显式标识；
用户打断或快速进入下一轮时，上一轮迟到的 `LAST` 只能完成或丢弃上一轮指标，
不得清空当前轮次，也不得产生“LLM 0 次、只剩音频发送一步”的串轮记录。
该约束只作用于新产生的聊天记录，不回写或推断历史 `metrics_json`。

每条新消息还携带 `message_role` 和同轮递增的 `message_sequence`。ASR 在进入同步语义/意图工具前先
预留用户序号，因此即使工具调用更早完成数据库写入，manager-api 和 manager-web 仍统一按
`started_at_epoch_ms -> message_sequence` 还原为“用户 -> 工具调用 -> 工具结果 -> 助手”；缺少这些字段
的旧记录继续回退到 `createdAt`。数据库中的真实创建时间仍保留用于审计。

聊天记录导出包含 `summary.csv` 和逐步骤 `trace.csv`。递归模型调用必须按
`call_index` 和 `depth` 分行保存，禁止再次压缩成单个“LLM耗时”。

### 4.8 日程提醒主动播报

日程管理与周期调度的权威契约见[服务端提醒任务中心](reminder-task-center.md)。当前投递适配器使用已有在线 WebSocket 的设备 TTS 下行能力，小程序和 Python 不运行另一套持久日程调度器。完整链路是：

```text
语音 ASR -> 主 LLM 选择 device_reminder Server Plugin
          -> manager-api 校验并写入 MySQL
小程序   -> manager-api 创建 / 编辑 / 取消 / 查询同一份提醒事实
manager-api 有界到期扫描与共享下发预算 -> xiaozhi-server 精确投递接口及接纳保护
                      -> 当前在线且空闲的目标设备 TTS 队列
```

语音侧的 `device_reminder` 只有在 `Intent.<selected>.functions` 白名单中启用才会注册，支持一次性及受限周期安排的
`create / list / cancel`；它是 Server Plugin，不是外部 no-code MCP。manager-api 和 MySQL 是安排内容、时间、规则、
单次执行和重试结果的唯一权威，每个安排只滚动下一次执行，不提前展开无限历史。小程序编辑后直接修改这份事实。
语音创建记录的设备同时是来源幂等锚点：小程序仍可修改标题和时间，但不能把该记录改投其他设备；需要换设备时应先取消再新建。

`device_reminder` 是 Server Plugin 保留名。Server MCP、Device IoT、Device MCP、MCP Endpoint 的同名工具，
以及带一层或多层 `mcp_` 前缀的别名，都会在统一注册和执行边界被拒绝，不能替代或绕过白名单中的真实
Server Plugin。Native Qwen/Doubao Realtime 当前没有独立主动通知 TTS，即使智能体白名单已配置该工具，
也不会在 Realtime 会话中注册或执行。小程序仍可选择绑定此类智能体的设备并保存提醒，但到点无法播报，
本次执行在有效期或重试预算耗尽后会标记未送达，周期安排按规则继续推进。

用户端接口按当前 workspace 校验提醒和设备资源所有权；语音运行时通过 server secret 进入受保护的
`/config/**` 边界，并再次验证发起连接的设备仍绑定目标智能体。到期投递请求必须同时携带 `deviceId`、
`agentId` 和由提醒 ID + 投递版本指纹派生的 `requestId`；指纹覆盖目标设备/智能体、播报内容和计划时间。
同一业务版本的普通重试保持同一个 `requestId`，编辑这些字段后生成新的 `requestId`。xiaozhi-server 只在
在线连接同时匹配设备与智能体时接受，相同 `requestId` 重试返回幂等结果，不重复入队。
调度器领取提醒后，会在独立事务中按设备、提醒的固定顺序加锁并复核租约；设备行锁覆盖有界的内部 HTTP 投递和状态回写，
因此设备换绑或删除只能在线性化投递完成前或完成后生效，不能插入“校验通过、尚未入队”的窗口造成错投。

设备正在对话、会议、声纹采集或其他受保护状态时不抢占；设备忙碌、短暂离线和下游过载由 manager-api 在有限
有效期及尝试预算内退避重试，耗尽后本次执行标记未送达。Python 在去重与设备状态检查后执行有限速率、设备冷却和容量保护；过载明确拒绝，不创建无限等待队列。Python 只负责实时连接查询和精确 TTS 入队，不保存日程、
不承担跨进程调度，也不把按智能体广播当作按设备精确投递。

manager-api 的 `DELIVERED` 只表示 Python 已接受并入队，或对相同 `requestId` 返回已接收的幂等结果；
提醒链路当前不等待设备播放 ACK，不能据此判断 ESP32 扬声器已经物理播放。Python 的回执缓存目前是单进程
内存状态；多 Python 实例或进程重启场景若要进一步降低重复播报，需要 sticky routing 或共享、持久化的回执
存储，在没有设备播放 ACK 时仍不能保证跨进程严格 exactly-once。产品暂定休眠可由下游唤醒，本轮只实现服务端任务管理与下发，
不修改或验证设备固件。当前适配器在没有 WebSocket 时仍返回离线，不能据此宣称已经实现物理唤醒。提醒正文和服务密钥不得进入日志，运行日志只记录 reminder/request/device 标识、
投递状态和稳定错误码。

## 5. 声纹注册采集模式

声纹注册不再通过普通语音对话触发。智控台点击“开始录音”后，`manager-api` 调用
`xiaozhi-server` 的 HTTP 控制端点，指定在线设备进入连接级采集模式：

```text
POST /device/voiceprint/enrollment/start
Authorization: Bearer <server.secret>

{
  "deviceId": "设备MAC",
  "requestId": "采集任务ID",
  "agentId": "智能体ID",
  "sourceName": "说话人姓名",
  "targetSamples": 1,
  "minDurationSeconds": 3.0,
  "maxDurationSeconds": 30.0,
  "completionSilenceSeconds": 2.0,
  "reportEndpoint": "/agent/voice-print/enrollments/{id}/device-samples"
}
```

采集模式 active 时，设备继续按原 WebSocket 协议上传音频。服务端只在 VAD 句末把该段音频转成
16k 单声道 WAV 并上报到 `manager-api`，不会进入普通 ASR、语义、LLM 或工具调用链路。
当前设备采集流程只要求朗读 1 段文本：用户点击“开始录音”后朗读屏幕文本，读完持续安静至少 2 秒；
声纹采集状态会把句末静音下限提高到 2 秒，普通对话仍使用原 VAD 配置。达到连续静音下限后才上报样本。
`manager-web` 轮询到样本落库后自动调用完成接口注册声纹，
不再提供人工“停止录音”入口，也不依赖 `/device/voiceprint/enrollment/stop` 截断正常采集音频；但用户关闭弹窗、重启任务或任务超时时，manager-api 会携带任务 ID 调用该端点清理运行时状态。停止请求只能结束匹配的当前任务，迟到请求不得终止更新后的采集。
`startToChat()` 也会兜底检查采集状态，防止流式 ASR 或其他入口漏过采集分支后继续触发普通对话。

采集任务超过 2 分钟仍为 `COLLECTING` 时进入 `EXPIRED`。Web 主动关闭或重新开始会调用取消接口，避免设备断开后数据库长期残留采集中状态。

为了提升设备端体验，服务端会额外下发状态事件：

```json
{
  "type": "voiceprint_enrollment",
  "event": "sample_collected",
  "session_id": "当前会话ID",
  "requestId": "采集任务ID",
  "state": "collecting",
  "active": true,
  "sampleCount": 1,
  "targetSamples": 1,
  "reason": "",
  "prompt": "已采集完成，正在注册声纹。"
}
```

设备端可选处理这些事件：

| event | 建议设备表现 |
|-------|--------------|
| `started` | 进入声纹注册 UI/灯效，提示用户安静环境、正常音量说话 |
| `sample_collected` | 显示进度，如 `1/3`，短提示音或屏幕反馈 |
| `sample_rejected` | 显示失败原因，提示重新说一段 |
| `sample_failed` | 显示上传失败，提示重新采集 |
| `completed` | 显示采集完成，等待管理端自动提交注册 |
| `stopped` | 退出声纹注册 UI/灯效 |

同时，服务端会通过现有 TTS 通道播报 `prompt` 文案。旧固件即使不识别
`voiceprint_enrollment` JSON，也能听到语音提示；新固件可以识别该事件做屏幕、灯效和按键取消。

注册事务提交后，`manager-api` 会调用受 `server.secret` 保护的
`POST /device/voiceprint/refresh`。`xiaozhi-server` 为目标在线连接重新拉取运行时配置，先构造并校验
新的声纹 provider，再原子替换旧 provider；刷新失败不回滚已经成功的注册，设备下次重连仍会读取最新配置。

## 6. 打断机制（Barge-in）

### 6.1 官方式即时打断

```text
helloHandle.py
  - features.aec=true -> conn.client_aec=true

receiveAudioHandle.py:handleAudioMessage()
  - AEC + VAD 确认开口 + 正在播报 + 非 manual
  - 立即 handleAbortMessage(conn, preserve_user_audio=True)
  - 不等待句尾、完整 ASR 或 LLM
  - 用户音频继续进入 ASR

receiveAudioHandle.py:startToChat()
  - 非 AEC 路径取得完整 ASR 后，如果仍在播报则直接 abort
  - ASR 文本继续进入 SemanticCenter / Intent / Chat
```

交互注意力的目标打断策略为 `barge_in_policy=balanced`。在无音区 MVP 中，明确唤醒、按键触发或
`AWAITING_REPLY` 状态的用户开口可以快速打断；其他重叠人声应先取得 Addressing 结果，只有 `DIRECTED` 才正式
终止当前回复。`UNDIRECTED` 与 `UNKNOWN_ADDRESSING` 不应产生新回复。默认 `shadow` 不改变上述现有即时打断实现，
只记录如果启用门控将采取的判断；该策略需在后续代码任务中实现。

### 6.2 Abort 处理

```text
handle/abortHandle.py:9-38  handleAbortMessage()
  - conn.client_abort = True (加锁)
  - increment abort_generation
  - 清空 TTS 队列
  - 普通 abort 清空 ASR/音频状态；AEC 开口打断保留当前用户音频
  - 发送 stop TTS 消息（try/except 保护）
```

### 6.3 手动清空短期上下文

智控台设备管理页可对一台在线设备执行“清空上下文”。浏览器只提交设备数据库记录 ID；manager-api 校验当前
用户对设备 Workspace 的写权限，并从数据库事实派生 MAC 与 agentId，再通过内部 Bearer 服务密钥调用
xiaozhi-server。Python 必须同时匹配活动连接和 agent 绑定，禁止仅凭请求体控制其他智能体连接。

传统拼接模式清除当前连接的 Dialogue 与游戏/语义软状态；Realtime 模式还按 adapter 能力创建新的上游
session，重新注入 system prompt、工具和当前会话音色。清空不删除后台聊天记录或长期记忆。为了避免旧回复在
清空后迟到写回，设备正在收音、生成或播报时返回忙碌，管理员应在本轮结束后重试；会议、通话和声纹采集期间
同样拒绝。

## 7. IoT 描述符

### 7.1 设备上报

```text
设备发送: {"type": "iot", "descriptors": [{"name": "light", "properties": {...}, "methods": [...]}]}
  → textHandler/iotMessageHandler.py:19-22  分发
  → providers/tools/device_iot/iot_handler.py:63-120  handleIotDescriptors()
```

### 7.2 服务端门控

```text
iot_handler.py:36-61  SERVER_GATED_IOT_DEVICES
  - "hass" → Home Assistant（需真实启用 HA 工具并具备有效 URL/Key）
  - 不在映射中的设备名默认拒绝
  - 兼容旧版 Device IoT 时，必须在服务端 `device_iot_allowlist` 显式列出设备名
  - 门控失败 → 静默丢弃（记录 info 日志）
```

### 7.3 工具注册

每个 IoT 描述符被转换为 `IotDescriptor` 对象，通过 `DeviceIoTExecutor` 注册为 LLM 工具。工具名格式：
- 查询：`get_{devicename}_{property}`
- 控制：`{devicename}_{method}`

**注意**：工具名解析存储了 `(device_name, property/method)` 到 `_tool_device_map` 的映射，不依赖 `split("_")` 分片。

## 8. Device MCP

“音量”必须先区分对象：Device MCP 的扬声器音量控制 ESP32 硬件，智能体 `ttsVolume` 控制厂商合成参数，Web `<audio>` 音量只控制浏览器试听。`self_audio_speaker_set_volume` 等设备工具由设备运行时上报并受服务端门控，不是 Python 内置 TTS 配置；三层数值范围不能互相复用。

### 8.1 MCP 能力声明

```text
helloHandle.py:54-66
  hello 消息 features.mcp == true
  → 创建 MCPClient 实例
  → send_mcp_initialize_message(conn)
```

### 8.2 MCP 握手

```text
mcp_handler.py:253-288  send_mcp_initialize_message()
  1. initialize (id=1, protocolVersion: 2024-11-05)
  2. 设备回复 → tools/list (id=2)
  3. 设备回复工具列表 → name_mapping 清理 → MCPClient.set_ready(True)
```

### 8.3 清理规则

```text
util.py:573-576  sanitize_tool_name()
  点号 → 下划线：self.audio.speaker.set_volume → self_audio_speaker_set_volume
```

### 8.4 工具调用

```text
DeviceMCPExecutor.execute() → mcp_handler.py:317-426  call_mcp_tool()
  - 控制工具 (FAST_RESPONSE_CONTROL_TOOLS) → 3s 超时
  - 其他工具 → 10s 超时
  - JSON-RPC 2.0 tools/call → 等待 Future → 返回结果
```

## 9. OTA 固件升级

### 9.1 版本检查

```text
ota_handler.py:143-353  OTAHandler.handle_post()
  - 读取 device-id, client-id, device-model, firmware-version 请求头
  - 扫描 data/bin/*.bin 固件文件（按 {model}_{version}.bin 格式）
  - 版本比较（数值元组，支持不同长度）
  - 返回：server_time, firmware.version, firmware.url
```

### 9.2 固件下载

```text
ota_handler.py:372-415  OTAHandler.handle_download()
  - 文件名安全化：regex ^[A-Za-z0-9\.\-_]+\.bin$
  - 路径遍历防护：os.path.realpath 比较
  - 返回 web.FileResponse 流式下载
```

### 9.3 URL 构建

OTA 下载 URL 使用 `urllib.parse.urljoin` 从 `server.vision_url` 的基础 URL 构建，不再依赖字符串替换。

## 10. 连接生命周期

### 10.1 注册

```text
device_runtime_registry.py:59-81  register_connection()
  - 全局字典：device-id + client-id → ConnectionHandler
  - MQTT 网关：分割 @@@ 组 ID
  - 重连时覆盖旧条目（记录 warning）
  - 注销时仅删除当前仍指向该连接对象的键；旧连接晚关闭不能删除新连接映射
```

### 10.2 超时检测

```text
connection.py:1855-1884  _timeout_checker()
  - 每 10 秒检查 last_activity_time（毫秒）
  - 超时 (> timeout_ms) → close()
```

### 10.3 关闭流程

```text
connection.py:1673-1786  close()
  1. VAD 连接资源释放
  2. 取消超时任务
  3. func_handler.cleanup()（MCP 清理 + ServerMCP 清理）
  4. 停止 ReportThreadManager
  5. 设置 stop_event
  6. 清空队列 (asr_audio_queue, tts_text_queue, tts_audio_queue)
  7. 清空 IoT 描述符
  8. 取消 pending TTS futures
  9. 关闭 WebSocket（3 种回退策略）
  10. 关闭 TTS、ASR
  11. 关闭 ThreadPoolExecutor
```

### 10.4 保存与关闭

```text
connection.py:325-360  _save_and_close()
  1. 生成聊天标题（非守护线程）
  2. 保存记忆（非守护线程）
  3. PowerMem 会话处理（非守护线程）
  4. 所有线程 join(timeout=10)
  5. await self.close(ws)
```

## 11. 关键文件

| 文件 | 行数 | 职责 |
|------|------|------|
| `core/connection.py` | 1977 | 中央状态机 |
| `core/websocket_server.py` | 193 | WebSocket 服务器 + 连接管理 |
| `core/handle/receiveAudioHandle.py` | ~210 | 音频处理入口 + 打断 + 聊天触发 |
| `core/handle/helloHandle.py` | ~130 | Hello 消息 + MCP 初始化 |
| `core/handle/sendAudioHandle.py` | ~260 | TTS 音频发送 + 流控 |
| `core/handle/abortHandle.py` | ~40 | 打断协调 |
| `core/handle/audioInitializer.py` | ~64 | TTS/ASR/Voiceprint 初始化 |
| `core/handle/reportThreadManager.py` | ~68 | 报告线程管理 |
| `core/handle/textHandler/iotMessageHandler.py` | ~22 | IoT 消息分发 |
| `core/providers/vad/silero.py` | ~120 | Silero VAD |
| `core/providers/asr/base.py` | ~180 | ASR 基类 |
| `core/providers/tts/base.py` | ~500 | TTS 基类（文本+音频双线程） |
| `core/external_tts.py` | — | 已认证设备原文 TTS、幂等、忙碌门控和状态任务 |
| `core/providers/tools/device_iot/iot_handler.py` | ~150 | IoT 描述符处理 + 门控 |
| `core/providers/tools/device_iot/iot_executor.py` | ~80 | IoT 工具执行 |
| `core/providers/tools/device_mcp/mcp_handler.py` | ~430 | MCP 握手 + 工具调用 |
| `core/providers/tools/device_mcp/mcp_client.py` | ~10 | MCP 客户端（继承 BaseMCPClient） |
| `core/providers/tools/device_mcp/mcp_executor.py` | ~60 | MCP 工具执行器 |
| `core/api/ota_handler.py` | ~415 | OTA HTTP 处理器 |
| `core/api/device_mcp_handler.py` | ~70 | Device MCP REST API |
| `core/device_runtime_registry.py` | ~100 | 全局连接注册表 |
| `core/semantic/interruption.py` | ~130 | 语义中断分类器 |
| `core/utils/audioRateController.py` | ~265 | 音频发送流控 |

## 12. 已知修复（2026-07）

| # | 问题 | 修复 |
|---|------|------|
| 1 | `client_abort` 无锁跨线程读写 | 添加 `threading.Lock` |
| 2 | Abort WebSocket send 无 try/except | 包裹 try/except |
| 3 | IoT 工具名解析依赖 split("_") | 改用 `_tool_device_map` 字典 |
| 4 | IoT 状态类型比较永远失败 | 改为类型转换而非类型比较 |
| 5 | OTA URL 字符串替换脆 | 改用 `urllib.parse.urljoin` |
| 6 | 中断分类器线程不可取消 | 超时时 cancel future |
| 7 | MCP JSON 解析不支持嵌套 | 改进正则支持一层嵌套 |
| 8 | `_modules_ready` 缺少同步 | 添加 threading.Event |
| 9 | 注册表静默覆盖旧连接 | 添加 warning 日志 |
| 10 | `_save_and_close` 守护线程丢数据 | 改为非守护 + join(10s) |
| 11 | 唤醒后固定丢弃 2 秒音频 | 新增 `wakeup_vad_ignore_ms`，默认 0，可配置恢复兼容窗口 |
| 12 | 默认欢迎语阻塞首轮交互 | `enable_greeting` 默认改为 false |
| 13 | 豆包录音 ASR 同步 HTTP/休眠阻塞事件循环 | 改为 aiohttp 异步提交、轮询和总预算超时 |
| 14 | WebSocket 请求头脱敏正则可能泄露令牌 | 按 header 键结构化屏蔽 Authorization/token/api-key/cookie/secret |
| 15 | 旧连接注销误删新连接映射风险 | 注销前校验注册表中的对象身份，并增加覆盖重连测试 |
| 16 | 豆包流式 ASR 判停、等待边界和重连清理不完整 | 本地 VAD 判停发送 LAST，增加 10s 初始化、15s 中间结果和 5s 最终结果预算，并按会话身份清理状态 |
| 17 | SeedTTS2 不足 60ms 的 Opus 尾帧跨会话残留 | 正常结束冲刷尾帧，取消/失败/断链重置编码器 |
| 18 | 声纹采样截断导致长句后半段身份特征丢失 | 对齐官方上游：VAD 句末上传当前整段语音 WAV，与 ASR 并发识别；`server.voiceprint_sample_seconds` 保留为历史参数但运行时不再使用 |
| 19 | function-call 普通文本延后入队时，SeedTTS2 上游会话因提前启动而空闲失效 | 逻辑 `FIRST` 只建立轮次状态，首段有效 `TEXT/FILE` 到达时才懒启动上游会话，并保持设备 `FIRST/LAST` 幂等 |

## 13. 排查方法

### 唤醒优化边界（2026-09-21）

观测版本 `3de8b802` 的单次关联样本：detect 到首音频成功发送 3994.136ms；初始化排队约 2052ms，其中提示增强 1605.282ms（包含天气 1494.239ms、位置 104.039ms，不重复相加）；长期记忆 222.932ms，LLM 首有效文本 987.137ms。配置 API 已在 detect 前结束，不再计入等待。样本数仅 1，发送时间不是扬声器出声时间，无平均值或 P95。

本地优化借鉴开源 `xinnan-tech/xiaozhi-esp32-server` 的并行准备方式，而非声称采用 xiaozhi.me 私有云实现：

- `RuntimeReadiness` 分离纯唤醒和完整对话门禁。绑定、权威角色配置、LLM、TTS、工具/身份初始化和本地系统提示完成后，纯唤醒可运行；位置、天气和动态上下文继续刷新。缓存未命中时首句不包含这些最新外部信息，长期记忆检索不变。
- 普通指令仍等待完整就绪；初始化期间的 ASR 结果有限等待，不删除录音。失败、关闭、配置对象替换时拒绝旧输入，超时沿用就绪队列预算。热更新不启用快速通道。
- `connection.py` 仅保留既有初始化调用顺序调整和提示渲染参数，门禁逻辑留在 `core/session/runtime_readiness.py`，不新增中央状态机职责。
- 火山双流 TTS 的 FIRST 可预热可复用传输连接，与记忆/LLM 等待重叠；不发送 StartSession、不生成音频。正文到达才建合成会话，避免长工具轮的空会话失效。预热与正文握手共用锁，失败可重试，关闭可取消，失效连接重新建立。
- 不改变缓存问候开关、唤醒去重、普通问题的工具和记忆语义，不新增提示音或录音屏蔽窗口。

用户批准后已发布 `81ec6f46-lite`。11:38 的单次样本中，detect 分发到首音频成功发送为 2019.546ms，连接进入到发送约 3045ms；唤醒放行不再等待外部上下文，TTS 预热 203.745ms 与记忆等待重叠。剩余关键阶段为初始化放行约 474ms、记忆 433.858ms、LLM 首有效文本 689.814ms、文本出队到提交 54.458ms、提交到上游首音频 350.675ms、原始音频到发送 13.429ms。前后各仅一个样本、外部耗时不同，不能宣称稳定提速比例；也不能将首音频发送当作设备出声或排除播放中途断续。回滚按唯一生产部署流程回到 `3de8b802`，不涉及数据库迁移。

### 控制面与 LLM 初始化细分观测（开发阶段，未发布）

- `detect.received` 仅表示应用消息分发入口，不是底层网络到达时间。WebSocket 循环逐条等待路由处理，前序消息等待绑定或事件循环工作可延后该标记；不能直接把连接到 detect 的差值归到设备。
- 既有 `session_latency` 增加请求级 `request_id`；只对模型配置、纠错词、记忆查询三个 manager-api POST 添加随机 32 位十六进制 `X-Xiaozhi-Trace-Id`。Java 校验并回显该标识，`config_latency.trace_id` 与之关联。标识不承担身份认证，权限链路不变。
- Python 的 `api.models`、`api.correct_words`、`api.memory` 记录 `client_acquire`、HTTP trace 的 TCP/TLS、请求发送、响应头/响应体等待与 `total`。复用连接时没有 TCP/TLS 阶段是正常现象，不能捏造为零耗时；`receive_headers` 包含服务端处理和网络等待，并非纯网络耗时。
- Java 记录控制器范围耗时、配置的设备/角色解析和组装、记忆 DAO 调用、词法筛选和容量设置读取。参数读取额外区分 `params.cache_read`、`params.db_fallback`、`params.cache_write`；只在上述请求 scope 内计时，不记录参数键值。DAO 调用包含连接池等待与结果映射，不等于数据库纯执行时间；容量设置可能从 Redis 回源 SQL，不等于纯 Redis 时间。控制器外的鉴权、请求反序列化、响应序列化和代理等待仍不在 Java 根 span 中，不能把 Python 总耗时减 Java 耗时全部解释成网络。
- LLM 初始化增加 `init.llm.provider_import`（带命中标记）、`provider_construct` 和 OpenAI `client_construct`，以区别动态导入和 SDK 构造。阶段嵌套包含，不得相加；不进行额外模型请求或 SDK 全局替换。
- Python ContextVar 和 Java 请求 scope 在退出、异常和取消时恢复，避免并发连接/线程复用串线。只记录受控阶段名、随机标识、耗时和状态，不记录请求/响应正文、SQL参数、记忆内容、设备信息或密钥。

此次只增加观测，保持现有 HTTP 连接复用策略、重试、记忆检索、身份过滤、实时 LLM 问候及去重不变。无新生产数据，不宣称进一步变快；验证不调用付费模型。撤销本节对应的观测源码即可回退，不需要改配置或数据库。

### 后续只读排查（2026-09-21，生产仍为 81ec6f46-lite）

没有重启或修改配置，仅在现有容器中以独立诊断进程执行有限只读查询，不进行设备批量唤醒或模型调用；正文和密钥未输出。该实验不是优化后端到端性能验收：

- 相同设备的记忆查询、相同唤醒查询文本，经现有 Web 代理且禁用复用的 3 次耗时为 22.558/14.144/39.842ms，TCP 建连为 3.031/1.556/1.180ms；返回上下文均为 97 字符（仅核对长度，不代表逐字比对）。API 直连为 60.495/16.873/15.670ms，复用 Web 连接为 57.832/89.065/15.162ms。独立进程、顺序执行且缓存状态不同，不能作统计对照；但未复现历史 434ms，不能把全部延迟归为 TCP 或认为开启 keep-alive 已有收益证据。
- 现有模型配置查询 3 次为 155.022/200.772/197.608ms，交替执行的纠错词查询为 13.931/82.394/14.819ms。6 次请求均 HTTP 200、业务 code=0；未请求更改配置。
- 管理 API 的 `cpu.max` 为 `60000 100000`（0.6 核配额）。上述 6 次请求期间 `nr_throttled` 从 490 增至 497，`throttled_usec` 增加 1,072,725，CPU 使用计数增加 484,323 微秒，确认这段窗口发生配额限流。限流计数属于整个容器，可能汇总多个调度实体；不能将 1.073 秒当成某一请求的等待，更不能回溯认定历史 434ms 全由限流造成。
- 独立 Python 进程冷导入 OpenAI SDK 为 456.401ms，随后客户端构造为 50.341ms（无网络）。这说明冷导入值得拆查，但进程导入状态与实际服务不同，不证明历史 provider 创建 385ms 的具体来源。
- MySQL `performance_schema` 与慢查询日志均未开启，没有擅自启用；历史数据库阶段无法事后恢复。下一步可在批准的维护窗口进行有回滚的 CPU 配额单变量对照，并结合上述细分观测，先验证资源瓶颈，不削减记忆或身份能力。

```bash
# 查看设备连接
scripts/dev logs server -f | grep -E "设备连接|device-id|handle_connection"

# 查看 IoT 描述符
scripts/dev logs server -f | grep -E "iot|descriptors|允许的 IoT"

# 查看 MCP 握手
scripts/dev logs server -f | grep -E "mcp|tools/list|initialize"

# 查看 OTA 请求
scripts/dev logs server -f | grep -E "OTA|firmware|ota_handler"

# 手动部署后检查语音长尾（不要输出请求头或私有配置）
scripts/dev logs server -f | grep -E "录音识别完成|录音识别失败|查询录音识别结果超时"
```

2026-07-11 的源码修复没有构建镜像、没有部署或重启腾讯云服务。生产验证必须等用户手动发布后执行，并同时观察设备串口、ASR 阶段耗时、WebSocket 重连映射和日志脱敏结果。

## 14. 手机连续语音调试（2026-10-13）

独立 Android App 通过管理 API 的 POST /mobile/voice-session 建立普通用户会话。Shiro 显式保护此入口；仅 USER、当前 workspace 与所选智能体一致且有写权限时允许签发。输入为 agentId 和安装 UUID，不接受客户端指定实体 device-id。使用用户/安装/智能体的 SHA-256 派生 mobile- 身份，首次创建独立虚拟设备并绑定所选智能体，后续检查完整归属后复用。它会显示在设备列表，使用智能体真实模型、知识库、工具、记忆、对话日志和服务额度，不是隔离沙盒。

返回 WSS 地址、设备身份、随机 mobile-voice- client-id、HMAC token，120 秒内建连，最长 1800 秒。客户端凭证由 Android 原生持有，不交给界面脚本。公共 OTA 拒绝给 mobile 身份签发 token；Python 对此保留前缀强制校验新鲜凭证，设备白名单和 auth.enabled=false 均不能旁路。物理设备原有凭证时效保持不变。本轮通过新增 Liquibase 迁移补充手机额度查询索引，不新增手机身份表。

手机签发按用户、工作空间、设备的锁序执行事务，锁等待后重新校验授权，失败不遗留半初始化身份。`xiaozhi.mobile-voice.max-devices-per-user-workspace` 默认 10、硬范围 1 至 50；仅约束此手机签发入口创建的身份，不是全平台设备总配额，复用身份不占新名额。查询通过专用 workspace/user/board 索引定位。Python 手机凭据必须在 HTTP 请求头中，拒绝 URL 中传递；同手机每个 Python 进程仅允许一个活动会话，重复连接返回 1013。多实例仍需按手机身份固定路由，不能声称跨实例分布式会话限制已实现。

App 0.11 在通话前读取一次当前空间额度摘要；已知额度不足、冻结或空间限额时不开始录音。
本人零额度可额外读取一次注册赠额状态，区分未到账与已消费；其他成员不能读取负责人的个人赠额。
预检只是用户体验提示，不能替代真实模型调用时的服务端准入。

App hello 声明整数 `features.voice_status=1`；服务器 hello 回显同一能力后发送
`{type:"voice_status",version:1,status:"initializing"|"ready"|"error",session_id:...}`。
`ready` 必须在 `audio_input_ready` 与 `conversation_ready` 均真正就绪后发布，
由已有能力事件通知而非周期轮询；初始化最多等待 18 秒，失败和缺失能力明确返回。
App 必须同时收到能力 ACK、speech_route(server/server) 和 ready 才发送 listen start mode auto
并启动录音；保留 20 秒客户端准备超时。旧服务器无能力 ACK 时明确提示协议不兼容，不能假装通话成功。
持续上传仍为 16 kHz 单声道、60 ms raw Opus，由服务器 VAD/ASR 分句。不能上传 PCM 冒充 Opus。
下行 raw Opus 由 App 解码为 24 kHz PCM；hello 回显的是输入参数，不应据此设定播放采样率。

工具进度兼容旧设备的 `type=stt` 外形，但明确添加 `source=tool`；它不是 ASR，不应进入用户转录
或重置识别到首音频的计时。App 0.12 显示独立状态，不把 `% device_reminder` 当成用户发言。
对未携带来源的旧服务器，仅兼容严格的 `% <工具标识>` 显示格式；明确的 ASR 来源优先，
不能按任意百分号文本过滤用户语音。此修正不改变工具权限、聊天事实或 TTS 内容。

App 0.12 的 Concentus 固定版本带可复现的 HYBRID 修正：CELT 降采样输出在 `accum` 模式下
必须与已有 SILK PCM 饱和相加，不得覆盖低频。修复保留 24 kHz 播放和正常语速，不以降采样绕过；
库来源、补丁、SHA 与 SILK/HYBRID/CELT 波形回归在 App 工作区 `tools/CONCENTUS.md`。
离线波形一致不等同于真机扬声器、AEC 和播放时序验收，发布状态仍以手机验收记录为准。

状态错误只包含白名单 `code`、阶段 `stage`、固定提示、`retryable` 与 `retry_after_ms`，
不回显异常原文，不为提示调用付费 TTS。共享 ASR 异常边界和托管豆包录音/流式通路传播错误；
正常静音和空识别结果不伪装成故障。额度拒绝阻断本连接后续识别尝试；新 App 停止录音，需用户重新开始。
就绪后若配置热更新或托管记忆变化导致 Realtime 会话重建、能力失效，通过 `VOICE_SESSION_INVALIDATED`
结束协商客户端的本次通话，要求手动重开；当前没有暂停/恢复协议，不能维持录音却静默丢帧。
事件失效计数保留快速 clear/set 的变化，无状态变化不重复通知；普通对话轮次不会因此重建。
旧设备不协商此扩展时保持原握手，仅收到兼容 error 消息；瞬时识别错误冷却 5 秒，永久准入拒绝后须重连，
后台补额不会自动解除旧连接断路。旧 provider 内部吞掉的异常仍需对应适配器单独治理。
手机仅以每秒最多两次更新本地音量与采集/上传帧计数，不上传诊断正文，不把录音电平当作识别文字或服务器接收确认。

Android 使用 VOICE_COMMUNICATION 和 MODE_IN_COMMUNICATION，绑定录音 session 的 AcousticEchoCanceler 在 getEnabled() 为 true 时才发送 features.aec=true。此声明打开服务端已有的开口打断路径；启用状态不保证实际声学效果，仍需真机验证。若 AEC 不可用/启用失败，App 在 TTS 播放及缓冲排空后的 200 ms 暂停上传，继续录音，随后恢复自动监听；此时保留点击 abort，不承诺语音打断。离开前台、关闭页面、登出或切换空间会释放音频与连接。

验证使用本地桥接模拟、Concentus 与服务器 libopus 双向编解码、Java/Python 单元测试；未以此冒充真机麦克风、回声测试或生产通话。API、Python 协议与 App 需配套发布，实际发布和真机验收状态见下方验收文档。

Android 0.7 使用取消代际隔离旧连接请求，静音不发送代表提交 ASR 的 `listen stop`。无 AEC 播放/打断结束后保留 200 ms 尾音保护，旧播放完成不能释放新播放的上传门控。主动提醒沿现有 TTS 协议接收，不要求前置 STT，不新增日程轮询。重连使用同一安装 UUID 与智能体，因此同一手机日程目标保持稳定。操作步骤及可执行协议探针见[手机语音链路验收](../development/mobile-voice-testing.md)。

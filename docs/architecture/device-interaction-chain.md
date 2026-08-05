---
status: supporting
owner: xiaozhi-server
last_verified: 2026-08-05
source_paths:
  - main/xiaozhi-server/core
  - main/manager-api/src/main/java/xiaozhi/modules/config
  - main/manager-web/src/components/role
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
  │     ├── 音频上行 (binary Opus → VAD → ASR → text)
  │     ├── 音频下行 (text → TTS → Opus → binary WebSocket)
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
设备 wakeup/listen 消息
  → helloHandle.py 或 listenMessageHandler.py
  → wakeupHandle.py: arm_wakeup_vad_ignore()
  → receiveAudioHandle.py: should_ignore_wakeup_audio()
  → VAD → ASR
```

- `wakeup_vad_ignore_ms` 默认 `0`，唤醒后第一包音频直接进入 VAD/ASR，不再固定丢弃 2 秒。
- `enable_greeting` 默认 `false`，避免 2.4-2.7 秒欢迎语占用首轮交互。
- 唤醒词缓存先检查开关和文本是否命中，再等待 TTS 初始化；普通文本与关闭缓存的请求不再被额外阻塞最多 3 秒。
- 设备 `listen/detect` 命中唤醒词时，无论是否开启欢迎语，聊天记录都保存设备实际上报的检测文本；命中唤醒缓存并成功播放后，同时上报实际播放的助手文本和音频，不得只写入连接内对话。
- 无 AEC 或扬声器回声会回采进麦克风的设备，可在私有配置中把 `wakeup_vad_ignore_ms` 设为 500-2000ms；设为 `2000` 可恢复旧版兼容行为。
- 忽略窗口使用 `time.monotonic()` 判断，不创建延迟任务；配置非法、负数或非有限值时安全回退为 0。

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

`context_text` 是独立的火山 `request.context` 字段，可用于直传热词或对话上下文。它与控制台热词表
ID 不可混用，provider 必须保持两者各自的请求层级，日志只记录是否启用，不记录上下文正文。

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
当前设备自己的任务。`completed` 仅表示服务端音频已经通过 WebSocket 发送完成，现有协议没有
扬声器物理播放完成 ACK。

### 4.4 智控台真实音频旁路监听

设备管理页通过 manager-api 的 `POST /device/{id}/audio-preview-ticket` 获取 60 秒短期票据，
再连接 `wss://host/xiaozhi/audio-preview/`。票据必须经过当前用户的设备 workspace 读取权限校验，
并在 WebSocket 建立后的首条 JSON 消息中提交；Token 不进入 URL 或日志。

`sendAudioHandle._do_send_audio()` 只在设备 WebSocket 成功发送原始 Opus 包后，才通过有限内存队列
复制同一字节帧给预览连接。复制使用非阻塞入队：监听页变慢、断线或队列溢出只会丢弃预览积压并发送
`discontinuity`，不得等待、取消或改变设备下行发送。每台设备同时只允许一个预览连接；预览只包含
下行音频，不包含麦克风上行，也不在服务端保存录音。

预览控制帧包含 `ready / stream_start / stream_stop / discontinuity / listener_busy / error`，二进制帧为
裸 Opus 单帧。Web 端使用项目内置 `opus-decoder` 解码，并按 `stream_start` 提供的采样率播放和绘制波形。

### 4.5 流控

```text
utils/audioRateController.py  AudioRateController
  - 前 5 帧立即发送 (PRE_BUFFER_COUNT)
  - 后续帧按 60ms 精确定时发送
  - client_abort 时通过 CancelledError 取消
```

- 整轮 TTS 不设置固定播放时长上限；收到 `LAST` 后，等待预算按队列中剩余音频帧的真实播放时长动态计算，并额外保留单包发送超时与收尾容差。
- WebSocket 半开保护作用于单个音频包发送。单包超过 `audio_send_timeout_seconds` 仍未完成时，后台发送任务失败并清理队列，避免异常连接永久占用会话；正常的长语音不会因为总时长超过 20 秒被截断。

### 4.6 对话延时链路协议

聊天记录中的延时指标使用 `schema_version=2`，不兼容旧的单值 `latency_ms` 口径。
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

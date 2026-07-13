---
status: supporting
owner: xiaozhi-server
last_verified: 2026-07-13
source_paths:
  - main/xiaozhi-server/core
---

# 设备交互完整链路

本文档说明 ESP32 设备从 WebSocket 连接到音频上行/下行、IoT 描述符注册、Device MCP 工具发现、OTA 固件升级的完整链路，以及连接生命周期管理。

先读：[云端运行架构与配置链路](cloud-runtime-architecture.md)、[提示词注入工程](prompt-injection-engineering.md)。

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
  - 创建 _modules_ready threading.Event（同步模块初始化）
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

**同步保护**：后台初始化通过 `_modules_ready` Event 与消息循环同步 — audio 处理在 Event.set() 之前被阻塞。

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
- 无 AEC 或扬声器回声会回采进麦克风的设备，可在私有配置中把 `wakeup_vad_ignore_ms` 设为 500-2000ms；设为 `2000` 可恢复旧版兼容行为。
- 忽略窗口使用 `time.monotonic()` 判断，不创建延迟任务；配置非法、负数或非有限值时安全回退为 0。

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
- 静音检测：`silence_threshold_ms`(1000ms)

### 3.3 ASR 语音转文字

```text
handle/receiveAudioHandle.py  handleAudioMessage()
  - 完整音频继续进入 ASR
  - VAD 有效人声同步喂给 voiceprint provider
  - 累计到前 1 秒后立即启动一次远程识别
providers/asr/base.py  handle_voice_stop()
  - asyncio.gather(speech_to_text(), voiceprint.resolve_utterance_result())
  - 句末仅给未完成的声纹结果 300ms 等待预算
  → receiveAudioHandle.py:45-179  startToChat(conn, text)
```

声纹识别调用独立部署的 `voiceprint-api`：全模块部署以智能体 ID 作为远程 `user_id`，每个智能体最多 5 条；历史数据可保留 `user_id=default`，Python 按结构化说话人记录的作用域分组识别并选择最高分。每句话假设只有一个说话人，只截取前 1 秒有效 PCM，达到时长便提前请求，后续音频不再参与；不足 1 秒直接跳过。Token 只通过 Bearer Header 传输，默认远程总超时 5 秒、句末追加等待最多 300ms；失败或超时只降级为无身份。智能体没有绑定声纹时不初始化 provider，也不发送识别请求。

ASR 基类仍通过工作线程桥接异步 provider；provider 内部不能再使用同步 HTTP 或 `time.sleep()`，否则慢请求会阻塞共享事件循环并放大队列积压。

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

## 4. 音频下行链路（TTS）

### 4.1 TTS 触发路径

多条路径向 `conn.tts.tts_text_queue` 发送 `TTSMessageDTO`：

| 来源 | 位置 | 说明 |
|------|------|------|
| `conn.chat()` | connection.py:1050-1463 | LLM 流式 chunk → TTS |
| `SemanticCenter._speak()` | semantic_center.py:162-187 | 确定性别回复 |
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

SeedTTS2 双流 provider 在 `EVENT_SessionFinished` 后先用 `is_end=True` 冲刷不足 60ms 的 PCM 尾帧，再发布 `SentenceType.LAST`。取消、失败或异常断链时调用 `OpusEncoderUtils.reset_state()`，防止上一轮残留样本进入下一轮。真实连续 5 会话的 PCM/Opus 均可解码，修复后每轮编码缓冲均为 0。

### 4.3 流控

```text
utils/audioRateController.py  AudioRateController
  - 前 5 帧立即发送 (PRE_BUFFER_COUNT)
  - 后续帧按 60ms 精确定时发送
  - client_abort 时通过 CancelledError 取消
```

## 5. 打断机制（Barge-in）

### 5.1 两层打断

```text
receiveAudioHandle.py:107-153  startToChat() 中断路径

第1层：确定性 STOP_ALL 快速路径
  - TextNormalizer.route_key(text) → SemanticRouter.STOP_ALL 集合 O(1) 查找
  - 命中 → handleAbortMessage(conn), 完全跳过 LLM

第2层：LLM 语义中断分类器
  - SemanticInterruptionClassifier.classify()  → conn.llm.response_no_stream()
  - 超时 1.5 秒 (configurable)
  - 3 类决策：continue / interrupt / new_turn
  - LLM 失败 → 回退到 new_turn（保守：中断并处理新输入）
```

### 5.2 Abort 处理

```text
handle/abortHandle.py:9-38  handleAbortMessage()
  - conn.client_abort = True (加锁)
  - increment abort_generation
  - 清空 TTS/ASR 队列
  - 重置 audio 状态
  - 发送 stop TTS 消息（try/except 保护）
```

## 6. IoT 描述符

### 6.1 设备上报

```text
设备发送: {"type": "iot", "descriptors": [{"name": "light", "properties": {...}, "methods": [...]}]}
  → textHandler/iotMessageHandler.py:19-22  分发
  → providers/tools/device_iot/iot_handler.py:63-120  handleIotDescriptors()
```

### 6.2 服务端门控

```text
iot_handler.py:36-61  SERVER_GATED_IOT_DEVICES
  - "hass" → Home Assistant（需 config.plugins.home_assistant 或 hass_get_state）
  - 不在映射中的设备名默认放行（向后兼容）
  - 门控失败 → 静默丢弃（记录 info 日志）
```

### 6.3 工具注册

每个 IoT 描述符被转换为 `IotDescriptor` 对象，通过 `DeviceIoTExecutor` 注册为 LLM 工具。工具名格式：
- 查询：`get_{devicename}_{property}`
- 控制：`{devicename}_{method}`

**注意**：工具名解析存储了 `(device_name, property/method)` 到 `_tool_device_map` 的映射，不依赖 `split("_")` 分片。

## 7. Device MCP

### 7.1 MCP 能力声明

```text
helloHandle.py:54-66
  hello 消息 features.mcp == true
  → 创建 MCPClient 实例
  → send_mcp_initialize_message(conn)
```

### 7.2 MCP 握手

```text
mcp_handler.py:253-288  send_mcp_initialize_message()
  1. initialize (id=1, protocolVersion: 2024-11-05)
  2. 设备回复 → tools/list (id=2)
  3. 设备回复工具列表 → name_mapping 清理 → MCPClient.set_ready(True)
```

### 7.3 清理规则

```text
util.py:573-576  sanitize_tool_name()
  点号 → 下划线：self.audio.speaker.set_volume → self_audio_speaker_set_volume
```

### 7.4 工具调用

```text
DeviceMCPExecutor.execute() → mcp_handler.py:317-426  call_mcp_tool()
  - 控制工具 (FAST_RESPONSE_CONTROL_TOOLS) → 3s 超时
  - 其他工具 → 10s 超时
  - JSON-RPC 2.0 tools/call → 等待 Future → 返回结果
```

## 8. OTA 固件升级

### 8.1 版本检查

```text
ota_handler.py:143-353  OTAHandler.handle_post()
  - 读取 device-id, client-id, device-model, firmware-version 请求头
  - 扫描 data/bin/*.bin 固件文件（按 {model}_{version}.bin 格式）
  - 版本比较（数值元组，支持不同长度）
  - 返回：server_time, firmware.version, firmware.url
```

### 8.2 固件下载

```text
ota_handler.py:372-415  OTAHandler.handle_download()
  - 文件名安全化：regex ^[A-Za-z0-9\.\-_]+\.bin$
  - 路径遍历防护：os.path.realpath 比较
  - 返回 web.FileResponse 流式下载
```

### 8.3 URL 构建

OTA 下载 URL 使用 `urllib.parse.urljoin` 从 `server.vision_url` 的基础 URL 构建，不再依赖字符串替换。

## 9. 连接生命周期

### 9.1 注册

```text
device_runtime_registry.py:59-81  register_connection()
  - 全局字典：device-id + client-id → ConnectionHandler
  - MQTT 网关：分割 @@@ 组 ID
  - 重连时覆盖旧条目（记录 warning）
  - 注销时仅删除当前仍指向该连接对象的键；旧连接晚关闭不能删除新连接映射
```

### 9.2 超时检测

```text
connection.py:1855-1884  _timeout_checker()
  - 每 10 秒检查 last_activity_time（毫秒）
  - 超时 (> timeout_ms) → close()
```

### 9.3 关闭流程

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

### 9.4 保存与关闭

```text
connection.py:325-360  _save_and_close()
  1. 生成聊天标题（非守护线程）
  2. 保存记忆（非守护线程）
  3. PowerMem 会话处理（非守护线程）
  4. 所有线程 join(timeout=10)
  5. await self.close(ws)
```

## 10. 关键文件

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

## 11. 已知修复（2026-07）

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
| 18 | 句末上传完整语音做声纹会增加长尾延迟 | 仅累计前 1 秒有效人声并提前异步识别，短句跳过，句末最多等待 300ms |

## 12. 排查方法

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

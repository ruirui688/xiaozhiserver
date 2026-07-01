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
providers/asr/base.py:84-180  handle_voice_stop()
  - Opus → PCM → 合并 → 可选 WAV 转换
  - asyncio.gather(speech_to_text(), voiceprint.identify_speaker())  并行
  → receiveAudioHandle.py:45-179  startToChat(conn, text)
```

**已知瓶颈**：`future.result()` 阻塞 ASR 线程 — 一个慢 chunk 阻塞所有后续。后续可改为 async 原生 ASR provider。

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
```

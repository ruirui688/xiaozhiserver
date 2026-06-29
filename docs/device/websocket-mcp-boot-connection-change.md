# 开机后常驻 WebSocket 与 MCP 可见性改动说明

同步说明：

- 来源：`D:\Codex_ws\Robot\xiaozhi\xiaozhi-esp32-cam\docs\websocket_mcp_boot_connection_change_zh.md`
- 同步到服务端项目时间：2026-06-16
- 用途：作为服务端 MCP 可见性问题排查记录的设备端配套改动说明。

## 1. 背景

本次改动的目标是解决这样一个问题：

- 设备开机后已经成功请求 OTA 接口，并从服务端拿到了 WebSocket 地址和 token。
- 但服务端在设备空闲、未触发唤醒词或语音交互前，看不到设备连接 `xiaozhi-server:8000` 的 WebSocket 日志。
- 因此服务端也无法继续看到后续的 MCP 握手过程，包括：
  - WebSocket 认证校验
  - 设备连接注册
  - 设备发送 `hello(features.mcp=true)`
  - 服务端发送 MCP `initialize`
  - 设备返回 `initialize result`
  - 服务端发送 `tools/list`
  - 设备返回工具列表

期望行为是：

- 设备启动并完成 OTA 配置后，不需要用户先触发语音交互，就应主动建立 WebSocket 连接。
- WebSocket 连接建立后，设备立即发送 `hello`，并在 `features` 中声明 `mcp=true`。
- 这样服务端在设备空闲时就能完成 MCP 初始化与工具发现。

## 2. 改动前的行为

改动前，设备端的 WebSocket 逻辑是“按需连接”：

- 在 `Application::ActivationTask()` 中，设备会执行 OTA 检查并调用 `InitializeProtocol()`。
- 如果 OTA 返回的是 WebSocket 配置，则会创建 `WebsocketProtocol` 实例。
- 但 `WebsocketProtocol::Start()` 只是直接返回 `true`，不会主动发起连接。
- 真正的 `Connect()` 只会在 `OpenAudioChannel()` 中发生。
- `OpenAudioChannel()` 只有在以下交互路径里才会被调用：
  - 用户按键开始说话
  - UI 主动开始监听
  - 唤醒词检测触发

这意味着：

- OTA 成功不等于 WebSocket 已建立。
- 服务端如果只观察开机后的空闲设备，会看不到任何 `8000` 端口的连接日志。
- 设备的 MCP 能力虽然已经实现，但只有在语音会话建立后才会被服务端发现。

## 3. 代码定位与问题判断

本次问题排查中，关键代码位置如下（位于 xiaozhi-esp32-cam 固件仓库）：

- `main/application.cc`
- `main/protocols/websocket_protocol.cc`
- `main/protocols/websocket_protocol.h`
- `main/ota.cc`
- `main/mcp_server.cc`

结论如下：

1. OTA 请求头与 WebSocket 握手头的 `Device-Id` / `Client-Id` 来源一致。
2. WebSocket 握手时本来就会带：
   - `Authorization: Bearer <token>`
   - `Protocol-Version`
   - `Device-Id`
   - `Client-Id`
3. 设备建立 WebSocket 后，本来就会立刻发送 `hello`。
4. `hello` 中本来就会带 `features.mcp=true`。
5. 设备端原本已经支持响应 MCP `initialize` 和 `tools/list`。
6. 真正的问题不在 MCP 能力缺失，而在于“开机空闲阶段没有主动建立 WebSocket”。

## 4. 本次改动内容

本次改动围绕两个核心目标展开：

1. 开机后主动建立并尽量保持 WebSocket 常驻连接。
2. 将“底层控制连接”和“语音会话状态”解耦，避免保持在线后破坏原有语音流程。

### 4.1 WebSocket 在启动阶段主动连接

修改位置：

- `main/protocols/websocket_protocol.cc`

改动前：

- `WebsocketProtocol::Start()` 不做任何连接动作。

改动后：

- `WebsocketProtocol::Start()` 会直接调用新的 `ConnectWebSocket(false)`。
- 这意味着设备在 OTA 配置完成、协议初始化完成后，会立即尝试连接 OTA 下发的 WebSocket 地址。

### 4.2 增加 WebSocket 自动重连能力

修改位置：

- `main/protocols/websocket_protocol.h`
- `main/protocols/websocket_protocol.cc`
- `main/protocols/websocket_protocol.cc`

新增内容：

- 重连定时器 `reconnect_timer_`
- 活跃标记 `alive_`
- 内部连接函数 `ConnectWebSocket(bool report_error)`
- 自动重连调度函数 `ScheduleReconnect()`

行为变化：

- 如果 WebSocket 在空闲状态或运行中断开，设备会在 10 秒后再次尝试连接。
- 这样即使开机后首次连接失败，设备也会继续尝试恢复管理连接。

### 4.3 将控制连接与音频会话解耦

修改位置：

- `main/protocols/websocket_protocol.h`
- `main/protocols/websocket_protocol.cc`
- `main/protocols/websocket_protocol.cc`
- `main/protocols/websocket_protocol.cc`

新增状态：

- `hello_completed_`：表示底层 WebSocket 握手与 server hello 已完成。
- `audio_channel_opened_`：表示当前是否处于语音会话状态。
- `connecting_`：表示当前是否正在发起连接，避免重复重入。

关键变化：

- 改动前，`CloseAudioChannel()` 会直接释放底层 WebSocket 对象，相当于断开整个连接。
- 改动后，`CloseAudioChannel()` 只结束“语音会话状态”，不主动关闭底层 WebSocket。
- 底层 WebSocket 可以继续保留，供 MCP 常驻使用。
- 只有在真正网络断开或服务端断开时，才会触发底层连接关闭和重连。

### 4.4 保留进入语音会话时的原始行为

修改位置：

- `main/protocols/websocket_protocol.cc`

行为说明：

- 当用户开始语音交互时，`OpenAudioChannel()` 会先检查：
  - 如果底层 WebSocket 已在线，则直接进入音频会话，并触发 `OnAudioChannelOpened()`。
  - 如果底层 WebSocket 尚未在线，则会先补建连接，再进入音频会话。

这样可以兼容两种场景：

- 开机常驻连接已经在线，用户说话时无需再次握手。
- 开机连接失败但后续用户触发交互时，仍可即时重试连接。

### 4.5 空闲状态恢复低功耗

修改位置：

- `main/application.cc`

问题背景：

- 进入音频会话时，原逻辑会把板级功耗模式切到 `PERFORMANCE`。
- 如果现在保持 WebSocket 常驻，但语音会话已经结束，设备仍应尽量回到较低功耗。

改动后：

- 在设备状态切回 `kDeviceStateIdle` 时，显式恢复为 `LOW_POWER`。

### 4.6 有常驻会话时禁止休眠

修改位置：

- `main/application.cc`

原因：

- 如果设备建立了常驻 WebSocket，但休眠逻辑仍允许进入睡眠，则控制连接会被省电逻辑中断。

改动后：

- 当协议层存在有效会话标识时，`CanEnterSleepMode()` 返回 `false`。
- 这样设备在管理连接有效期间不会进入睡眠。

## 5. 改动后的启动时序

改动后的 WebSocket / MCP 启动时序如下：

1. 设备上电启动。
2. 完成网络连接。
3. 执行 OTA 检查，请求版本与运行配置。
4. OTA 返回 WebSocket 配置，包括：
   - `url`
   - `token`
   - `version`
5. `Application::InitializeProtocol()` 创建 `WebsocketProtocol`。
6. `protocol_->Start()` 立即尝试建立 WebSocket 连接。
7. WebSocket 握手时带上：
   - `Authorization`
   - `Protocol-Version`
   - `Device-Id`
   - `Client-Id`
8. WebSocket 连接成功后，设备立刻发送：

```json
{
  "type": "hello",
  "version": 1,
  "features": {
    "mcp": true
  },
  "transport": "websocket",
  "audio_params": {
    "format": "opus",
    "sample_rate": 16000,
    "channels": 1,
    "frame_duration": 60
  }
}
```

9. 服务端返回 `hello`。
10. 服务端识别到 `features.mcp=true`。
11. 服务端发送 MCP `initialize`。
12. 设备返回 `initialize result`。
13. 服务端发送 `tools/list`。
14. 设备返回本地工具列表。
15. 后续即使用户还没有说话，设备也已经在管理端可见。

## 6. 改动后的预期日志

### 6.1 设备端预期日志

设备端至少应出现：

- `Connecting to websocket server: ...`
- `Session ID: ...` 或成功收到服务端 hello 的相关日志

如果连接失败，则应出现：

- `Failed to connect to websocket server`
- 或 `Failed to receive server hello`

并在稍后继续尝试重连。

### 6.2 服务端预期日志

服务端在设备开机且 OTA 成功后，应能看到类似如下阶段：

- WebSocket 认证校验
- 运行时设备连接已注册
- 收到 hello 特性 `features={'mcp': true}`
- 发送 MCP 初始化消息
- 收到 MCP 初始化响应
- 发送 MCP 工具列表请求
- 设备 MCP 客户端准备就绪

## 7. 验证方法

建议按以下步骤验证：

1. 确认 OTA 返回内容中包含设备端真正需要的 WebSocket 配置结构：

```json
{
  "websocket": {
    "url": "ws://10.19.1.209:8000/xiaozhi/v1/",
    "token": "xxx",
    "version": 1
  }
}
```

2. 重新编译并刷写设备固件。
3. 设备开机后，不触发唤醒词，也不按键。
4. 观察设备串口日志，确认其主动发起 `Connecting to websocket server`。
5. 观察服务端日志，确认收到 WebSocket 认证和 `hello(features.mcp=true)`。
6. 确认服务端继续下发：
   - `initialize`
   - `tools/list`
7. 确认设备正确返回对应的 MCP 响应。

## 8. 已知限制与注意事项

### 8.1 当前终端未完成完整编译验证

本次代码修改后，当前工作终端无法直接执行 `idf.py build`，因为本机环境中未识别 `idf.py` 命令。

因此：

- 代码改动已完成。
- 静态逻辑已完成复核。
- 但仍建议在实际 ESP-IDF 环境中执行完整构建与真机验证。

### 8.2 OTA 返回字段名必须与设备端读取逻辑一致

设备端读取 WebSocket 配置的方式是：

- 从 OTA JSON 顶层读取 `websocket` 对象
- 再读取其中的 `url` / `token` / `version`

如果服务端返回的是其他字段名，例如：

- `websocketUrl`
- `authEnabled`
- 顶层 `token`

而没有封装在 `websocket` 对象内，则设备端不会按预期读取到该配置。

### 8.3 `Client-Id` 以设备本地 UUID 为准

设备端在 OTA 请求和 WebSocket 握手中使用的 `Client-Id` 均来自本地持久化 UUID，而不是 OTA 响应额外返回的某个独立 clientId。

因此如果服务端 token 的签名逻辑绑定了另一套 clientId，需要确保它与设备本地 UUID 一致。

## 9. 相关文件

本次改动涉及的主要文件如下：

- `main/protocols/websocket_protocol.h`
- `main/protocols/websocket_protocol.cc`
- `main/application.cc`

关联参考文件：

- `main/ota.cc`
- `main/mcp_server.cc`
- `docs/websocket_zh.md`
- `docs/mcp-protocol_zh.md`

## 10. 总结

本次改动的本质，是将设备的 WebSocket 使用方式从“仅在语音交互时临时连接”改为“开机后优先建立常驻控制连接，再在需要时进入语音会话”。

这样调整后：

- OTA 不再只是拿到配置而已。
- 设备在空闲状态就能被服务端发现。
- `hello(features.mcp=true)` 会在开机后尽快发送。
- 服务端可以在无需用户触发说话的情况下，完成 MCP 初始化和工具发现。

这使设备从“会说话时才在线”变成“开机后就是一个持续可管理、可发现、可调用 MCP 能力的终端”。

# 聊天记录图片与 MCP 工具显示问题排查

本文记录 2026-06-15 对管理端聊天记录图片显示和设备 MCP 工具显示问题的排查结论。结论基于当时本地运行日志、前端组件和后端上报链路。

## 1. 聊天记录中看不到拍照图片

### 现象

在 `manager-web` 的聊天记录弹窗中，用户发起类似“分析一下画面中的场景”的请求后，历史记录里可以看到工具调用和视觉分析文字，但看不到拍摄到的图片。

### 已确认链路

相关链路如下：

1. 设备端通过 MCP 暴露摄像头工具，例如：
   * `self.camera.take_photo`
   * `self.camera.capture`
2. `xiaozhi-server` 调用设备 MCP 工具，代码入口：
   * `xiaozhi-server/core/providers/tools/device_mcp/mcp_handler.py`
   * `xiaozhi-server/core/providers/tools/device_mcp/mcp_executor.py`
3. 工具调用结果被加入聊天记录上报队列，代码入口：
   * `xiaozhi-server/core/handle/reportHandle.py`
4. `manager-api` 保存上报内容到 `ai_agent_chat_history.content`，代码入口：
   * `manager-api/src/main/java/xiaozhi/modules/agent/service/biz/impl/AgentChatHistoryBizServiceImpl.java`
5. `manager-web` 读取聊天记录并渲染，代码入口：
   * `manager-web/src/components/ChatHistoryDialog.vue`

### 日志证据

当时日志中可见真实设备 MCP 工具已注册成功，包含摄像头工具：

```text
self.camera.take_photo
self.camera.capture
```

但工具调用返回内容没有图片字段：

```json
{
  "content": [
    {
      "type": "text",
      "text": "{\"success\":true,\"action\":\"RESPONSE\",\"response\":\"这个场景中可以看到一个室内环境...\"}"
    }
  ],
  "isError": false
}
```

另一次 `self.camera.capture` 返回为：

```json
{
  "content": [
    {
      "type": "text",
      "text": "true"
    }
  ],
  "isError": false
}
```

也就是说，当前设备工具返回的是“分析结果文字”或布尔成功标记，不包含图片 URL、图片 ID 或 base64 图片数据。

### 根因

前端无法显示图片的直接原因是：聊天历史数据源中没有可渲染的图片地址或图片内容。

当前前端即使支持 `image_url`、`imageUrl`、`url`、`src`、`data:image/...;base64,...` 等字段，也只能显示已经存在于历史记录里的图片数据。若后端保存的内容只有 `{"result":"true"}` 或分析文字，前端无法还原当时拍摄的图片。

还需要注意一个数据结构问题：`reportHandle.py` 当前将工具结果统一包装成字符串形式：

```python
result_display = f'{{"result":"{str(tool_result)}"}}'
```

这个写法会把结构化结果压成字符串。即使后续设备端返回了结构化图片字段，也需要确认这里不会破坏图片字段。

### 已落实的修复方向

要让聊天记录显示图片，需要先让 MCP 摄像头工具返回图片引用，再让历史记录保存该引用。

推荐返回结构示例：

```json
{
  "success": true,
  "action": "RESPONSE",
  "response": "这个场景中可以看到...",
  "image_url": "http://example.com/path/to/photo.jpg"
}
```

或：

```json
{
  "success": true,
  "action": "RESPONSE",
  "response": "这个场景中可以看到...",
  "image": "data:image/jpeg;base64,..."
}
```

当前已实现的关键改动：

1. `vision_handler.py` 先将文件保存到服务器本地 `data/vision`，再调用视觉模型做分析。
2. `vision_handler.py` 生成可访问的 `image_url` 和 `image_id`，并随视觉分析结果一并返回。
3. `http_server.py` 新增 `/mcp/vision/images/{filename}` 下载路由。
4. `mcp_executor.py` 在 `action` 型 JSON 结果中保留完整结构到 `ActionResponse.result`。
5. `reportHandle.py` 改为使用 `json.dumps` 序列化工具结果，避免把嵌套 JSON 拼坏。
6. `ChatHistoryDialog.vue` 仍然可以直接消费聊天历史里的 `image_url`。

后续仍需关注：

1. `image_url` 属于可访问资源，需确认是否需要更严格的访问控制或过期机制。
2. 若视觉图片历史需要长期保存，建议明确清理策略。
3. 如果设备端返回的是短期地址，后续打开聊天记录时可能失效。

## 2. 设备调试窗口看不到 MCP 工具

### 现象

在设备聊天调试窗口中，MCP 工具列表显示为空。

### 已确认情况

日志显示真实设备连接时曾返回 6 个 MCP 工具：

```text
self.get_device_status
self.audio_speaker.set_volume
self.screen.set_brightness
self.screen.set_theme
self.camera.take_photo
self.camera.capture
```

但浏览器调试窗口连接时使用的是 `manager-debug-*` 类型的 `client-id`。后续日志中可见调试连接返回：

```json
{
  "tools": []
}
```

因此，当页面显示无 MCP 工具时，需要区分两类情况：

1. 真实设备已经断开或 MCP 未注册完成。
2. 当前显示的是调试连接自身返回的空工具列表，而不是设备真实工具列表。

### 相关注意点

`device_runtime_registry.py` 已有逻辑避免将 `manager-debug-*` 调试连接注册为真实设备运行时连接。若页面仍显示空工具，应优先检查当前真实设备是否在线、MCP 是否 ready、运行时查询是否命中真实设备连接。

日志中还出现过清理异常：

```text
工具处理器清理失败: 'MCPClient' object has no attribute 'reject_all_call_results'
```

排查发现 `device_mcp` 下存在重复的 `MCPClient` 定义，`mcp_handler.py` 中的类包含 `reject_all_call_results`，但包导出的 `mcp_client.py` 中缺少该方法。该问题会影响 MCP 清理流程，建议统一 MCPClient 定义或补齐缺失方法。

### 仍建议补强的点

1. 调试窗口显示空工具时，不只显示“暂无可见工具”，应显示原因：
   * 设备未连接运行时
   * MCP 未启用
   * MCP 未 ready
   * 运行时查询失败
   * 工具列表确实为空
2. 避免将调试连接返回的空 `tools/list` 当成真实设备工具列表。
3. 修复 `MCPClient.reject_all_call_results` 缺失问题，避免清理阶段异常掩盖真实状态。

### 2026-06-16 修复记录

本次针对设备调试窗口左侧看不到设备 MCP 工具做了收敛修复：

1. `device_runtime_registry.py` 不再只用原始 `device-id` 作为唯一键，注册真实设备连接时会同时登记设备 ID、MAC、冒号/下划线格式，以及 MQTT client-id 中拆出的 MAC 别名。
2. `manager-debug-*` 调试连接仍然不会登记到真实设备运行时注册表，避免调试窗口覆盖真实设备连接。
3. `DeviceServiceImpl` 查询运行时 `/device/mcp/tools` 和 `/device/mcp/call` 时，会依次尝试设备 `macAddress` 和数据库 `device.id`，避免两边标识不一致导致误判为未连接。
4. `MCPClient` 补齐 `reject_all_call_results`，避免连接清理阶段异常影响 MCP 状态排查。
5. 新增 `tests/test_device_runtime_registry.py`，覆盖真实设备别名命中和调试连接跳过。

如果页面仍显示空工具，应先重启 `xiaozhi-server` 和 `manager-api`，再确认真实设备已经重新连接并完成 MCP 初始化。调试窗左侧事件中出现 `mcp tools/list -> 0` 时，需要结合左侧状态文案判断是未命中运行时、MCP 未 ready，还是设备确实返回空工具列表。

### 2026-06-16 追加排查记录

重启后再次出现“已连接但设备 MCP 工具不可见”。日志确认：

1. `xiaozhi-server` 只收到 `manager-debug-*` 调试窗口连接，没有收到真实 ESP32 设备连接。
2. 调试窗口自身响应了 `tools/list -> []`，因此事件里会出现 `mcp tools/list -> 0`。
3. 直接请求运行时 `/device/mcp/tools?deviceId=3c:dc:75:fe:26:c0` 返回 `设备未连接到当前运行时`。
4. `manager-api` 原先会把 `server.ota` 推导出的 `8002` manager-api 地址也当成运行时查询，后续 HTML 404 会覆盖前面从 `8003` 拿到的有效原因，最终前端只看到空工具列表。

修复：

1. `DeviceServiceImpl.getRuntimeHttpBases()` 只从 `server.websocket` 推导 `xiaozhi-server` HTTP 运行时地址，不再把 `server.ota` 当成 MCP 运行时地址。
2. 运行时返回 `success:false` 但没有 message 时，不覆盖前一次有效错误原因。
3. `device_mcp_handler.py` 对“设备未连接 / MCP 未启用 / MCP 未 ready”返回 HTTP 200 + `success:false`，方便 manager-api 稳定解析业务状态。

当前若页面显示 `设备未连接到当前运行时`，含义是：管理端和调试窗口已经连上，但真实硬件设备还没有连接到当前重启后的 `xiaozhi-server`。需要让硬件设备重新连接当前 WebSocket 地址，完成 hello + MCP 初始化后，工具列表才会出现。

### 2026-06-16 UI 状态重构记录

截图复核时发现调试弹窗左侧 UI 将三类状态混在一起展示，导致“调试连接已连接但设备未连接”“调试连接未连接但工具列表存在”等场景看起来互相矛盾：

1. 调试会话连接：管理端网页作为 `manager-debug-*` 客户端连接到 `xiaozhi-server` 的 WebSocket。
2. 真实设备运行时连接：ESP32 真实硬件是否连接到当前运行时。
3. 设备 MCP 状态：真实设备的 MCP 是否未启用、初始化中或已就绪。

本次前端重构将这三类状态拆成独立标签：

```text
调试会话：已连接 / 未连接
真实设备：已连接 / 未连接 / 运行时状态不可用
MCP：不可用 / 已连接，未启用 MCP / 初始化中 / 已就绪
```

同时收敛状态来源：

1. `DeviceChatDebugDialog.vue` 中设备运行时和 MCP 状态只由 `/device/debug/status/{deviceId}` 更新。
2. `/device/tools/list/{deviceId}` 只负责更新工具列表和工具列表错误说明，不再反向覆盖设备状态。
3. “断开”按钮只断开调试 WebSocket，不再停止设备状态和工具轮询；关闭弹窗才完整停止轮询和释放音频资源。
4. 输入框和空状态文案改为“连接调试会话后可发送文本或语音进行调试”，避免误导为真实设备连接。

还补齐了 `deviceDebug.*` 简体中文文案：

1. `zh_CN` 补齐新增状态文案。
2. 修复页面直接显示 `deviceDebug.xxx资源不存在` 的问题。

运行时工具接口也补充了成功响应字段，便于直接调用 `/device/mcp/tools` 时判断语义：

```json
{
  "runtimeConnected": true,
  "mcpReady": true,
  "status": "mcp_ready",
  "message": "设备MCP已就绪",
  "toolsCount": 5,
  "source": "runtime"
}
```

验证结果：

1. `npm run build` 通过，仅保留项目已有的大资源和 Browserslist 过期警告。
2. `python3 -m py_compile main/xiaozhi-server/core/api/device_mcp_handler.py` 通过。
3. 已检查 `DeviceChatDebugDialog.vue` 引用的 `deviceDebug.*` key 在现有所有语言文件中均存在。

### 2026-06-16 开机后需先聊天才显示设备 MCP 的修复记录

现象：真实设备开机并连接后，管理端“对话调试”里看不到设备 MCP；必须先通过真实设备聊天一次，之后调试窗口才显示 MCP 状态和工具。

根因在 `xiaozhi-server` 的连接早期消息门控：

1. 设备 WebSocket 建连后，`ConnectionHandler` 会后台拉取绑定状态和设备私有配置。
2. `_route_message` 在绑定状态未完成时会等待最多 1 秒，超时后直接丢弃消息。
3. 设备的首个 `hello` 消息携带 `features.mcp`，服务端只有处理到这个 `hello` 才会创建 `MCPClient` 并发送 MCP `initialize`。
4. 如果绑定/配置查询超过这个短窗口，首个 `hello` 或后续 MCP 初始化响应可能被丢弃，导致运行时一直没有 `mcp_client` 或没有 ready 工具列表。
5. 后面真实设备聊天时，新的消息让连接继续活跃，部分固件会重新触发握手或 MCP 流程，所以看起来像“必须先聊天一次 MCP 才出现”。

修复：

1. `ConnectionHandler._route_message()` 在绑定状态尚未完成时，先放行连接协议消息：`hello`、`mcp`、`ping`。
2. 业务消息、音频消息、IoT 控制等仍然保留原有绑定状态门控。
3. 新增 `_should_handle_before_bind_check()`，只解析 JSON 类型并限定消息类型，避免把任意文本绕过绑定检查。

修复后预期：

1. 真实设备开机连接后，只要固件在 `hello.features.mcp=true` 中声明支持 MCP，运行时会立即启动设备 MCP 初始化。
2. 管理端状态接口 `/device/debug/status/{deviceId}` 能在设备完成 `initialize -> tools/list` 后显示 `mcp_ready`。
3. 不再需要先通过真实设备聊天一次才能在“对话调试”里看到设备 MCP。

验证：

1. `python3 -m py_compile main/xiaozhi-server/core/connection.py main/xiaozhi-server/core/handle/helloHandle.py main/xiaozhi-server/core/providers/tools/device_mcp/mcp_handler.py main/xiaozhi-server/core/api/device_mcp_handler.py` 通过。

### 2026-07-02 离线设备仍应允许对话调试

现象：真实 ESP32 设备不在线时，管理端“对话调试”可以建立 `manager-debug-*` 调试 WebSocket，也能发送文本，但页面收不到助手回复；左侧同时显示“真实设备：未连接”“设备 MCP 工具为空”。

排查日志显示，调试连接发送的文本已经进入 Python 实时服务：

```text
收到listen消息：{"type":"listen","state":"detect","text":"你好"}
大模型收到用户消息: 你好
```

但后续没有出现 `LLM请求已发起` 日志，说明卡点不在前端 WebSocket，也不在真实设备在线状态，而是在调试连接进入 LLM 前的运行时准备阶段。

核心边界结论：

1. `manager-debug-*` 是管理端创建的虚拟调试会话，用真实设备 MAC 拉取该设备绑定的智能体配置。
2. 真实设备是否在线，只应该影响设备自身 MCP / IoT / 摄像头 / 音量亮度等硬件工具。
3. 文本对话、知识库、普通服务端插件、外部 MCP、智能体人设和模型配置调试，不应依赖真实 ESP32 在线。
4. `device_runtime_registry.py` 仍必须跳过 `manager-debug-*`，避免调试连接覆盖真实设备运行时连接。
5. 前端状态必须把“调试会话连接”“真实设备运行时连接”“设备 MCP 状态”分开展示，不能把“真实设备未连接”解释成整个调试不可用。

本次根因：

`Dialogue.update_system_message()` 在持有 `threading.Lock` 时调用 `self.put()`，而 `put()` 会再次获取同一把非重入锁。调试连接初始化 system prompt 时，如果当前对话还没有 system message，会在这里死锁，表现为：

```text
大模型收到用户消息
  -> 无 LLM请求已发起
  -> 页面一直收不到助手回复
```

修复：

1. `core/utils/dialogue.py` 中 `update_system_message()` 在锁内直接 append system message，不再递归调用 `put()`。
2. 新增 `tests/test_dialogue.py`，覆盖首次新增和后续替换 system message，防止死锁回归。
3. `DeviceChatDebugDialog.vue` 离线时不再持续轮询 `/device/tools/list/{deviceId}`，只保留状态轮询；待真实设备上线且 MCP 初始化/就绪后再拉取工具。
4. `zh_CN.js` 文案明确：“真实设备未连接，仅设备 MCP 工具不可用；文本、知识库和普通插件调试可继续”。

同日追加严重问题：用户在助手播报期间输入“你开心吗”后，页面不再收到任何回复，输入框变为“请先连接调试会话”。生产日志显示该输入进入了语义打断分类：

```text
收到listen消息：{"type":"listen","state":"detect","text":"你开心吗"}
语义打断大模型裁决超时(1.5s)，按新用户轮次处理
Connection error: 'coroutine' object has no attribute 'done'
```

根因是 `SemanticInterruptionClassifier.classify()` 使用 `asyncio.to_thread(...)` 后直接把返回值当作 Future，在超时分支访问 `.done()`。但 `asyncio.to_thread` 返回的是 coroutine，不是 Task/Future；超时时兜底分支反而抛出 `AttributeError`，导致 `ConnectionHandler` 关闭 WebSocket。

修复：

1. `core/semantic/interruption.py` 将 `asyncio.to_thread(...)` 包装为 `asyncio.create_task(...)`，超时后可安全判断和取消。
2. 新增 `test_llm_timeout_falls_back_to_new_turn_without_crashing`，确保裁决超时时返回 `new_turn/llm_timeout` 而不是关闭连接。
3. 补齐语义打断流程测试桩的 `_abort_lock` 和 executor Future 行为，验证 new_turn 分支会 abort 当前播报并提交新聊天。

后续修改这条链路时必须保持：

```text
真实设备离线 != 调试不可用
真实设备离线 == 设备本身 MCP/IoT/硬件能力不可用
语义打断裁决超时 != 连接错误；必须降级为 new_turn 并继续处理用户新问题
```

### 2026-06-16 MQTT 网关路径状态修复记录

截图复核时仍显示：

```text
调试会话：已连接
真实设备：未连接
MCP：不可用
调试事件：mcp initialize / mcp tools/list -> 0
```

这里的 `mcp initialize / tools/list -> 0` 来自浏览器调试会话自身。调试会话以 `manager-debug-*` 连接到 `xiaozhi-server`，服务端会向它发起 MCP 初始化；浏览器再把真实设备工具列表代理回去。因此这些事件不等于真实硬件设备已连接。

进一步检查发现 `manager-api` 的工具查询顺序有阻断问题：

1. `getDeviceTools()` 先查 `xiaozhi-server` 运行时 `/device/mcp/tools`。
2. 如果真实设备没有注册在当前 WebSocket 运行时，运行时返回“设备未连接到当前运行时”。
3. 旧逻辑会把这个未命中结果直接作为最终空工具返回。
4. 后面的 MQTT 网关 `/api/commands/{clientId}` 兜底查询永远不会执行。
5. 因此使用 MQTT 网关连接的设备，即使设备已开机在线，也可能在调试窗口显示“真实设备未连接 / MCP 不可用”。

修复：

1. `DeviceServiceImpl.getDeviceToolsFromRuntime()` 在运行时未命中时返回 `null`，允许继续走 MQTT 网关兜底。
2. 新增 `getDeviceToolsFromMqttGateway()`，把 MQTT 网关返回的工具结果标准化为：

```json
{
  "runtimeConnected": true,
  "mcpReady": true,
  "status": "mcp_ready",
  "source": "mqtt_gateway",
  "toolsCount": 3,
  "tools": ["..."]
}
```

3. `getDeviceDebugStatus()` 在 WebSocket 运行时未连接时，会继续用 MQTT 网关工具查询结果推导真实设备状态。
4. 前端调试事件文案从 `mcp initialize/tools-list` 改成 `debug mcp proxy ...`，明确这些事件属于调试会话代理，不是硬件设备连接状态。

验证：

1. `cd main/manager-api && mvn -q -DskipTests compile` 通过。

### 2026-06-16 `server.mqtt_manager_api` 配置收敛记录

复核当前机器端口发现只有 `8000/8003`、`8001`、`8002` 在监听，没有 MQTT 网关需要的 `1883`、`8884`、`8007`。同时数据库里的 `server.mqtt_manager_api` 被填成了：

```text
ws://10.19.1.209:8000/xiaozhi/v1/?from=mqtt_gateway
```

这个值是 MQTT 网关连接 `xiaozhi-server` 的聊天 WebSocket 地址，不是管理端查询网关状态的 HTTP API 地址。正确规则：

1. 未部署 MQTT 网关时，`server.mqtt_gateway`、`server.udp_gateway`、`server.mqtt_manager_api`、`server.mqtt_signature_key` 都应为 `null`。
2. 已部署 MQTT 网关时，`server.mqtt_manager_api` 才填写 `PUBLIC_IP:API_PORT`，例如 `10.19.1.209:8007`。
3. `ws://.../xiaozhi/v1/?from=mqtt_gateway` 只能放在 MQTT 网关自身的 `chat_servers` 配置里，不能放进智控台参数 `server.mqtt_manager_api`。

修复：

1. `DeviceServiceImpl` 对 `server.mqtt_manager_api` 做防御式解析：接受 `host:port`、`http://host:port`、`https://host:port`，拒绝 `ws://` 和 `wss://`。
2. 非空但无效的 `server.mqtt_manager_api` 会返回明确配置错误，不再抛 `UnknownHostException: ws` 或 500。
3. `server.mqtt_manager_api=null` 被视为“未启用 MQTT 网关”的正常状态，工具列表回到真实运行时离线语义，不再提示用户必须配置 MQTT。
4. 当前数据库已把 `server.mqtt_manager_api` 改回 `null`，并清理 `sys:params` 缓存。

验证：

1. `cd main/manager-api && mvn -q -DskipTests compile` 通过。
2. `cd main/manager-web && npm run build` 通过，仅保留项目已有的大资源和 Browserslist 过期警告。
3. `python3 -m py_compile main/xiaozhi-server/core/connection.py main/xiaozhi-server/core/api/device_mcp_handler.py main/xiaozhi-server/core/handle/helloHandle.py main/xiaozhi-server/core/providers/tools/device_mcp/mcp_handler.py` 通过。
4. 已重启 `manager-api`、`manager-web`、`xiaozhi-server`，当前 `8000/8001/8002/8003` 均在监听。

### 2026-06-16 真实设备连接与调试连接分流日志记录

复核设备 `3c:dc:75:fe:26:c0` 的现场日志：

1. `manager-api` 收到真实设备 OTA：
   `OTA请求: deviceId=3c:dc:75:fe:26:c0, clientId=61902ba6-9fae-477e-9d8e-078c10ac628a, appVersion=2.2.6`。
2. OTA 下发的 WebSocket 地址为：
   `ws://10.19.1.209:8000/xiaozhi/v1/`，认证 token 已生成。
3. `xiaozhi-server` 没有收到该真实 `clientId=61902ba6-...` 的 WebSocket 认证、hello 或 MCP 日志。
4. 后续进入 `xiaozhi-server` 的连接是浏览器调试连接：
   `client-id=manager-debug-...`、`user-agent=Chrome`、`origin=http://127.0.0.1:8001`。
5. 调试连接的 MCP `tools/list` 返回空数组，不能代表真实设备 MCP 工具为空；它只是调试会话代理连接。

为避免再次混淆，新增日志：

1. `core.device_runtime_registry`：
   - 真实设备连接注册：`运行时设备连接已注册`
   - 真实设备连接注销：`运行时设备连接已注销`
   - 调试连接跳过：`跳过运行时设备注册: 调试连接不作为真实设备`
2. `core.api.device_mcp_handler`：
   - 查询状态时输出 `deviceId` 和当前 `registeredKeys`
   - 未命中时输出 `设备MCP状态未命中运行时连接`
   - 命中时输出 `clientId`、`ready`、`toolsCount`
3. `DeviceServiceImpl`：
   - 管理端查询运行时状态/工具时输出命中或未命中的最终结果。
4. `DeviceChatDebugDialog.vue`：
   - 侧栏显示后端返回的状态细节、`clientId` 和 `source`，区分“调试会话已连接”和“真实设备已连接”。

自测结论：

1. 使用已存在设备 MAC `3c:dc:75:fe:26:c0` 模拟真实设备 WebSocket。
2. 只发送 `hello(features.mcp=true)` 和 MCP `initialize/tools-list` 响应，不发送任何 `listen` 对话消息。
3. 运行时状态成功变为：

```json
{
  "runtimeConnected": true,
  "mcpReady": true,
  "status": "mcp_ready",
  "toolsCount": 1,
  "source": "runtime"
}
```

因此服务端逻辑已经不依赖“先聊天一次”。如果真实设备开机后仍看不到 MCP，下一步只需要看日志里是否出现真实设备的：

```text
WebSocket认证校验: device-id=3c:dc:75:fe:26:c0, client-id=<真实clientId>
运行时设备连接已注册: device-id=3c:dc:75:fe:26:c0
收到hello特性: ... features={'mcp': True}
设备MCP客户端准备就绪: tools=<N>
```

如果只有 OTA 日志而没有上述 WebSocket 日志，问题不在 MCP 解析，而在真实设备没有连到 `xiaozhi-server:8000`，常见原因是设备当前固件只在唤醒/对话时建立 WebSocket，或 `10.19.1.209:8000` 对设备网络不可达。

验证：

1. `python3 -m py_compile main/xiaozhi-server/core/device_runtime_registry.py main/xiaozhi-server/core/api/device_mcp_handler.py main/xiaozhi-server/core/connection.py main/xiaozhi-server/core/handle/helloHandle.py main/xiaozhi-server/core/providers/tools/device_mcp/mcp_handler.py main/xiaozhi-server/core/websocket_server.py` 通过。
2. `cd main/manager-api && mvn -q -DskipTests compile` 通过。
3. `cd main/manager-web && npm run build` 通过，仅保留项目已有的大资源和 Browserslist 过期警告。
4. 已重启服务，`api/web/server/mobile` 均运行；当前真实设备状态接口返回 `runtimeConnected=false`，表示没有真实设备 WebSocket 保持在线。

### 2026-06-16 设备侧修复后的最终闭环记录

设备侧同步修改并重新接入后，服务端现场状态已经恢复正常。当前结论：

1. 真实设备 `3c:dc:75:fe:26:c0` 已连接到 `xiaozhi-server` WebSocket 运行时。
2. 设备 `hello.features.mcp=true` 已被服务端收到。
3. 服务端已完成 MCP `initialize -> tools/list`。
4. 运行时注册表已命中真实设备连接，而不是 `manager-debug-*` 调试连接。
5. 管理端状态接口已稳定返回 `mcp_ready`，工具数量为 6。

状态接口验证：

```bash
curl -s -H 'Authorization: Bearer 394fb376-911b-407f-a371-83825b64fdda' \
  'http://127.0.0.1:8003/device/mcp/status?deviceId=3c:dc:75:fe:26:c0'
```

返回：

```json
{
  "success": true,
  "data": {
    "deviceId": "3c:dc:75:fe:26:c0",
    "clientId": "61902ba6-9fae-477e-9d8e-078c10ac628a",
    "runtimeConnected": true,
    "mcpReady": true,
    "status": "mcp_ready",
    "message": "设备MCP已就绪",
    "toolsCount": 6,
    "source": "runtime"
  }
}
```

工具接口验证：

```bash
curl -s -H 'Authorization: Bearer 394fb376-911b-407f-a371-83825b64fdda' \
  'http://127.0.0.1:8003/device/mcp/tools?deviceId=3c:dc:75:fe:26:c0'
```

返回工具共 6 个：

```text
self.get_device_status
self.audio_speaker.set_volume
self.screen.set_brightness
self.screen.set_theme
self.camera.take_photo
self.screen.display_image
```

关键握手日志：

```text
16:53:22 WebSocket认证校验: device-id=3c:dc:75:fe:26:c0, client-id=61902ba6-9fae-477e-9d8e-078c10ac628a, tokenPresent=True
16:53:22 运行时设备连接已注册: device-id=3c:dc:75:fe:26:c0, client-id=61902ba6-9fae-477e-9d8e-078c10ac628a
16:53:22 收到hello特性: device-id=3c:dc:75:fe:26:c0, client-id=61902ba6-9fae-477e-9d8e-078c10ac628a, features={'mcp': True}
16:53:22 客户端声明支持MCP，创建MCPClient并启动初始化
16:53:22 发送MCP初始化消息: device-id=3c:dc:75:fe:26:c0, client-id=61902ba6-9fae-477e-9d8e-078c10ac628a
16:53:22 收到MCP初始化响应: device-id=3c:dc:75:fe:26:c0, client-id=61902ba6-9fae-477e-9d8e-078c10ac628a
16:53:23 发送MCP工具列表请求: device-id=3c:dc:75:fe:26:c0, client-id=61902ba6-9fae-477e-9d8e-078c10ac628a
16:53:24 客户端设备支持的工具数量: 6
16:53:24 设备MCP客户端准备就绪: tools=6
16:53:24 设备MCP状态命中: deviceId=3c:dc:75:fe:26:c0, clientId=61902ba6-9fae-477e-9d8e-078c10ac628a, ready=True, toolsCount=6
16:53:24 查询设备MCP工具成功: deviceId=3c:dc:75:fe:26:c0, clientId=61902ba6-9fae-477e-9d8e-078c10ac628a, toolsCount=6
```

这组日志说明：MCP 工具不是通过 OTA、manager-api 扫描或数据库保存得到的，而是设备接入 `xiaozhi-server:8000` 后，通过同一条真实 WebSocket 连接实时协商出来的。服务端只在当前连接对象里保存：

```python
conn.mcp_client.tools
```

因此工具列表是运行时内存状态，不是通用持久表数据。真实设备断开后，该连接对象被清理，工具也会从运行时注册表里消失；设备重新连接后，需要重新完成 `hello -> initialize -> tools/list`。

本次问题的最终责任边界：

1. 服务端已修复早期消息门控，允许 `hello`、`mcp`、`ping` 在绑定状态完成前通过，避免首个 MCP 握手被丢弃。
2. 服务端已修复运行时查询、调试连接隔离、状态文案和日志可观测性。
3. 设备侧需要保证开机后主动建立并保持 `ws://10.19.1.209:8000/xiaozhi/v1/` 真实 WebSocket 连接。
4. 设备侧需要在开机连接后的 `hello` 中携带 `features.mcp=true`。
5. 设备侧需要响应服务端发出的 MCP `initialize` 和 `tools/list`，不能等到真实语音对话才创建 MCP 能力。

后续如果再次出现“必须先真实对话一次，调试界面才看到 MCP”，按下面顺序判定：

1. 有 OTA 日志但没有 `WebSocket认证校验`：设备只请求了 OTA，没有连接运行时 WebSocket。
2. 有 `WebSocket认证校验` 但没有 `运行时设备连接已注册`：认证、header 或注册逻辑异常。
3. 有注册但没有 `收到hello特性`：设备连接后没有发送 hello。
4. `features` 中没有 `mcp: True`：设备没有声明 MCP 支持。
5. 有 `发送MCP初始化消息` 但没有 `收到MCP初始化响应`：设备 MCP initialize 处理异常。
6. 有 `发送MCP工具列表请求` 但没有 `设备MCP客户端准备就绪`：设备 tools/list 处理异常或返回格式异常。
7. 已有 `设备MCP客户端准备就绪: tools=N` 但 UI 不显示：再查 `manager-api` 的 `/device/debug/status/{deviceId}` 和 `/device/tools/list/{deviceId}` 转发逻辑。

当前闭环状态：服务端和设备侧 MCP 接入链路已打通，调试窗口应显示真实设备 MCP 已就绪和 6 个工具。

设备端配套改动说明已同步到本项目：

- [websocket-mcp-boot-connection-change.md](../device/websocket-mcp-boot-connection-change.md)

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

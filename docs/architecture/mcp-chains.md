---
status: supporting
owner: tools
last_verified: 2026-07-28
source_paths:
  - main/xiaozhi-server/core/providers/tools
---

# MCP 三类链路架构

本文档说明 xiaozhi-esp32-server 中三条 MCP (Model Context Protocol) 工具链路的工具发现、注册和调用完整流程。

先读：[项目完整架构](project-architecture.md)、[运行时链路](runtime-flows.md)、[提示词注入工程](prompt-injection-engineering.md)。

## 1. 三类 MCP 总览

```text
┌─────────────────────────────────────────────────────────────┐
│                    UnifiedToolHandler                       │
│                   get_functions()                           │
│                         │                                   │
│         ┌───────────────┼───────────────┐                  │
│         │               │               │                   │
│    ServerMCP       DeviceMCP      MCPEndpoint               │
│    Executor        Executor       Executor                  │
│         │               │               │                   │
│    ┌────┴────┐    ┌────┴────┐    ┌────┴────┐              │
│    │ Local   │    │ ESP32   │    │External │              │
│    │ MCP     │    │ Device  │    │ MCP     │              │
│    │ Server  │    │         │    │Endpoint │              │
│    └─────────┘    └─────────┘    └─────────┘              │
└─────────────────────────────────────────────────────────────┘
```

## 2. 统一工具处理器

### 2.1 工具聚合

```text
unified_tool_handler.py:get_functions()
  → unified_tool_manager.py:get_function_descriptions()
    → get_all_tools()
      → 遍历 executors（注册顺序）：

注册顺序 (优先级 高→低):
  1. SERVER_PLUGIN    — 服务端插件（最高优先级，先注册先保留）
  2. SERVER_MCP       — 服务器侧 MCP
  3. DEVICE_IOT       — 设备 IoT 描述符
  4. DEVICE_MCP       — 设备端 MCP
  5. MCP_ENDPOINT     — 外部 MCP 接入点

工具名冲突：先注册者保留，后注册者跳过，记录 warning 含双方 ToolType
```

### 2.2 工具执行路由

```text
ToolManager.execute_tool(name, arguments)
  → get_tool_type(name) → 获取对应 ToolType
  → executors[tool_type].execute(conn, name, arguments)
  → 返回 ActionResponse
```

## 3. 外部 MCP Endpoint

### 3.1 配置

```text
后台配置：
  - mcp_endpoint: WebSocket URL（以 ws:// 或 wss:// 开头）
  - 占位符值（"你的MCP"、"null"）视为未配置
```

### 3.2 连接与工具发现

```text
mcp_endpoint_handler.py:connect_mcp_endpoint()
  1. 验证 URL 非空/占位符
  2. websockets.connect(mcp_endpoint_url)
  3. 创建 MCPEndpointClient → set_websocket()
  4. 启动 _message_listener 后台任务
  5. MCP 握手:
     - initialize (id=1, protocolVersion: "2024-11-05")
     - notifications/initialized
     - tools/list (id=2)
  6. 工具分页: nextCursor → 继续 tools/list
  7. 完成后: mcp_client.set_ready(True)
```

### 3.3 断连重连

```text
_message_listener() 循环:
  - 正常 → 处理消息
  - 断连 → 记录 warning, 等待 5s, 重试连接（最多 3 次）
  - 3 次失败 → permanent_failure = True, 记录 error
```

### 3.4 工具调用

```text
MCPEndpointExecutor.execute()
  → call_mcp_endpoint_tool(conn, client, tool_name, args, timeout=30s)
    → JSON-RPC 2.0 tools/call
    → asyncio.wait_for(future, timeout)
    → 解析 result.content[0].text
```

## 4. 服务器侧 MCP

### 4.1 配置

```text
mcp_server_settings.json:
  {
    "mcpServers": {
      "filesystem": {"command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path"]},
      "playwright": {"command": "npx", "args": ["-y", "@executeautomation/playwright-mcp-server"]},
      "sse-server": {"url": "http://localhost:8080/sse", "transport": "sse", "headers": {...}}
    }
  }
```

支持三种传输协议：
- **stdio**: `command` + `args`（子进程）
- **SSE**: `url` + `transport: "sse"`
- **Streamable HTTP**: `url` + `transport: "streamable-http"`

### 4.2 连接生命周期

```text
ServerMCPManager.initialize_servers()
  → asyncio.gather(*[ _init_server(name, config) ])
    → ServerMCPClient.initialize()
      → _worker() 后台任务
        → AsyncExitStack:
          1. 建立传输 (stdio/SSE/HTTP)
          2. mcp.ClientSession(streams)
          3. session.initialize()          MCP 握手
          4. session.list_tools()          工具枚举
          5. 存储工具到 tools_dict
          6. 等待 _shutdown_evt            保持连接
```

### 4.3 工具执行与重试

```text
ServerMCPManager.execute_tool(name, args)
  → 搜索 clients 中包含该工具的客户端
  → client.call_tool()  最多 3 次重试
    - 失败 → cleanup() → 重建 ServerMCPClient → 重新初始化 → 等待 2s
```

### 4.4 mcp_ 命名空间

```text
ServerMCPExecutor:
  - execute(): 去除 "mcp_" 前缀 → 调用 mcp_manager
  - get_tools(): 注册原始名 + "mcp_" 前缀别名
```

## 5. 设备侧 MCP

### 5.1 MCP 能力声明

```text
设备发送 hello: {"type": "hello", "features": {"mcp": true}}
  → helloHandle.py: create MCPClient + send_mcp_initialize_message()
```

### 5.2 MCP 握手

```text
mcp_handler.py:
  1. initialize (id=1) → 设备回复 server_info
  2. tools/list (id=2) → 设备回复 tools[]
  3. 工具名清理: sanitize_tool_name()  → 点号转下划线
  4. 工具描述中的名称引用 → 正则 \b 单词边界替换
  5. MCPClient.set_ready(True)
```

### 5.3 工具调用

```text
DeviceMCPExecutor.execute():
  - FAST_RESPONSE_CONTROL_TOOLS:
      volume/brightness/theme/update  → device_mcp_control_timeout (3s)
  - 其他工具 → device_mcp_tool_call_timeout (10s)
  - JSON-RPC 2.0 tools/call → 等待设备回复
  - 控制工具结果包含 transport_result_received 标志
```

### 5.4 模型决定的终止型工具

设备工具可以在 `tools/list` 中声明：

```json
{
  "inputSchema": {
    "type": "object",
    "properties": {"request_id": {"type": "string"}},
    "required": ["request_id"]
  },
  "annotations": {
    "turnBehavior": "terminate_silently",
    "serverGenerated": ["request_id"]
  }
}
```

该标记描述工具执行后的协议行为，不描述任何自然语言短语。语言意图仍由主大模型结合完整输入和上下文
判断；服务器不维护停止词表、正则或整句匹配。

`serverGenerated` 是 Device MCP 的显式参数所有权注解，目前只支持 `request_id`。云端发现该注解后，
在连接内保存 `server_generated` 元数据，从模型可见 Schema 中移除 `request_id`；实际发送 `tools/call`
时使用本次 JSON-RPC ID 生成 `mcp-<id>`，并把参数重建为 `{"request_id":"mcp-<id>"}`。因此该工具会
忽略模型幻觉出的 `text`、`content` 或自造 `request_id`。未带注解的旧设备仍按原 Schema 和模型参数调用；
该行为不扩展到其他 MCP 类型、普通设备工具或所有终止型工具。

模型选择终止型工具后，`connection.py` 只调度该工具，丢弃同轮 `direct_answer` 和其他工具调用。无论设备
返回成功还是失败，云端都复用 `handleAbortMessage()` 递增 `abort_generation`、清空 TTS 队列并发送
`tts/stop`，随后静默返回，不再递归请求 LLM；日志分别记录设备工具成功/失败和云端本轮已静默终止。
emotion 任务携带 sentence 与 abort generation，旧结果不能越过终止边界。设备若已先发 abort，服务器识别
generation 已变化，不会重复递增。

`direct_answer` 在完整模型输出确定前不进入 TTS，避免一个响应同时包含终止工具时抢先播出确认文字。

### 5.5 REST API

```text
GET  /device/mcp/status  → 设备 MCP 连接状态
GET  /device/mcp/tools   → 已注册工具列表
POST /device/mcp/call    → 远程调用工具

认证：Authorization: Bearer <server.auth_key 或 manager-api.secret>
设计：使用原始共享密钥（非 JWT），因为调用来自可信内网。
```

## 6. 共享基础设施

### 6.1 BaseMCPClient

```text
base/mcp_client_base.py  BaseMCPClient
  - tools: dict                工具存储 (sanitized_name → tool_info)
  - name_mapping: dict         原始名称映射
  - ready: bool                就绪状态
  - call_results: dict         待处理调用 (id → Future)
  - lock: asyncio.Lock         并发保护
  - add_tool() / get_available_tools()
  - is_ready() / set_ready()
  - register_call_result_future() / resolve_call_result()
  - reject_all_call_results()
  - tool_count 属性

子类:
  - MCPClient (device_mcp)        ← 9 行，仅继承
  - MCPEndpointClient (mcp_endpoint) ← 17 行，新增 close()
```

### 6.2 工具名清理

```text
util.py:sanitize_tool_name(name)
  → re.sub(r"[^a-zA-Z0-9_\-一-鿿]", "_", name)
  → 点号 → 下划线: self.audio.speaker.set_volume → self_audio_speaker_set_volume
```

## 7. 清理流程

```text
UnifiedToolHandler.cleanup():
  1. ServerMCPExecutor.cleanup()  → ServerMCPManager.cleanup_all()
  2. MCPEndpointClient.close()     → WebSocket 关闭
  3. MCPClient.reject_all_call_results()  → ConnectionError 拒绝 pending futures

注意：connection.py 不再重复调用 reject_all_call_results()（由 func_handler.cleanup() 统一处理）
```

## 8. 关键文件

| 文件 | 行数 | 职责 |
|------|------|------|
| `core/providers/tools/unified_tool_handler.py` | ~280 | 工具聚合器 |
| `core/providers/tools/unified_tool_manager.py` | ~80 | 工具注册 + 冲突解决 |
| `core/providers/tools/base/tool_types.py` | ~30 | ToolType/ToolDefinition 数据类型 |
| `core/providers/tools/base/tool_executor.py` | ~30 | 基类 |
| `core/providers/tools/base/mcp_client_base.py` | 122 | MCP 客户端共享基类 |
| **外部 MCP Endpoint** | | |
| `core/providers/tools/mcp_endpoint/mcp_endpoint_executor.py` | ~50 | 执行器 |
| `core/providers/tools/mcp_endpoint/mcp_endpoint_client.py` | ~17 | 客户端（继承 BaseMCPClient） |
| `core/providers/tools/mcp_endpoint/mcp_endpoint_handler.py` | ~95 | 连接 + 消息处理 + 重连 |
| **服务器侧 MCP** | | |
| `core/providers/tools/server_mcp/mcp_executor.py` | ~70 | 执行器（mcp_ 命名空间） |
| `core/providers/tools/server_mcp/mcp_client.py` | ~160 | 客户端（stdio/SSE/HTTP） |
| `core/providers/tools/server_mcp/mcp_manager.py` | ~200 | 多服务器管理 |
| `mcp_server_settings.json` | — | 本地 MCP 服务器配置 |
| **设备侧 MCP** | | |
| `core/providers/tools/device_mcp/mcp_executor.py` | ~60 | 执行器（快速响应控制工具） |
| `core/providers/tools/device_mcp/mcp_client.py` | ~10 | 客户端（继承 BaseMCPClient） |
| `core/providers/tools/device_mcp/mcp_handler.py` | ~430 | 握手 + 工具调用 |
| `core/handle/textHandler/mcpMessageHandler.py` | ~30 | 消息分发 |
| **工具管理** | | |
| `core/providers/tools/server_plugins/plugin_executor.py` | ~400 | 服务端插件执行器 |
| `core/providers/tools/device_iot/iot_executor.py` | ~80 | IoT 工具执行器 |
| `core/providers/tools/device_iot/iot_handler.py` | ~150 | IoT 描述符处理 |

## 9. 已知修复（2026-07）

| # | 问题 | 修复 |
|---|------|------|
| 1 | MCP 端点断连后无重连 | 自动重连（3 次 / 5s 间隔） |
| 2 | MCPEndpointClient 缺 reject_all_call_results | 基类统一提供 |
| 3 | device_mcp 与 mcp_endpoint 客户端代码重复 | 提取 BaseMCPClient 基类 |
| 4 | 工具名冲突静默覆盖 | 先注册者保留 + 详细 warning |
| 5 | mcp_ 前缀不一致 | get_tools() 注册别名 |
| 6 | 工具描述替换无单词边界 | 正则 \b 替换 |
| 7 | HTTP API 认证机制文档缺失 | 添加设计说明注释 |

## 10. 排查方法

```bash
# 查看当前连接支持的工具
scripts/dev logs server -f | grep "当前支持的函数列表"

# 查看 MCP 端点连接
scripts/dev logs server -f | grep -E "mcp_endpoint|MCP 接入点"

# 查看服务器侧 MCP
scripts/dev logs server -f | grep -E "ServerMCP|mcp_manager"

# 查看设备 MCP 握手
scripts/dev logs server -f | grep -E "send_mcp|tools/list|mcp_client"

# 查看工具执行
rg -n "execute_tool|handle_llm_function_call" main/xiaozhi-server/core
```

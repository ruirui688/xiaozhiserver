# 可视化 MCP HTTP 工具

本文说明当前项目新增的“可视化 MCP HTTP 工具”链路。它允许在智控台为某个智能体配置 HTTP 工具，再由 Python 桥接脚本把这些 HTTP 调用注册成 MCP tools，供 `xiaozhi-server` 的工具系统调用。

## 1. 能力边界

该能力属于服务器侧 HTTP 工具桥接：

```text
manager-web 配置工具
  -> manager-api 保存 JSON
  -> xiaozhi-server/scripts/no_code_mcp_tools.py 拉取配置
  -> MCP Endpoint 注册 tools/list 和 tools/call
  -> xiaozhi-server 运行时发现并调用工具
```

它不是：

- 设备端 MCP 工具。
- 外部 MCP Endpoint 服务本身。
- Python 内置 `plugins_func/functions/` 插件。

它适合接入简单 HTTP API，例如查询业务系统、调用内部只读接口、包装一个固定 POST 请求。不适合接入需要复杂鉴权、文件上传、长连接、数据库直连或高风险写操作的系统。

## 2. 关键文件

```text
main/manager-web/src/components/FunctionDialog.vue
main/manager-web/src/apis/module/agent.js

main/manager-api/src/main/java/xiaozhi/modules/agent/controller/AgentMcpAccessPointController.java
main/manager-api/src/main/java/xiaozhi/modules/config/controller/ConfigController.java
main/manager-api/src/main/java/xiaozhi/modules/agent/dto/McpNoCodeToolDTO.java
main/manager-api/src/main/java/xiaozhi/modules/agent/entity/AgentMcpNoCodeToolEntity.java
main/manager-api/src/main/java/xiaozhi/modules/agent/service/impl/AgentMcpNoCodeToolServiceImpl.java
main/manager-api/src/main/resources/db/changelog/202606161720.sql

main/xiaozhi-server/scripts/no_code_mcp_tools.py
```

## 3. 数据库

迁移文件：

```text
main/manager-api/src/main/resources/db/changelog/202606161720.sql
```

表：

```text
ai_agent_mcp_no_code_tools
```

约定：

- 每个 `agent_id` 保存一份工具列表 JSON。
- 删除智能体时同步删除对应工具配置。
- 新迁移必须在 `db.changelog-master.yaml` 中登记。

## 4. 管理端接口

智控台管理接口：

```text
GET /agent/mcp/no-code-tools/{agentId}
PUT /agent/mcp/no-code-tools/{agentId}
```

要求：

- 用户必须有 `sys:role:normal` 权限。
- 后端会校验当前用户是否有该智能体权限。

服务端配置接口：

```text
GET /config/mcp/no-code-tools/{agentId}
```

该接口由 `server.secret` 保护，只给服务端桥接脚本读取，不作为普通用户接口使用。

## 5. 工具配置字段

核心字段：

```text
name              工具名称，暴露给大模型调用
description       工具描述
enabled           是否启用
method            HTTP 方法
url               HTTP URL，可使用 {{参数名}} 占位
headers           请求头模板，可使用 {{参数名}} 占位
queryParams       查询参数模板，可使用 {{参数名}} 占位
bodyTemplate      请求体模板，可使用 {{参数名}} 占位
parameters        MCP inputSchema 参数定义
timeoutSeconds    请求超时时间，秒
```

参数定义：

```text
name        参数名
type        string / number / integer / boolean / object / array
description 参数描述
required    是否必填
```

模板示例：

```text
url: https://example.com/weather?city={{city}}
headers:
  Authorization: Bearer {{token}}
bodyTemplate: {"keyword":"{{keyword}}"}
```

## 6. 桥接脚本

脚本：

```text
main/xiaozhi-server/scripts/no_code_mcp_tools.py
```

环境变量：

```text
MCP_ENDPOINT_URL          MCP Endpoint websocket 地址
MANAGER_API_URL           manager-api 地址，例如 http://127.0.0.1:8002/xiaozhi
MANAGER_API_SECRET        server.secret
AGENT_ID                  智能体 ID
MCP_TOOL_RELOAD_SECONDS   工具配置刷新间隔，默认 30 秒
```

启动示例：

```bash
cd main/xiaozhi-server
conda run -n xiaozhi-esp32-server python scripts/no_code_mcp_tools.py
```

注意：这是长期运行桥接进程。生产环境应使用 systemd、Docker 或进程管理器托管，并妥善保护 `MANAGER_API_SECRET`。

## 7. 安全边界

该能力可以发起任意 HTTP 请求，必须按高风险工具对待。

必须遵守：

- 只允许可信用户配置 no-code HTTP 工具。
- 不要把生产密钥、数据库密码、长期 access token 写入工具 JSON。
- 不要允许工具访问云元数据地址、内网管理端口、数据库管理端口、Redis、Docker API 或服务器控制面板。
- 生产环境建议增加 URL allowlist / denylist。
- 工具返回内容会进入模型上下文或语音回复，外部接口返回内容可能形成提示词注入风险。
- 写操作接口必须谨慎开放，必要时增加人工确认或只读代理。

后续代码增强建议：

- 后端校验工具名、HTTP 方法、URL scheme、timeout 范围、参数数量和 JSON 总大小。
- 禁止访问 `127.0.0.0/8`、`169.254.169.254`、私有网段等高风险地址，或只允许白名单域名。
- 保存前检测重复工具名。
- 首次并发保存时使用 upsert 或捕获唯一键冲突。

## 8. 运维与刷新

修改工具配置后，桥接脚本会按 `MCP_TOOL_RELOAD_SECONDS` 轮询刷新。

设备能否马上看到工具，还取决于：

```text
MCP Endpoint 连接是否正常
xiaozhi-server 是否已发现该 MCP tools/list
设备当前会话是否重新加载工具
LLM 是否在当前轮次拿到了新工具 schema
```

如果修改后没有生效，按顺序检查：

```bash
scripts/dev logs server -f
```

以及桥接脚本进程日志、MCP Endpoint 日志和 manager-api `/config/mcp/no-code-tools/{agentId}` 返回内容。

# MCP、上下文源与视觉能力操作手册

本文是 MCP 相关日常配置、验证和排障入口。协议链路、五类工具源优先级与冲突规则见 [MCP 链路架构](../architecture/mcp-chains.md)；本文不重复维护架构事实。

## 先选择正确能力

| 需求 | 选择 |
| --- | --- |
| 每次唤醒都注入实时状态 | 上下文源 |
| 由模型按需调用服务端函数 | Server Plugin 或 Server MCP |
| 设备自身暴露能力 | Device IoT / Device MCP |
| 外部工具进程动态接入 | MCP Endpoint |
| 智控台包装简单 HTTP API | 可视化 MCP HTTP 工具 |
| 摄像头图片理解 | MCP Vision + VLLM |

同名工具按项目既定优先级保留先注册者。设备上报的 IoT 描述符不能绕过服务端门控直接成为 LLM 工具。

Home Assistant 等完整第三方配置见[第三方服务集成手册](../integrations/third-party-integrations.md)。

## 共同安全边界

- MCP Endpoint 的完整 URL（包括 query 中的 `token`/`key`）就是密钥，不写日志、截图、工单、shell 历史或 Git。公网使用 `wss://`/`https://`。
- 所有用户配置 URL 都必须通过项目 SSRF 校验，阻止回环、链路本地、私网、云元数据地址和 DNS 重绑定；重定向后的最终地址也要验证。
- `Authorization`、API Key、Access Token、Server Secret 只在私有配置或环境变量注入，日志统一脱敏为 `***`。
- 工具返回、上下文源响应和视觉描述都是不可信内容，只能当数据，不能覆盖系统指令或授权边界。
- 外部 MCP/npm 包需要安全审查。生产优先预安装固定版本，不临时执行未锁定的 `npx -y`。

## MCP Endpoint

MCP Endpoint 用于把独立工具进程连接到指定智能体。部署服务本身、让 xiaozhi-server 连接它、再让工具进程注册，是三个不同步骤。

### 部署 Endpoint 服务

从官方仓库获取并审查版本：

```bash
git clone https://github.com/xinnan-tech/mcp-endpoint-server.git
cd mcp-endpoint-server
docker compose up -d
docker compose logs -f
```

不要为“干净启动”删除镜像或构建缓存。日志会给出健康检查地址和 WebSocket 地址；Docker 内部 `172.*` 地址不能直接给宿主机或设备使用，应替换为调用方可路由的 TLS 域名。健康检查返回 `status=success` 只表示 Endpoint 存活，不代表工具已经注册。

### 连接 xiaozhi-server

全模块部署：在“系统功能配置”启用 MCP 接入点，再在参数管理填写 `server.mcp_endpoint`。保存失败通常是 manager-api 无法访问 Endpoint、TLS/防火墙错误或 URL 未通过 SSRF 验证。

单模块部署：在私有 `data/.config.yaml` 配置：

```yaml
mcp_endpoint: "wss://可信域名/mcp_endpoint/mcp/?token=通过私有配置注入"
```

通过 `scripts/dev start server` 启动并查看脱敏后的连接阶段日志。任何日志实现都不得输出完整 URL query。

### 注册并验证工具

工具进程使用当前智能体的 Endpoint URL 建立连接并提供 `tools/list`、`tools/call`。以计算器示例项目为例时，按其 README 在独立环境安装固定依赖，通过进程环境变量传入 Endpoint URL；不要把 URL 写入脚本。

验证顺序：

1. Endpoint 健康检查成功。
2. 工具进程连接成功，Endpoint 统计中 `tool_connections` 增加。
3. 智控台刷新后显示准确的工具名和 schema。
4. `xiaozhi-server` 完成 MCP initialize 和 tools/list。
5. 新建会话后，LLM function list 中出现工具且能完成一次只读调用。

若存在同名 Server Plugin，Endpoint 工具会按优先级被跳过并记录 warning，这是预期行为。

## 可视化 MCP HTTP 工具

该能力链路是：

```text
manager-web 配置
  -> manager-api 按 agent_id 保存 JSON
  -> no_code_mcp_tools.py 拉取配置
  -> MCP Endpoint 注册 tools/list 和 tools/call
  -> xiaozhi-server 发现工具
```

它适合可信的简单 HTTP 查询或固定 POST，不适合文件上传、长连接、数据库直连、高风险写操作或复杂鉴权。

### 配置字段

| 字段 | 说明 |
| --- | --- |
| `name` / `description` / `enabled` | 工具标识、模型说明和开关 |
| `method` / `url` | HTTP 方法与 URL，可用 `{{参数名}}` |
| `headers` / `queryParams` / `bodyTemplate` | 请求模板 |
| `parameters` | MCP inputSchema 参数，支持基础 JSON 类型 |
| `timeoutSeconds` | 有上限的短超时 |

工具名必须唯一且受格式限制；URL scheme、域名、重定向、超时、参数数量和 JSON 大小都应在后端校验。写操作应增加人工确认或只读代理。

桥接进程使用：

```text
MCP_ENDPOINT_URL
MANAGER_API_URL
MANAGER_API_SECRET
AGENT_ID
MCP_TOOL_RELOAD_SECONDS
```

在项目 Conda 环境运行：

```bash
cd main/xiaozhi-server
conda run -n xiaozhi-esp32-server python scripts/no_code_mcp_tools.py
```

它是长期进程，生产必须由受控进程管理器托管，环境变量不得出现在进程日志。工具更新默认轮询后生效，设备当前会话仍可能需要重新加载 schema。

管理端接口必须同时检查登录权限和智能体所有权；服务端配置接口只允许 `server.secret` 调用，并继续做 per-agent 校验。

## 上下文源

上下文源在构建 Prompt 时请求 HTTP API，并替换 `{{ dynamic_context }}`。它会强制注入唤醒时状态，不适合需要随时刷新或产生副作用的动作；后者使用工具。

接口规范：GET 请求，系统添加 `device-id` Header；响应必须是 JSON，`code=0` 且 `data` 为对象、列表或字符串。例如：

```json
{
  "code": 0,
  "data": {
    "客厅温度": "26℃",
    "大门状态": "已关闭"
  }
}
```

智控台在角色配置的“上下文源”添加 URL 和必要 Header。单模块在私有配置中填写：

```yaml
context_providers:
  - url: "https://可信业务域名/device-context"
    headers:
      Authorization: "Bearer 通过私有配置注入"
```

确认私有提示词模板包含 `{{ dynamic_context }}`。外部响应必须包裹不可信内容边界并限制长度；超时或单个源失败不应阻塞整个会话。避免把敏感健康数据、内部拓扑或凭据无差别送入第三方 LLM。

## 向工具提供设备 ID

默认提示词上下文可加入：

```markdown
<context>
- **设备ID：** {{device_id}}
- **当前时间：** {{current_time}}
</context>
```

单模块需要自定义时，把 `agent-base-prompt.txt` 复制为私有 `data/.agent-base-prompt.txt`，并在 `data/.config.yaml` 设置：

```yaml
prompt_template: data/.agent-base-prompt.txt
```

工具 inputSchema 再声明名为 `device_id` 的 string 参数。`device_id` 只能用于定位当前设备，不能替代用户身份、资源所有权或权限检查，也不应回传到无关第三方。

## MCP Vision

前提：带摄像头且支持视觉调用的设备、兼容固件（至少 1.6.6）、基础语音链路已经跑通、已配置 VLLM。

全模块部署在智控台配置视觉模型密钥并给智能体选择 VLLM；单模块在私有配置选择 provider：

```yaml
selected_module:
  VLLM: ChatGLMVLLM
VLLM:
  ChatGLMVLLM:
    api_key: "通过私有配置注入"
```

通过统一入口启动：

```bash
scripts/dev start server
scripts/dev logs server -f
```

`server.vision_explain` 会下发到设备，必须是设备所在网络可达的 HTTPS 反向代理地址，不能是 `127.0.0.1`、WSL `172.*` 或 Docker 内部地址。全模块优先在参数管理配置，单模块写私有 `data/.config.yaml`：

```yaml
server:
  vision_explain: https://可信域名/mcp/vision/explain
```

生产反向代理必须同时转发 `/mcp/vision/explain` 和 `/mcp/vision/images/` 到
xiaozhi-server 的 8003 端口；前者接收设备上传并执行视觉分析，后者供智控台加载已保存的原图。
如果图片 URL 返回 `text/html`，说明请求落入了 manager-web 的根路径代理，而不是图片下载端点。

用设备所在网络访问健康地址，确认返回的视觉解释地址与配置一致，再让设备执行一次摄像头工具。仍访问旧地址时检查 manager-api 配置、缓存更新和 xiaozhi-server 重启；不要直接清空生产 Redis。

## 排障顺序

1. 确认能力类型选对，智能体开关和 action 白名单已启用。
2. 检查 URL 可达性、TLS、SSRF 校验和调用方网络视角。
3. 查看 Endpoint、桥接进程和 `scripts/dev logs server -f` 的脱敏日志。
4. 检查 initialize、tools/list、工具冲突 warning 和当前会话 schema。
5. 对 HTTP 工具检查 method、模板参数、超时和返回 JSON；对视觉检查设备下发地址。
6. 最后用新会话做一次低风险调用，不以真实生产写操作作为连通性测试。

代码变更按模块运行 Java 测试、Web 构建和项目 Conda 下的 Python 测试；外部 MCP 和视觉模型真实调用只在明确授权的联调阶段进行。

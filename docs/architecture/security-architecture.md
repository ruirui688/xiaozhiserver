---
status: canonical
owner: security
last_verified: 2026-07-12
source_paths:
  - main/xiaozhi-server/core/websocket_server.py
  - main/xiaozhi-server/core/api
  - main/manager-api/src/main/java/xiaozhi/modules/security
---

# 安全架构

本文描述系统的信任边界和强制控制。具体编码要求以 `AGENTS.md` 和安全相关源码为准。

## 1. 信任边界

```mermaid
flowchart LR
    Internet((不可信网络)) --> Proxy[TLS 反向代理]
    Proxy --> WS[Python WebSocket]
    Proxy --> HTTP[Python HTTP]
    Proxy --> Web[manager-web]
    Web --> API[manager-api]
    WS -->|共享服务密钥| API
    HTTP -->|共享服务密钥或 JWT| API
    API --> DB[(MySQL)]
    API --> Redis[(Redis)]
    WS --> External[外部模型 / MCP / URL]
```

设备、浏览器输入、上传文件、外部 URL、模型输出和 MCP 工具结果均视为不可信。内部网络不等于自动可信；共享密钥只能提供服务身份，不能替代资源归属校验。

## 2. 认证入口

| 入口 | 默认机制 | 例外 |
| --- | --- | --- |
| Python WebSocket | `auth.enabled=true`，验证 Bearer Token | `allowed_devices` 显式白名单 |
| Python 视觉接口 | JWT Bearer Token | 无隐式匿名 |
| Python Device MCP HTTP | 原始 `auth_key` 或 manager-api 密钥 | 仅受信内部调用 |
| Java 管理 API | Shiro OAuth2 Filter | `ShiroConfig` 显式 `anon` 路径 |
| Java `/config/**` | `ServerSecretFilter` | OPTIONS 预检 |
| OTA | 匿名 | 必须限流、校验文件名和下载路径 |

新增端点必须在对应过滤器或路由中明确身份策略，不能依赖模糊默认值。

## 3. 授权

- Java CRUD 必须使用实体实际的 `workspace_id`、`agent_id` 或 `device_id` 验证资源所有权。
- `X-Workspace-Id`、请求体中的 agent/device/workspace 标识不能直接作为授权结论。
- `/config/**` 即使通过共享服务密钥，也必须按设备绑定和智能体归属限制返回数据。
- Controller 的权限注解、Shiro 路由和应用服务的资源校验承担不同职责，不能互相替代。
- MEMBER 等只读角色的写限制必须在服务端执行，前端隐藏按钮只改善交互。

## 4. 密钥与日志

- 可提交配置只能包含占位符；真实密钥进入私有配置、环境变量或专用密钥表。
- 禁止把 API Key 放入 URL query；使用 Header 或请求体中的受保护字段。
- 日志不得输出 Authorization、Bearer Token、API Key、access token、secret/private key 或完整私有配置。
- 可记录 `tokenPresent`、凭据类型、脱敏尾号、错误分类和请求关联 ID。
- Redis 不应长期缓存完整明文模型密钥；需要缓存时使用最短生命周期和最小字段集。

## 5. 输入与外联

- 用户提供的 MCP、RAG、天气、新闻或 HTTP 工具 URL 必须执行 SSRF 校验，阻止回环、私网、链路本地和云元数据地址。
- 文件上传同时校验扩展名、MIME、大小和业务允许类型。
- 文件读取与下载使用规范化真实路径并确认仍位于允许根目录。
- 模型和 MCP 返回的文本、JSON、工具名和 schema 在使用前必须校验类型和大小。
- no-code HTTP 工具属于高风险能力，只能开放给可信运营人员并限制目标网络。

## 6. 传输与容器

- 生产必须由反向代理提供 HTTPS/WSS；设备配置下发 `wss://` 和公网可达的 `https://` 地址。
- MySQL、Redis 和内部 manager-api 地址只在容器网络或受控主机网络可达。
- 数据库和 Redis 密码不得有生产默认值；容器使用非 root、`cap_drop: ALL` 和最小 `cap_add`。
- Docker 镜像、MCP npm 包和 Python/Java 依赖应锁定版本并进入供应链审查。

## 7. 安全变更检查

安全相关变更至少验证：匿名路由清单、Token 失败路径、跨 workspace/agent 访问、日志脱敏、SSRF 私网阻断、上传路径穿越、共享密钥覆盖范围，以及 Python 核心中是否有裸 `except:` 吞掉安全异常。

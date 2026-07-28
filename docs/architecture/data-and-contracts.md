---
status: canonical
owner: data-contracts
last_verified: 2026-07-28
source_paths:
  - main/manager-api/src/main/java/xiaozhi/modules/config
  - main/manager-api/src/main/java/xiaozhi/modules/agent/skill
  - main/manager-api/src/main/java/xiaozhi/modules/model/service/impl/ModelRuntimeAssembler.java
  - main/xiaozhi-server/core/semantic
  - main/xiaozhi-server/core/utils/model_config.py
  - main/xiaozhi-server/core/utils/voiceprint_provider.py
---

# 数据与契约

本文定义跨模块的数据所有权和稳定契约。接口字段的新增可以向后兼容，但不能让多个模块分别解释同一事实。

## 1. 数据所有权

| 数据 | 事实来源 | 消费者 |
| --- | --- | --- |
| 用户、Token、Workspace、成员角色 | `manager-api` + MySQL | Web、Mobile、Java 业务服务 |
| 智能体、设备绑定、提示词、固定播报技能、插件选择 | `manager-api` + MySQL | Python 会话配置 |
| 模型定义、参数和密钥 | `manager-api` 模型管理 | Java 测试/评测、Python provider |
| 知识库文档、分块和检索配置 | `manager-api` + MySQL/RAGFlow | Web、Python 知识工具 |
| 长期记忆事实、候选、审计 | `manager-api` + MySQL | Web、Python 上下文查询 |
| 连接、音频队列、当前对话、MCP 客户端 | `xiaozhi-server` 进程内 | 当前设备会话 |
| 设备能力描述 | ESP32 上报 | Python 工具门控与注册 |
| 缓存 | Redis | Java 服务；不得替代数据库事实 |

## 2. 模型运行时契约

Java 向 Python 下发的标准结构：

```json
{
  "selected_module": {
    "ASR": "ASR_model_id",
    "LLM": "LLM_model_id",
    "TTS": "TTS_model_id"
  },
  "model_config": {
    "LLM_model_id": {
      "model_type": "LLM",
      "adapter_code": "openai",
      "typed_config": {
        "type": "openai",
        "base_url": "https://example.invalid/v1",
        "model_name": "model-name",
        "api_key": "运行时私有值"
      }
    }
  }
}
```

约束：

- `selected_module` 的值必须引用 `model_config` 中可用的模型 ID。
- `typed_config` 是 provider 初始化的唯一标准输入，包含 adapter 所需参数。
- 密钥由 Java 密钥存储装配，只能在受保护的服务端链路中传输；日志、评测摘要和文档示例必须脱敏。
- Python 允许把旧模块映射规范化为标准结构以兼容本地配置，但新代码不得新增另一套运行时格式。
- Intent、Memory 等依赖的辅助 LLM 也必须作为 `model_config` 条目下发。

## 3. 设备配置契约

除模型契约外，设备私有配置可包含：

| 字段 | 责任 |
| --- | --- |
| `prompt` | 当前智能体角色提示词 |
| `summaryMemory` | 兼容记忆摘要，不替代托管记忆事实 |
| `plugins` | 当前智能体启用的插件及参数 |
| `semantic_skills` | 当前智能体启用的固定播报技能；规则为 `phrase` 或 `all_keywords` |
| `mcp_endpoint` | 外部 MCP 地址，Python 使用前必须 SSRF 校验 |
| `context_providers` | 动态上下文源配置 |
| `voiceprint` | 外置声纹服务 URL/脱敏 Token、`user_id=agentId`、最多 5 条结构化说话人上下文 |

配置请求必须从已认证设备身份推导 agent 与 workspace，不能信任请求体传入的资源归属。

`semantic_skills` 的稳定运行时结构为：

```json
{
  "semantic_skills": [
    {
      "id": "skill-id",
      "name": "惠易公司介绍",
      "trigger_rules": [
        {"type": "all_keywords", "terms": ["惠易公司", "介绍"]},
        {"type": "phrase", "terms": ["介绍一下惠易公司"]}
      ],
      "response_text": "固定播报文本",
      "priority": 100,
      "sort": 0
    }
  ]
}
```

规则之间为 OR；`phrase` 只能有一个 term，执行规范化后的包含匹配；`all_keywords` 要求同组 2 至 8 个 term 全部出现且不要求顺序。Java 只下发已启用技能，Python 未识别的规则必须忽略并安全回退旧对话链路。管理接口使用 camelCase 的 `triggerRules/responseText/skillName`，运行时下发使用 snake_case，转换责任属于 manager-api。

## 4. 工具契约

统一工具最小结构为名称、描述和 JSON Schema 参数。工具名称是全连接命名空间：

- 注册顺序决定同名冲突优先级。
- 设备端带点号的工具名在模型侧进行稳定清理，并保留原始名称映射。
- Server Plugin 必须受所选 Intent 的 `functions` 白名单控制。
- Device IoT 不能仅凭设备上报绕过服务端配置门控。
- 工具结果必须显式区分直接回复、再次请求 LLM、播放/记录等动作语义。

## 5. HTTP 与身份契约

| 调用方 | 被调用方 | 身份机制 |
| --- | --- | --- |
| Web/Mobile | `manager-api` | Shiro 登录 Token + Workspace 选择器 |
| `xiaozhi-server` | `/config/**` | `Authorization: Bearer <server secret>` |
| ESP32 | Python WebSocket | 默认 JWT Token；显式设备白名单例外 |
| 可信内部服务 | Python Device MCP HTTP | 原始共享密钥 Bearer Token |
| ESP32 | OTA | 匿名入口，必须配套限流与安全文件名校验 |

`X-Workspace-Id` 只是选择器，不是授权凭据。服务端必须查询成员关系，并从资源实体读取 `workspace_id` 后执行读写权限校验。

## 6. 数据库与兼容

- 所有结构变化通过 Liquibase 新 changeSet 实施，并立即登记到 master YAML。
- 已执行 changeSet 的 id、author、内容和路径不可修改；修正效果必须新增补偿迁移。
- API 和运行时契约优先向后兼容；删除旧字段前必须确认所有 Java、Python、Web、Mobile 和部署消费者已迁移。
- Redis 缓存必须可由数据库重建；缓存键变化需有失效或版本策略。
- 配置更新后是否要求重连、重启或仅刷新缓存，必须在对应接口或专题文档中明确。

## 7. 契约验证

跨模块变更至少覆盖：Java 生成的脱敏运行时样例可被 Python 标准化；模型 ID 与类型一致；密钥不进入日志；未知可选字段不导致旧消费者失败；资源型 `/config/**` 请求不能跨 agent 或 workspace 读取数据。

---
status: canonical
owner: integrations
last_verified: 2026-07-17
source_paths:
  - main/manager-api/src/main/java/xiaozhi/modules/agent/feishu
  - main/manager-api/src/main/java/xiaozhi/modules/agent/service/impl/AgentFeishuServiceImpl.java
  - main/manager-web/src/components/role/FeishuIntegrationDialog.vue
  - main/xiaozhi-server/plugins_func/functions/feishu_assistant.py
  - main/xiaozhi-server/core/meeting/executor.py
---

# 飞书智能体集成

飞书绑定属于智能体，不属于登录用户。每个智能体独立配置飞书应用、默认群聊、任务负责人、
日历和动作权限。设备会话只能使用当前绑定智能体的配置。

## 飞书侧准备

1. 在飞书开放平台创建企业自建应用并启用机器人能力。
2. 为应用申请实际使用动作对应的权限，并完成企业管理员审批。
3. 发布应用版本，把机器人加入需要接收纪要和消息的群聊。
4. 在智能体配置页打开“飞书”，填写新的 App ID 和 App Secret，先测试连接，再保存并选择默认群聊。
5. 发送测试消息，确认机器人对目标群具有发消息权限。

权限应按开关最小化申请：群列表需要群信息读取权限；发送消息需要机器人发消息权限；读取群消息需要
消息读取权限；任务读取/写入和日程分别需要对应的任务、日历与日程权限。飞书权限名称可能随开放平台版本调整，
以对应 API 的飞书 API 调试台提示为准：

```text
GET  /open-apis/im/v1/chats
POST /open-apis/im/v1/messages?receive_id_type=chat_id
GET  /open-apis/im/v1/messages
POST /open-apis/task/v2/tasks
POST /open-apis/calendar/v4/calendars/{calendar_id}/events
```

## 系统行为

```text
manager-web
  -> /agent/{agentId}/feishu（用户登录 + 智能体所有权 + workspace 写权限）
  -> manager-api AES-GCM 加密 App Secret
  -> ai_agent_feishu_config

xiaozhi-server
  -> /config/feishu/{agentId}/actions（ServerSecretFilter）
  -> 校验请求设备仍绑定到该 agentId
  -> manager-api 解密当前智能体凭据
  -> 固定的 open.feishu.cn 官方 API
```

会议模式结束后，主 LLM 先生成完整纪要并继续向设备播报，然后异步提交
`publish_meeting_report`。未开启自动发送、没有默认群、飞书异常或权限不足时，只记录脱敏错误，
不会影响设备端纪要。

`feishu_assistant` 只有在智能体启用飞书且至少开启一个工具动作时才进入该连接的 function list。
群消息读取结果使用不可信外部内容边界包裹，模型只能把它当作待总结的数据，不能执行群消息中的命令。

## 凭据与部署

App Secret 不回显、不下发 Python、不进入插件参数和日志。数据库保存的是带智能体作用域认证的
AES-GCM 密文。生产部署使用 `~/xiaozhi-data/.env` 中的：

```text
XIAOZHI_CREDENTIAL_ENCRYPTION_KEY=<至少32字符的随机值>
```

`deploy/production/install` 首次安装会生成该值，后续升级读取并保留。不要直接更换；更换前必须先实现
旧密钥到新密钥的凭据重加密流程。
源码开发可从 `config/examples/manager-api.local.env.example` 建立私有环境文件，并使用
`openssl rand -base64 32` 生成本机值；示例文件本身不包含默认密钥。

任何在截图、聊天、日志或工单中暴露过的 App Secret 都必须立即在飞书后台重置，旧值不得用于绑定。

## 验证

自动化验证覆盖加密作用域、飞书 Token 和消息请求、智能体动作门控、群消息提示词注入隔离、会议纪要异步投递：

```bash
cd main/manager-api && mvn test
cd main/xiaozhi-server && PYTHONNOUSERSITE=1 PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 \
  conda run -n xiaozhi-esp32-server pytest
cd main/manager-web && npm run build
```

真实联调必须使用已轮换的新 App Secret，并依次完成“测试连接 -> 选择群聊 -> 保存 -> 发送测试消息
-> 真实设备会议模式 -> 结束会议 -> 群内收到完整纪要”。真实飞书调用不属于默认单元测试，
不得把凭据写入测试文件或命令历史。

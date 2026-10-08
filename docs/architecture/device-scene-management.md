---
status: supporting
owner: device-runtime
last_verified: 2026-08-04
source_paths:
  - main/manager-api/src/main/java/xiaozhi/modules/device
  - main/manager-api/src/main/resources/db/changelog/202608040930.sql
  - main/xiaozhi-server/core/session/scene_switch.py
  - main/xiaozhi-server/core/session/runtime_config_reloader.py
  - main/manager-web/src/components/scene
  - main/manager-mobile/src/pages/device
---

# 设备多场景管理

设备场景让一台设备关联多个已有智能体，并在不复制模型、插件、知识库或记忆运行架构的前提下切换用途。跨模块运行时序仍以[运行时链路](runtime-flows.md)为准，设备协议见[设备交互链路](device-interaction-chain.md)。

## 1. 数据事实

`ai_device_scene_binding` 保存设备和智能体的多对多场景关系，同一设备最多关联 20 个场景，唯一键为 `device_id + agent_id`。场景名称、切换别名、启用状态、排序及模板来源属于绑定元数据。

`ai_device.agent_id` 仍是当前生效智能体的唯一事实源。OTA、私有配置下发和旧设备协议继续读取该字段，不维护第二个“当前场景”。迁移会把每台已有设备的当前智能体回填为一个默认场景。

`ai_device.config_version` 是单调递增的设备配置版本。`ai_device_scene_switch` 以 `switch_request_id` 幂等记录请求、调度、生效、失败或回滚状态；审计只保存设备、场景、模型引用和错误类型，不保存提示词、热词正文或凭据。

## 2. 权限与资源边界

管理 API 必须同时验证：

- 当前用户对设备工作空间具有读写权限；
- 目标智能体与设备属于同一工作空间；
- 目标场景已经绑定且启用；
- 所选模型已启用并通过真实测试，TTS 音色属于所选 TTS 模型。

模板和场景只保存已经授权的资源 ID。API Key、Token、密码、第三方 Header、声纹样本、聊天历史和长期记忆事实不进入模板或场景绑定。跨工作空间引用直接拒绝。

## 3. 切换时序

```text
Web/Mobile PUT /devices/{deviceId}/active-scene
  -> 锁定 ai_device 行并校验 config_version
  -> 更新 ai_device.agent_id，版本 +1，写 REQUESTED 审计
  -> 事务提交后按 deviceId 通知 xiaozhi-server
  -> 在线且空闲：立即刷新；正在识别或播报：下一轮安全边界刷新
  -> 先创建候选 provider 管线，再停止并替换旧管线
  -> 成功：回传 APPLIED
  -> 失败：恢复旧管线；manager-api 仅在设备版本仍匹配时回滚 agent_id
```

离线设备的数据库切换直接视为生效，下一次连接按新的 `ai_device.agent_id` 拉取配置。连续切换以更高 `config_version` 为准，旧失败结果不能覆盖新请求。

## 4. 会话隔离

同一智能体内普通配置热更新保留当前对话历史和会话级状态。跨智能体场景切换会生成新的聊天会话 ID，并清空连接内的：

- user/assistant/tool 对话上下文与待执行工具状态；
- 游戏、会议、通话及单轮背景上下文；
- 当前说话人和声纹采集元数据；
- 临时 TTS 音色、语速、音量和音调覆盖。

候选管线激活失败时，上述状态快照与旧 provider 一起恢复，设备继续使用原场景。场景切换不会删除后台已经持久化的聊天、长期记忆或声纹数据；隔离依靠新的 agent/session 作用域阻止跨场景读取。

## 5. 管理接口

```text
GET    /devices/{deviceId}/scenes
POST   /devices/{deviceId}/scenes
PUT    /devices/{deviceId}/scenes/{bindingId}
DELETE /devices/{deviceId}/scenes/{bindingId}
PUT    /devices/{deviceId}/active-scene
POST   /config/device-scenes/switch-result  # server-secret + 请求事实匹配
```

当前场景不能禁用或删除。状态回传接口虽然位于 `/config/**` 且使用服务端共享密钥，仍必须匹配已经登记的 `deviceId + switchRequestId + agentId + configVersion`。

## 6. 工作空间设备中心

Web 导航的“设备管理”进入 `/device-center`，按当前工作空间智能体汇总已绑定设备（最多四个并发列表请求），支持设备名称、MAC、型号搜索及智能体筛选。无权访问或读取失败时显示错误，不以空列表伪装成功。

设备卡片直接选择目标智能体并切换。已有场景复用；未关联时先创建场景，再调用原 `active-scene` 接口。禁用场景不会自动启用，20 个场景上限及设备写权限、同工作空间、模型验证仍由管理 API 校验。创建成功但切换失败的场景保留，可重试或在场景管理删除。

前端读取场景事实和切换审计，显示请求、等待、生效、失败及回滚；等待状态每三秒刷新，约两分钟后停止自动刷新并提示手动确认，组件销毁时取消轮询并丢弃过期响应。“最近连接”仅为历史时间，不代表在线。原 `/device-management?agentId=...` 保留设备设置、音频预览及解绑入口。

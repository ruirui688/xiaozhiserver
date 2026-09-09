---
status: canonical
owner: architecture
last_verified: 2026-08-17
source_paths:
  - main/manager-api/src/main/java/xiaozhi/modules/reception
  - main/manager-api/src/main/resources/db/changelog/202608171500.sql
  - main/manager-api/src/main/resources/db/changelog/202608171700.sql
  - main/manager-api/src/main/resources/db/changelog/202608171900.sql
  - main/xiaozhi-server/core/reception.py
  - main/xiaozhi-server/core/reception_interrupt.py
---

# 机器人门口迎宾产品化

服务器端接待领域、跨端协议和运营 API 已实现。完整产品仍需真实业务数据、YHRobot 目标机与导航后端
验收；不能仅凭代码存在宣称两秒、90% 或 10 cm 指标已经达成。

## 跨仓库职责

完整接待系统由本仓库与 `robot_control` 仓库共同组成：本仓库拥有客户、预约、工作空间配置、
设备实时会话、ASR/LLM/TTS 和运营管理；机器人仓库拥有现场感知、多人选择、主动欢迎时机、MCP、Safety、
X2 动作与屏幕执行。定位、地图、规划、避障和真实到达结果属于部署侧导航后端，当前两个仓库都没有可用
生产实现。

跨端调用遵循：

```text
机器人现场事实 → reception_v1 → xiaozhi-server
→ /config/reception/context → manager-api → MySQL
→ reception action → 机器人唯一 listen.detect / MCP / 动作策略
```

服务器不得直接控制 MC，也不得在机器人本地欢迎之外创建第二条主动播报链；机器人不得把缓存客户阶段、
预约或菜单反写成服务器权威事实。

## 数据模型

| 表 | 权威事实 |
| --- | --- |
| `ai_person_profile` / `ai_person_face_asset` | 人物身份和不可变人脸资源版本 |
| `ai_person_agent_binding` | 人物与智能体的期望绑定和已发布识别快照 |
| `ai_reception_customer_profile` | 新客/回访/长期/VIP、公司、爱好、餐饮/座位偏好、敏感需求同意范围、到访统计 |
| `ai_reception_appointment` | 预约时间窗、人数、座位需求和 `PENDING/CONFIRMED/ARRIVED/...` 状态 |
| `ai_reception_visit_event` | 设备事件幂等、Group、scene epoch、选中人物、人数和选择理由 |
| `ai_reception_workspace_config` | 公司介绍、最多 100 FAQ、餐品、语义座位、舞台往返、服务员说明和配置版本 |

接待领域独立于 `PersonProfileServiceImpl`。健康、过敏和无障碍等敏感需求只有 `consent_scope` 包含
`SENSITIVE` 才下发；普通公司、爱好和偏好要求 `RECEPTION`。日志不得打印正文。

## 客户阶段与预约

- 首次建立接待档案为 `NEW`；
- 第二次有效到访后为 `RETURNING`；
- 到访达到 5 次且首末跨度至少 30 天时自动成为 `LONG_TERM`；
- `VIP` 只由授权运营人员设置，不被自动规则覆盖；
- 预约状态固定按 `PENDING -> CONFIRMED -> ARRIVED -> COMPLETED` 流转，允许从待确认或已确认取消，
  已确认可标记 `NO_SHOW`；终态不能回退。
- 只有当前时间落入 `CONFIRMED` 预约窗口时，接待上下文才返回预约，幂等到访事件落库后改为 `ARRIVED`。
- 同一人物的 `PENDING/CONFIRMED/ARRIVED` 时间窗不能重叠；新预约默认 `PENDING`，不能直接创建终态。

同一 `device_id + event_id` 只累计一次。事件审计写入失败不会在已发送 `reception/action` 后再返回 error，
避免设备执行本地回退形成第二轮欢迎。

## 管理与内部 API

运营 API：

```text
GET/PUT /resource/reception/persons/{personId}
GET/POST /resource/reception/persons/{personId}/appointments
PUT/DELETE /resource/reception/appointments/{appointmentId}
GET/PUT /resource/reception/workspace-config
```

管理 Web 的“人物与人脸”页面提供两个入口：“接待与预约”维护个人客户阶段、公司、偏好、同意范围和预约；
“接待配置”维护公司介绍、FAQ、餐品、语义座位和舞台往返 JSON。保存前浏览器校验 JSON 类型，
服务端继续执行长度、数量、Workspace 和权限校验。

实时服务器内部 API：

```text
POST /config/reception/context
POST /config/reception/events
```

所有人物、智能体、设备、预约和配置必须属于同一 Workspace。内部 API 使用已认证连接的设备 ID，并按
设备当前智能体的已发布人物绑定授权；设备消息中的 MAC、客户阶段和预约声明不作为身份来源。

## `reception_v1`

设备 Hello 声明：

```json
{"features":{"reception_v1":true}}
```

本地 `InteractionSessionCoordinator` 产生迎宾机会后发送：

```json
{
  "type":"reception",
  "event":"group_identified",
  "version":1,
  "eventId":"robot:group:visit:3:1",
  "groupSessionId":"group-1",
  "sceneEpoch":8,
  "presentCount":4,
  "recognized":[{
    "personId":"person-1",
    "visitId":"visit-1",
    "confidence":96.0,
    "distanceAvailable":true,
    "distanceM":2.4,
    "azimuthAvailable":true,
    "azimuthRad":0.35
  }]
}
```

服务器返回一个 `action`，包括：

- `spokenText`：姓名、长期客户小话题、预约确认和最多 60 字公司介绍的确定性组合；
- `conversationContext.people`：只含已授权客户事实；
- `faq/products/seats`：设备本地缓存，欢迎提示不直接注入大目录；
- `selectedPersonIds` 和配置版本。

设备把去除大目录后的动作放入唯一 `listen.detect`。服务器 1 秒未返回时设备使用本地姓名欢迎；不存在
服务器 `SPEAK_TEXT` 与本地 Visit 同时触发的第二条链。

## 多人规则

- Group ≤3：视觉层为每个 Visit 调度识别，欢迎点名全部确认身份；
- Group >3：按已有在途请求、有效距离、最近距离、脸质量、入场时间和 Visit ID 选一名代表；
- 代表确认后其余 Visit 保留本地 Track/Visit，但不再调用 Provider；
- 服务器再次按人数将选择上限限制为 3/1，防止异常设备绕过规则。

## 打断

接待欢迎/介绍建立 15 秒锁。AEC 开口不会立即停止设备播放；完整 ASR 只有命中“闭嘴、别说了、不要说
了、停止说话、停止播报、停下、安静”才调用 Abort。其他说话人按 ID 进入最多 16 条有界队列，当前第一
提问者回复结束后随机选择一位进入下一轮。TTS stop、失败、断线或锁超时后恢复普通交互。急停、Safety、
进程关闭和运维停止不受该语义门限制。

## FAQ、餐品、座位与本地二维码

设备 MCP 只使用本次 `reception_v1` 缓存：

- FAQ 不命中时明确返回无结果；
- 餐品按人数、显式口味、预算、套餐、库存和已授权偏好排序；
- 人物档案中的确认生日年龄段与性别只能做弱排序，不从外观推断；
- 推荐不满意固定提供二次推荐、服务员协助和扫码点餐；
- 座位按容量、可用状态和偏好推荐，只允许引导到配置目录中的 ID；
- 二维码由机器人控制台本地生成，固定显示 10 秒后恢复默认屏幕并清理临时资产。
- 舞台表演固定执行“引导到配置舞台→等待配置动作完成→引导回接待点”，并用互斥锁拒绝重复指令。

年龄、性别或外观不能替代用户明确需求，不能用于推断疾病、消费能力或忌口。

## 验证边界

已实现并自动验证：数据库服务、Workspace/设备/人物绑定、同意范围、到访阶段、预约到达、WebSocket
协议、多人 3/1 规则、审计失败去重、明确停止词、多人排队、FAQ/推荐/座位、本地二维码和舞台互斥编排。

当前验证结果：manager-api 全量 Maven 测试通过；xiaozhi-server 使用项目 Conda 环境并设置
`PYTHONNOUSERSITE=1` 后 621 passed；manager-web 80 passed。

仍需现场或运营输入：

1. 真实 100 条 FAQ 和 ≥90% 评测；
2. 真实公司、菜单、库存、座位和服务员配置；
3. YHRobot 相机距离/方位、两秒 TTS 首响和 24 小时长稳；
4. 转向、鞠躬、二维码 Face UI 的 L2/L3 验收；
5. `/yhrobot/navigation/guide_to_seat` 定位规划后端、舞台/座位地图和 10 cm 到达精度。

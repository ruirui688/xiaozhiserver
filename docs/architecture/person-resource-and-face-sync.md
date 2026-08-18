---
status: supporting
owner: manager-api
last_verified: 2026-08-04
source_paths:
  - main/manager-api/src/main/java/xiaozhi/modules/person
  - main/xiaozhi-server/core/device_face_profiles.py
  - main/manager-web/src/views/PersonProfileManagement.vue
---

# 人物资源与设备人脸同步

资源中心中的“人物与人脸”指机器人可识别的业务人物档案，不是管理后台登录账号。知识库仍使用原有数据集和文档链路，插件仍使用 Function Plugin 模型；资源中心只统一入口和交互，不复制这两类事实。资源中心顶部只保留知识库、人物与人脸、插件三项紧凑次级导航，各页面自行承担标题和任务说明，不能再重复展示 `RESOURCE CENTER`、资源中心标题或相同描述。

## 数据边界

```text
ai_person_profile              工作空间人物资料与归一化照片元数据
ai_person_device_binding       人物照片允许下发到哪些设备
ai_person_recognition_event    设备本地识别事件审计
/uploadfile/person-faces/      生产容器内归一化 JPEG 文件（运行数据，不进入 Git）
```

人物档案、目标设备和识别事件必须属于同一工作空间。OWNER/ADMIN 可以修改，MEMBER 只读。照片上传同时校验扩展名、MIME、大小、实际图片内容和像素数量，再缩放到最长边 640 像素并转为 JPEG。数据库和接口不保存或返回服务端人脸特征向量。

人物资料与照片是两个连续保存步骤，设备下发绑定可以为空；未选择设备只影响 `ai_person_device_binding`，不得跳过或删除照片。生产通过 `PERSON_FACE_STORAGE_ROOT=/uploadfile/person-faces` 写入持久化挂载，manager-api 启动时会检查目录可创建且可写，避免资料保存成功后才暴露照片目录错误。照片上传失败时管理端保留当前人物 ID 和本地预览，后续保存只重试同一人物，不能重复创建档案。

## 当前运行链路

```text
设备 hello.features.face_profiles=true
  -> xiaozhi-server 等待设备绑定完成
  -> GET /config/device-face-profiles/{macAddress}
  -> manager-api 按 device.workspace_id + 显式绑定过滤人物
  -> xiaozhi-server 发送 face_profiles/manifest
  -> 逐条发送 face_profiles/upsert（含归一化图片 Base64）
  -> 发送 face_profiles/complete

设备本地识别成功
  -> 当前已认证 WebSocket 发送 face_recognition
  -> xiaozhi-server 使用连接中的 device_id，上报人物 ID、置信度和事件 ID
  -> manager-api 再次校验设备、工作空间和人物绑定
  -> 幂等记录事件并返回 NONE 或 SPEAK_TEXT 动作
  -> xiaozhi-server 把 face_recognition/action 返回设备
```

设备上报消息中的 `macAddress` 不作为身份来源。旧设备没有声明 `face_profiles` 时不发送清单，也不接受识别事件。下发单张图片、单设备总字节数和人物数量均有界，避免大清单占满 WebSocket。

设备协议示例：

```json
{
  "type": "face_recognition",
  "eventId": "camera-boot-7-event-23",
  "personId": "人物档案ID",
  "confidence": 0.92,
  "capturedAt": "2026-08-04T17:30:00.000+08:00"
}
```

服务端返回：

```json
{
  "type": "face_recognition",
  "event": "action",
  "eventId": "camera-boot-7-event-23",
  "personId": "人物档案ID",
  "actionType": "SPEAK_TEXT",
  "text": "欢迎回来，需要我帮你做什么？",
  "duplicate": false
}
```

动作表示服务器对本次事件的确定性指令。当前只支持 `NONE` 和 `SPEAK_TEXT`；设备可以本地播报，也可以按固件约定把文字提交到现有原文 TTS 链路。后续插件、通知或自动化动作必须在服务端建立白名单执行器，不能把任意 URL 或脚本直接下发给设备。

## 服务端识别预留

`GET /resource/person-recognition/capability` 固定返回 `supported=false` 和 `DEVICE_LOCAL_ONLY`。`POST /resource/person-recognition/server` 是兼容未来客户端的能力探测入口，当前只接受 JSON 设备标识并拒绝 multipart 图片请求，不读取、不保存图片，也不调用任何人脸模型。

未来启用服务端识别前必须另行完成：

- 明确的功能开关、模型供应器和资源预算；
- 生物特征处理告知、保存周期与删除流程；
- 图片上传速率限制、MIME/路径检查和工作空间隔离；
- 特征加密、日志脱敏、阈值评测和误识别处理；
- 真实模型测试以及设备本地/服务端模式的明确切换，禁止静默双重识别。

## 管理端任务边界

人物页面按“新增人物 -> 上传正面照片 -> 选择下发设备和识别后动作”组织，不用大幅技术架构说明代替操作入口。列表优先展示人物、人脸状态、设备数量、动作和启用状态；“设备本地识别”和“服务端识别未启用”只作为紧凑状态说明。

人物设备选择接口返回设备别名、MAC、硬件型号、固件版本、绑定智能体和最后连接时间。Web 使用设备别名作为首要识别信息，未设置别名时明确标注“未命名设备”，不能把 MAC 同时冒充名称和地址；大量设备通过绑定智能体、别名、MAC、型号和固件筛选，并在保存前展示确定的下发设备清单。最后连接时间只表示历史事实，不能冒充实时在线状态。

当前人物设备接口仍没有返回固件是否声明 `face_profiles`、照片版本是否已同步或最近识别时间，因此 Web 不得伪造“在线”“已同步”等状态。需要展示这些状态时，必须先扩展 manager-api 的只读事实和设备上报协议，并继续执行工作空间鉴权。

## 与机器人门口接待的边界

人物模块继续负责身份和人脸资源；客户阶段、预约、偏好、公司、FAQ、餐品、座位、二维码和到访审计由
独立 `modules/reception` 负责。YHRobot 不消费 `face_profiles` 图片清单，而是把现有 Provider 的稳定
`person_id` 映射到服务器人物档案，并通过 `reception_v1` 批量查询接待上下文。主动欢迎仍由机器人本地
Visit / Group 协调器决定，服务器不能用单人 `face_recognition/SPEAK_TEXT` 再触发一轮。

完整数据、协议和验证边界见[机器人门口迎宾产品化](robot-reception-productization.md)。

## 验证边界

自动测试覆盖图片归一化与路径穿越、跨工作空间拒绝、设备绑定校验、WebSocket 清单、单人识别事件和
`reception_v1`。真实摄像头、Provider ID 与人物档案数据一致性、照片缓存删除、两秒首响和物理播报完成
状态仍需目标硬件联调。

---
status: canonical
owner: manager-api
last_verified: 2026-10-13
source_paths:
  - main/manager-api/src/main/java/xiaozhi/modules/person
  - main/manager-api/src/main/java/xiaozhi/modules/agent/entity/AgentVoicePrintEntity.java
  - main/manager-api/src/main/java/xiaozhi/modules/agent/service/impl/AgentVoicePrintServiceImpl.java
  - main/manager-api/src/main/resources/db/changelog/202608261130.sql
  - main/manager-api/src/main/resources/db/changelog/202608281000.sql
  - main/manager-api/src/main/resources/db/changelog/person-voiceprint-link-20261013.sql
  - main/xiaozhi-server/core/device_face_profiles.py
  - main/manager-web/src/views/PersonProfileManagement.vue
---

# 用户信息资源、人脸发布与声纹关联

资源中心页面统一使用“用户信息”名称，其中每条记录仍是可识别人物的工作空间业务资源，不是后台登录账号。
基础资料、照片资源、人物—声纹关联、智能体人脸发布和设备本地缓存是相互独立的事实：上传照片不等于
发布，关联声纹不等于把声纹绑定到智能体，绑定智能体不等于选择设备，设备连接也不等于重新下载完整
人脸库。

## 数据边界

| 表/路径 | 权威事实 |
| --- | --- |
| `ai_person_profile` | 人物当前管理草稿、基础资料和当前照片资源指针 |
| `ai_person_face_asset` | 每次上传生成的不可变归一化 JPEG 版本 |
| `ai_person_agent_binding` | 人物与智能体的期望绑定、最近发布快照及识别动作 |
| `ai_agent_face_release` | 每个智能体单调递增的人脸库发布版本 |
| `ai_person_recognition_event` | 设备本地识别事件审计 |
| `ai_agent_voice_print.person_id` | 人物与已有声纹资源的可空、一对一显式关联 |
| `/uploadfile/person-faces/` | 归一化图片运行文件，不进入 Git |

`ai_person_device_binding` 是 v1 历史兼容表。迁移会把其设备的当前 `agent_id` 合并成
`ai_person_agent_binding`，新管理 API、v2 同步和接待授权不再写入或依赖人物—设备直绑关系。

照片上传同时校验扩展名、MIME、大小、实际图片内容和像素数量，再将最长边限制为 640 像素并转为 JPEG。
数据库和 API 不保存或返回服务端人脸特征向量。每次上传创建新资源文件和新资产行；旧已发布资产不能被
覆盖或立即删除，因为其他智能体可能仍在引用它。

## 人物与声纹的一对一关系

`ai_agent_voice_print.person_id` 是唯一关联事实。一个人物最多关联一个声纹资源，一个声纹资源也最多归属
一个人物；`person_id` 可以为空，因此既有的智能体级声纹仍可在未关联人物的状态下继续使用。人物本身仍
可绑定多个智能体，声纹资源也继续通过原有独立绑定表被同一工作空间内的多个智能体复用，两类绑定不会
互相推导或自动改变。

关联必须由有当前工作空间写权限的用户显式执行：

```text
PUT /resource/persons/{personId}/voice-print/{voicePrintId}
```

服务端同时校验人物和声纹真实存在、二者属于同一个当前可写工作空间，并确认该声纹尚未归属其他人物、
该人物尚未关联其他声纹。姓名、声纹显示名和介绍都不是关联键；即使名称相同也禁止自动匹配，历史数据
同样不得按姓名回填。新建用户信息时必须先保存人物，获得稳定 `personId` 后才能录入新声纹或选择已有
声纹进行关联。

绑定与解绑使用带当前关联条件的原子 `UPDATE`；条件更新未命中时，必须以 `SELECT ... FOR UPDATE` 读取
数据库当前值再判断幂等或冲突，不能在 MySQL `REPEATABLE READ` 下用普通快照查询判定并发结果。

关联候选通过 `GET /resource/persons/{personId}/voice-print-options` 获取，仅允许当前工作空间有写权限的用户
调用，并且只返回当前人物工作空间内尚未归属人物或已归属当前人物的声纹；不能返回其他工作空间或其他
人物已占用的生物特征资源。

解除关联使用：

```text
DELETE /resource/persons/{personId}/voice-print
```

该操作只清空 `person_id`，不删除本地声纹记录、远程生物特征、采集样本或既有智能体绑定。删除声纹则
继续使用声纹模块的破坏性删除语义：删除远程特征、所有智能体绑定、本地记录和样本；关联随声纹记录一并
消失。人物仍有关联声纹时禁止直接删除人物，必须先明确选择“解除关联”或“删除声纹”，避免人物删除事务
隐式触发远程生物特征删除，也避免留下无管理入口的声纹资源。

Web 卡片中的人脸状态和声纹状态只是上述权威事实的投影。关联新声纹时若远程注册已经成功、后续人物关联
失败，页面必须明确提示“声纹已创建但尚未关联”，并允许从已有声纹中恢复关联，不能伪装为整个创建流程
已经回滚。

## 管理与发布流程

```text
创建/编辑人物
  -> 选择智能体，只更新 desired_active
  -> 上传照片，创建不可变 current_face_asset 草稿
  -> 页面显示“有未发布变更”
  -> 用户显式点击发布
  -> ai_person_agent_binding 写入发布快照
  -> 受影响智能体 ai_agent_face_release.release_version + 1
  -> 在线 v2 设备只收到 sync_available 提示
```

发布快照包含人物名称、分组、照片资产版本、识别动作和播报文本。人物后续编辑或上传新照片不会改变正在
运行的设备事实；再次显式发布后才生效。解除智能体绑定、停用人物或删除当前照片也先形成待发布变更，
发布后设备才收到删除差异。删除整个人物是例外：服务端会递增所有已发布智能体版本，使设备能够清理缓存。

人物资料中的“介绍”（例如职业、来访背景等）同样写入发布快照 `publishedDescription`，只有显式发布后才
随人脸库同步到设备；编辑草稿不会提前改变设备上的背景信息。

## v2 设备本地持久化协议

### 能力声明

设备 Hello 推荐声明：

```json
{"features":{"face_profiles":{"version":2}}}
```

兼容别名 `face_profiles_v2=true`。服务器完成设备绑定和智能体配置加载后只发送同步就绪消息，不下发图片：

```json
{
  "type":"face_profiles",
  "event":"sync_ready",
  "protocolVersion":2,
  "agentId":"agent-a"
}
```

### 本地持久化事实

设备应在 Flash 或本地文件系统中按智能体保存：

```text
agentId
releaseVersion
personId -> faceVersion + faceSha256 + 本地图片路径 + 展示元数据
```

设备重连不得清空人脸库，也不得因为收到 `sync_ready` 就重新下载图片。设备切换智能体时可以复用该智能体
已有缓存；存储不足时可按最近使用时间清理其他智能体缓存，但不能把旧智能体版本冒充为当前版本。

### 版本协商

设备收到 `sync_ready` 或 `sync_available` 后发送本地事实：

```json
{
  "type":"face_profiles",
  "event":"sync_request",
  "protocolVersion":2,
  "agentId":"agent-a",
  "releaseVersion":12,
  "profiles":[
    {
      "personId":"person-1",
      "name":"访客一",
      "groupName":"客户",
      "description":"负责电路板设计",
      "recognitionAction":"NONE",
      "faceVersion":3,
      "faceSha256":"64位SHA-256"
    }
  ]
}
```

设备缓存的人物展示元数据也属于本地事实。姓名、分组、介绍或识别动作发生变化时，即使照片版本和
SHA-256 不变，服务器仍会返回该人物的 `upsert`，确保背景信息同步到设备；旧设备未上报这些字段时会
兼容性地触发一次 `upsert`。

消息中的 `agentId` 只描述设备缓存，不能作为授权身份。xiaozhi-server 使用已认证连接中的 `device_id`，
manager-api 再从 `ai_device.agent_id` 得到当前权威智能体。

版本相同返回：

```json
{
  "type":"face_profiles",
  "event":"unchanged",
  "protocolVersion":2,
  "agentId":"agent-a",
  "releaseVersion":12
}
```

此路径不读取图片文件，也不传完整清单。

版本不同先返回差异计划：

```json
{
  "type":"face_profiles",
  "event":"sync_plan",
  "protocolVersion":2,
  "agentId":"agent-a",
  "releaseVersion":13,
  "upserts":[
    {
      "personId":"person-2",
      "name":"访客二",
      "description":"画电路板的",
      "faceVersion":4,
      "faceSha256":"64位SHA-256",
      "contentType":"image/jpeg",
      "recognitionAction":"NONE"
    }
  ],
  "deletes":["person-3"]
}
```

服务器只为 `upserts` 发送图片：

```json
{
  "type":"face_profiles",
  "event":"upsert",
  "protocolVersion":2,
      "personId":"person-2",
      "assetId":"asset-4",
      "description":"画电路板的",
      "faceVersion":4,
  "faceSha256":"64位SHA-256",
  "imageBase64":"..."
}
```

全部图片准备成功后才会发送计划和内容，最后发送：

```json
{
  "type":"face_profiles",
  "event":"complete",
  "protocolVersion":2,
  "agentId":"agent-a",
  "releaseVersion":13,
  "upsertCount":1,
  "deleteCount":1
}
```

设备必须先写临时文件并验证 SHA-256，在收到 `complete` 后原子应用 upsert/delete，最后持久化
`releaseVersion=13`。断线、校验失败或 `sync_failed` 时不得推进本地版本，也不得提前删除旧文件。应用成功可回报：

```json
{"type":"face_profiles","event":"applied","releaseVersion":13}
```

### 在线发布提示

显式发布后，manager-api 通过受共享密钥保护的内部 HTTP 端点通知 xiaozhi-server。在线 v2 设备收到：

```json
{"type":"face_profiles","event":"sync_available","protocolVersion":2,"agentId":"agent-a"}
```

它只是版本检查提示，不包含照片。设备随后发 `sync_request`；离线设备下次连接时完成同样协商。

## v1 兼容边界

只声明 `face_profiles=true` 的旧设备仍会在一次 WebSocket 连接中收到 manifest、全量 upsert 和 complete。
这是固件迁移兼容路径，不是新设备缓存协议。新固件必须使用 v2，避免每次连接重复传输全部图片。

## 识别与接待授权

设备本地识别成功后仍上报稳定 `personId`、事件 ID 和置信度，不上传识别图片。服务端按以下事实授权：

```text
已认证 WebSocket device_id
  -> ai_device.agent_id
  -> ai_person_agent_binding.published_active=1
```

单人识别动作读取已发布快照，未发布的欢迎语不会提前生效。`reception_v1` 查询和到访事件使用同一智能体
发布授权，不再要求人物直接绑定具体设备。人物、智能体、设备和接待数据仍必须属于同一工作空间。

## 服务端识别预留

`GET /resource/person-recognition/capability` 固定返回 `supported=false` 和 `DEVICE_LOCAL_ONLY`。
`POST /resource/person-recognition/server` 当前不接收、不读取、不保存图片，也不调用人脸模型。未来启用前仍需
单独完成生物特征告知、保存周期、删除流程、特征加密、限流、阈值评测和真实模型验证。

## 人物画像问答扩展边界

后续可以在新增或编辑用户信息时，让运营人员选择一组问题并由用户自愿回答，用于形成更立体的人物画像。
当前版本只保留独立扩展边界，尚未实现问卷模板、回答记录、画像生成、提示词注入或设备采集协议，也不得
用人物“介绍”字段伪装成已上线的问答流程。正式实现前需单独定义授权与撤回、回答来源、敏感字段、留存
期限、版本审计、工作空间权限，以及画像进入人脸同步、声纹识别或 LLM 上下文时的最小披露规则。

## 验证边界

自动测试覆盖上传不发布、显式发布、智能体快照授权、版本相同不读图片、增量 upsert/delete、在线发布提示、
识别事件幂等、接待上下文，以及人物—声纹关联的工作空间校验、一对一冲突、解除关联和删除保护。仍需
目标固件验证 Flash 原子替换、断电恢复、缓存空间淘汰、SHA-256 校验和真实设备从 v1 升级到 v2 的迁移
行为；人物画像问答仍未进入验证范围，因为当前没有实现该能力。

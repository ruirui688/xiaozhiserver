---
status: canonical
owner: manager-api
last_verified: 2026-08-26
source_paths:
  - main/manager-api/src/main/java/xiaozhi/modules/person
  - main/manager-api/src/main/resources/db/changelog/202608261130.sql
  - main/xiaozhi-server/core/device_face_profiles.py
  - main/manager-web/src/views/PersonProfileManagement.vue
---

# 人物资源、智能体人脸库与设备增量同步

资源中心中的人物是工作空间业务资源，不是后台登录账号。人物资料、照片资源、智能体发布和设备本地缓存
是四层独立事实：上传照片不等于发布，绑定智能体不等于选择设备，设备连接不等于重新下载完整人脸库。

## 数据边界

| 表/路径 | 权威事实 |
| --- | --- |
| `ai_person_profile` | 人物当前管理草稿、基础资料和当前照片资源指针 |
| `ai_person_face_asset` | 每次上传生成的不可变归一化 JPEG 版本 |
| `ai_person_agent_binding` | 人物与智能体的期望绑定、最近发布快照及识别动作 |
| `ai_agent_face_release` | 每个智能体单调递增的人脸库发布版本 |
| `ai_person_recognition_event` | 设备本地识别事件审计 |
| `/uploadfile/person-faces/` | 归一化图片运行文件，不进入 Git |

`ai_person_device_binding` 是 v1 历史兼容表。迁移会把其设备的当前 `agent_id` 合并成
`ai_person_agent_binding`，新管理 API、v2 同步和接待授权不再写入或依赖人物—设备直绑关系。

照片上传同时校验扩展名、MIME、大小、实际图片内容和像素数量，再将最长边限制为 640 像素并转为 JPEG。
数据库和 API 不保存或返回服务端人脸特征向量。每次上传创建新资源文件和新资产行；旧已发布资产不能被
覆盖或立即删除，因为其他智能体可能仍在引用它。

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
    {"personId":"person-1","faceVersion":3,"faceSha256":"64位SHA-256"}
  ]
}
```

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

## 验证边界

自动测试覆盖上传不发布、显式发布、智能体快照授权、版本相同不读图片、增量 upsert/delete、在线发布提示、
识别事件幂等和接待上下文。仍需目标固件验证 Flash 原子替换、断电恢复、缓存空间淘汰、SHA-256 校验和
真实设备从 v1 升级到 v2 的迁移行为。

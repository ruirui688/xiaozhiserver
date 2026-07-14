---
status: canonical
owner: voiceprint
last_verified: 2026-07-14
source_paths:
  - main/xiaozhi-server/core/utils/voiceprint_provider.py
  - main/xiaozhi-server/core/handle/receiveAudioHandle.py
  - main/xiaozhi-server/core/utils/dialogue.py
  - main/manager-api/src/main/java/xiaozhi/modules/agent/voiceprint/
  - main/manager-api/src/main/java/xiaozhi/modules/agent/service/impl/AgentVoicePrintServiceImpl.java
---

# 外置声纹识别服务接入

本项目使用独立部署的 `voiceprint-api` 完成声纹注册、识别和删除。声纹模型不运行在小智主服务器上，避免和 ASR、LLM、TTS 抢占 CPU 与内存。

当前要求：

- `voiceprint-api` 版本至少为 `0.1.0`，接口必须支持必填 `user_id`。
- 小智使用智能体 ID 作为远程 `user_id`，不同智能体的声纹完全隔离。
- 每个智能体最多绑定 5 个声纹；manager-api 和远程服务都会校验。
- API Token 只能通过 `Authorization: Bearer ...` 传输，禁止放在 URL 查询参数中。
- 生产环境使用 HTTPS，并把声纹服务限制为仅允许小智服务器访问。

## 1. 完整接口文档

Swagger：

```text
http://声纹服务器IP:8005/voiceprint/docs
```

OpenAPI JSON：

```text
http://声纹服务器IP:8005/voiceprint/openapi.json
```

声纹项目内的文字文档：

```text
docs/reference/api.md
```

主要接口：

| 用途 | 接口 |
| --- | --- |
| 就绪检查 | `GET /voiceprint/health` |
| 存活检查 | `GET /voiceprint/health/live` |
| 注册或更新 | `POST /voiceprint/register` |
| 识别 | `POST /voiceprint/identify` |
| 删除 | `DELETE /voiceprint/{speaker_id}?user_id=...` |

## 2. 部署拓扑

```text
ESP32
  -> xiaozhi-server
       -> HTTPS /voiceprint/identify
            -> 独立 voiceprint-api
                 -> 独立或同机 MySQL

manager-web / manager-mobile
  -> manager-api
       -> HTTPS /voiceprint/register|delete|health
```

浏览器、微信小程序和 ESP32 不直接持有声纹服务 Token。所有远程请求都由可信服务端发起。

按本项目安全策略，后台参数中的声纹地址不能解析到私有、回环、链路本地或云元数据地址。跨服务器部署应使用可验证的公网 HTTPS 域名，并通过安全组或防火墙只允许小智服务器出口 IP 访问。

## 3. 全模块部署配置

先部署并验证 `voiceprint-api`，然后在智控台完成以下配置。

### 3.1 开启功能入口

进入“参数字典 → 系统功能配置”，启用“声纹识别”。

### 3.2 配置服务地址

在“参数管理”中设置：

```text
server.voice_print = https://voiceprint.example.com:8005
```

也可以填写标准 API 根路径：

```text
https://voiceprint.example.com:8005/voiceprint
```

不要填写：

```text
https://voiceprint.example.com:8005/voiceprint/health?key=真实Token
```

旧版带 `?key=` 的地址仍可临时读取，但保存新配置时应完成拆分。

### 3.3 配置 Bearer Token

设置：

```text
server.voiceprint_token = 声纹服务配置中的 server.authorization
```

参数页面会按敏感字段遮罩显示。Token 不得写入 Git、文档、截图、日志或 URL。

### 3.4 配置相似度阈值

```text
server.voiceprint_similarity_threshold = 0.4
```

值越高越严格。修改后应使用真实设备分别验证本人、相似声音和陌生人，不能只依赖接口健康检查。

## 4. 智能体绑定规则

在智能体的“声纹识别”页面，从该智能体自己的聊天音频中选择 2 到 5 段 WAV 注册样本，填写姓名和描述后保存。

服务端规则：

1. 创建前确认每段音频都属于当前智能体。
2. 当前智能体已有 5 条时直接拒绝第 6 条。
3. 每次注册或替换样本必须提交 2 到 5 段样本；manager-api 要求每段 WAV 可解析且时长为 3 到 30 秒。
4. 使用 `user_id=agentId` 和多段 `files` 调用远程注册接口，由 voiceprint-api 聚合多段 embedding。
5. `speaker_id` 使用本地声纹记录 UUID。
6. 同一智能体内先做重复声纹检测，避免同一个人重复占用名额。
7. 删除时同时删除本地记录和远程特征；远程失败会回滚本地删除，避免孤儿声纹占用额度。

Web 和 Mobile 页面均显示 `已绑定数/5`。前端限制只用于交互提示，最终约束由 manager-api 和 voiceprint-api 共同保证。

## 5. 实时识别链路

xiaozhi-server 假设一句话内只有一个说话人。音频到达后，完整语音持续进入 ASR；VAD 检测到句末后，服务端把当前整段语音转换成 16k 单声道 WAV，并与 ASR 并发调用远程 `/voiceprint/identify`。这与官方上游声纹识别链路保持一致，不再截取前 N 秒有效人声。

```text
设备持续上传音频
  -> VAD 检测句末
  ├─ 完整音频进入 ASR
  └─ 当前整段语音转 WAV 后调用 /voiceprint/identify

ASR 与声纹识别完成
  -> 超时或失败则以无身份继续进入语义中心和 LLM
```

每句话最多产生一次声纹请求。历史参数 `server.voiceprint_sample_seconds` 保留在已发布数据库迁移中，但 manager-api 不再下发该参数，Python 运行时也不再读取它。

如果智能体没有绑定任何声纹，manager-api 不下发 `voiceprint` 私有配置，Python 不创建声纹 provider，也不会调用远程服务。

manager-api 下发的声纹私有配置包含：

```yaml
voiceprint:
  url: https://voiceprint.example.com:8005/voiceprint
  token: "***"
  user_id: agent-id
  timeout_seconds: 5
  similarity_threshold: 0.4
  speakers:
    - speaker_id: voiceprint-record-id
      name: 用户显示名
      description: 用户描述
      user_id: agent-id
```

Token 会在 Python 配置日志中脱敏。`timeout_seconds` 是远程请求自身的保护上限。`sample_seconds` 和 `result_wait_ms` 是旧版本地采样预取链路参数，新版整句识别链路不再使用。

识别出的身份在每个成功识别轮次都进入 LLM：当前 user message 保留结构化 `speaker` / `speaker_id`，动态 system 上下文同步写入当前说话人及其描述。这样“你知道我是谁吗”“刚刚是谁在说话”等问题不再依赖旧对话历史。提示词同时明确身份只是内部事实，模型不得主动播报声纹过程、置信度或标签，也不得因为每轮收到身份信息就机械地重复称呼姓名。

声纹身份还会以结构化字段进入聊天记录和长期记忆事件：

```json
{
  "speaker": "用户显示名",
  "speaker_id": "voiceprint-record-id",
  "speaker_score": 0.91,
  "content": "用户说的话"
}
```

- 聊天记录页面显示声纹姓名；旧记录中只有 `speaker` 时仍可显示。
- `speaker_id` 是长期记忆的稳定关联键，显示姓名只用于界面展示。
- 新抽取的事实会在 `payload` 中保存 `speaker_id` 和姓名快照。
- 当前说话人只召回自己的专属记忆和没有身份归属的共享记忆，不会读取其他声纹用户的专属事实。
- 识别失败或陌生人本轮没有可信 `speaker_id`，因此只使用共享记忆。

聊天记录下载会生成 ZIP 包，不再只是纯文本。包内包含 `history.txt`、`metadata.json` 和有权限导出的 `audio/` WAV 文件；`metadata.json` 保留原始 `content`、解析后的声纹识别结果、音频文件路径、音频字节数、WAV 时长和相邻消息时间差。当前历史表没有持久化完整 ASR/LLM/TTS 分段延时链路，因此下载包只能给出消息时间差和音频时长，并在 metadata 中明确标注该限制。

## 6. 历史数据升级

`voiceprint-api` 从旧版本升级到 `0.1.0` 后，历史远程数据会归入：

```text
user_id = default
```

本项目迁移会给现有 `ai_agent_voice_print` 记录写入 `remote_user_id=default`。运行时按每条记录的远程作用域分组识别，因此历史声纹仍可继续使用。

- 新增声纹：直接写入 `user_id=agentId`。
- 重新选择音频并保存旧声纹：先注册到 `user_id=agentId`，再删除旧 `default` 特征，完成单条迁移。
- 只修改姓名或描述：不重复调用远程模型，保留原远程作用域。

升级前应分别备份小智 MySQL 和声纹服务 MySQL。

## 7. 单模块 Python 配置

只运行 `xiaozhi-server` 时，在不提交 Git 的 `main/xiaozhi-server/data/.config.yaml` 中配置：

```yaml
voiceprint:
  url: https://voiceprint.example.com:8005
  token: 你的真实BearerToken
  user_id: local-agent-001
  similarity_threshold: 0.4
  timeout_seconds: 5
  speakers:
    - speaker_id: voice-1
      name: 示例用户
      description: 示例描述
      user_id: local-agent-001
```

单模块模式没有 manager-api 替你校验 5 条上限和资源所有权，运维人员必须自行保证同一 `user_id` 不超过 5 条，并通过声纹服务 API 注册对应 `speaker_id`。

## 8. 最小验证

就绪检查：

```bash
curl -H "Authorization: Bearer <token>" \
  "https://voiceprint.example.com:8005/voiceprint/health"
```

预期返回 `status=healthy`。随后至少验证：

1. 智能体 0 条时可新增。
2. 第 5 条可新增，第 6 条被拒绝。
3. 两个智能体可使用相同显示名，但远程数据互不可见。
4. 删除后可以重新新增。
5. 真实设备能识别本人，陌生人返回未知说话人。
6. 声纹服务器断开或超时时，ASR/LLM 对话仍能继续。
7. 日志中没有原始 Authorization、Token 或带密钥 URL。
8. 真实设备的完整单句语音会上传给声纹识别，长句不会被截断到前 N 秒。
9. 智能体未绑定声纹时，声纹服务没有识别请求。

不要把真实外部调用、长期服务启动或生产部署作为默认自动化验证；生产联调需在发布和备份后单独执行。

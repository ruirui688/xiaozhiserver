---
status: supporting
owner: xiaozhi-server
last_verified: 2026-09-15
source_paths:
  - main/xiaozhi-server/core/connection.py
  - main/xiaozhi-server/core/handle/audioFileHandler.py
---

# 设备端录音文件上传协议

## 适用场景

设备已经在本地完成 VAD，并得到一段完整 Opus 录音时，使用本协议上传。它与实时语音流协议独立：不要同时发送 `listen/start` 或 `listen/stop`。

## 发送顺序

```text
文本 JSON audio_file/start
  → 一个或多个 WebSocket Binary 消息（Opus 数据）
  → 文本 JSON audio_file/end
```

WebSocket 连接、认证和设备 hello 仍使用现有协议。每个文件必须在同一条连接内完成，消息不能交错属于不同文件。

## start 消息

```json
{
  "type": "audio_file",
  "state": "start",
  "request_id": "turn-20260915-0001",
  "format": "opus",
  "frames": 50,
  "frame_duration_ms": 60,
  "sample_rate": 16000,
  "timeout_ms": 5000
}
```

字段说明：

| 字段 | 必填 | 规则 |
|---|---|---|
| `type` | 是 | 固定为 `audio_file` |
| `state` | 是 | 固定为 `start` |
| `request_id` | 建议 | 本轮唯一字符串，用于设备日志关联 |
| `format` | 建议 | 当前固定为 `opus` |
| `frames` | 否 | 正整数；设备能可靠统计时填写 |
| `frame_duration_ms` | 否 | 单帧时长，通常为 60 |
| `sample_rate` | 否 | 通常为 16000，应与 hello 音频参数一致 |
| `timeout_ms` | 否 | 上传超时，默认 5000；不得设置过大值 |

非法 `frames` 不会拒绝 WebSocket 连接，但该次上传不会按帧数推断完成，设备仍应发送 `end`。

## Binary 消息

每条 Binary 消息承载一个或多个连续 Opus 帧的原始字节，不要 Base64、JSON 包装、WAV 头或 RTP 头。保持发送顺序；不要在文件中插入 `listen` 消息。

推荐每个 Binary 消息使用 1～8 个 Opus 帧。文件较大时分片发送，单个文件总大小不得超过 10 MB。

## end 消息

```json
{
  "type": "audio_file",
  "state": "end",
  "request_id": "turn-20260915-0001"
}
```

发送 `end` 前必须确保所有 Binary 消息已经按顺序写入 WebSocket。服务端收到 `end` 后一次性提交 ASR；设备不需要再发送 `listen/stop`。

## 超时、断线和重试

- 服务端默认在 5 秒内未结束时按已收到数据收尾；设备应记录本轮超时。
- `end` 后不要继续发送该文件的 Binary 数据；迟到数据会被丢弃。
- 连接断开视为本轮失败。重连后使用新的 `request_id`，重新发送完整文件。
- 重复 `start` 会被忽略；不要依赖重复消息完成重试。
- 文件为空、超过大小限制或格式错误时，服务端不会提交 ASR。

## 与实时流协议的区别

实时流：`listen/start`、逐帧 Opus、`listen/stop`，由设备显式控制边界。文件协议：`audio_file/start`、连续文件数据、`audio_file/end`，服务端只在文件结束后提交一次识别，不启用服务端 VAD。

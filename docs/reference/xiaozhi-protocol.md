# 小智 WebSocket 协议扩展（计划中）

## 独立录音文件上传

设备本地完成 VAD 后，可使用独立的 `audio_file` 协议上传完整录音。先发送 `audio_file/start` JSON，随后发送一个或多个二进制 Opus 数据块，最后发送 `audio_file/end` JSON。服务端在 end 后一次性提交 ASR。

该协议不进入 `listen` 状态机，也不启用服务端 VAD。现有 `listen/start`、音频帧、`listen/stop` 实时流协议保持不变，官方 ESP32 无需修改。

# 豆包 Seed TTS 2.0 配置指南

本项目的豆包 TTS 只保留一个入口：

| 智控台名称 | 上游模型 | `resource_id` | 运行时 adapter |
| --- | --- | --- | --- |
| 豆包语音合成模型 2.0 | Seed TTS 2.0 | `seed-tts-2.0` | `huoshan_double_stream` |

旧语音合成入口不再作为本项目有效入口。数据库迁移会删除这些历史模型配置和模板，避免后台继续误选。

## 1. 开通与凭据

1. 按火山文档接入 Seed TTS 2.0：
   `https://www.volcengine.com/docs/6561/2532486`
2. 在控制台获取 `API Key`，后台填写到 `api_key`。
3. 在已开通服务的音色列表中复制音色 `Voice_type`，填到 `speaker`。

不要把真实 `API Key` 写入 Git。真实配置只放后台数据库或本地私有配置。

## 2. 智控台字段

| 字段 | 推荐值 | 说明 |
| --- | --- | --- |
| `ws_url` | `wss://openspeech.bytedance.com/api/v3/tts/bidirection` | 固定双向流式地址 |
| `api_key` | 控制台 API Key | 作为 `X-Api-Key` 请求头发送 |
| `resource_id` | `seed-tts-2.0` | 固定值 |
| `speaker` | 控制台音色 `Voice_type` | 必须是 Seed TTS 2.0 可用音色 |
| `format` | `pcm` | 设备实时播报推荐 `pcm` |
| `enable_ws_reuse` | `true` | 降低首包延迟，但空闲连接会占并发 |

## 3. 默认配置示例

`main/xiaozhi-server/config.yaml` 只保留一个脱敏示例：

```yaml
TTS:
  DoubaoSeedTTS2Stream:
    type: huoshan_double_stream
    ws_url: wss://openspeech.bytedance.com/api/v3/tts/bidirection
    api_key: 你的火山引擎语音合成服务APIKey
    resource_id: seed-tts-2.0
    speaker: zh_female_sophie_uranus_bigtts
    format: pcm
    speech_rate: 0
    loudness_rate: 0
    pitch: 0
    enable_ws_reuse: True
```

## 4. 验证

后台模型测试必须真实生成可播放音频，并显示：

- 测试文本
- `resource_id=seed-tts-2.0`
- `speaker`
- 音频参数
- 首音频耗时
- 音频预览

如果测试详情仍显示其他 TTS 资源或旧 TTS 入口，说明服务器还没有执行最新迁移或前端仍是旧版本。

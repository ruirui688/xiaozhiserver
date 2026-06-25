# 豆包 TTS 配置指南

本项目只保留两个豆包语音合成入口，全部走 `huoshan_double_stream` 双向流式 TTS：

| 智控台名称 | 火山服务 | `resource_id` | 适用场景 |
| --- | --- | --- | --- |
| 豆包语音合成大模型 | `10007` | `volc.service_type.10029` | 语音合成大模型、音色克隆、混音 |
| 豆包语音合成模型 2.0 | `10035` | `seed-tts-2.0` | Seed TTS 2.0 新模型 |

旧 `302AI`、机智云、火山 AI Gateway TTS 不再作为本项目有效豆包 TTS 入口，历史预置配置会由数据库迁移清理。旧 `DoubaoTTS` HTTP 已归并为“豆包语音合成大模型”，新增配置不要选择废弃入口。

## 1. 开通与凭据

1. 访问火山语音控制台，按实际需要开通：
   - 语音合成大模型：`https://console.volcengine.com/speech/service/10007`
   - 豆包语音合成模型 2.0：`https://console.volcengine.com/speech/service/10035`
2. 优先使用控制台“快捷 API 接入”的 `API Key`。
3. 如果账号仍使用旧版鉴权，可以填写 `App ID` + `Access Token`；已经填写 `API Key` 时，旧版字段留空。
4. 在对应服务的音色列表中复制音色 `Voice_type`，填到 `speaker`。

不要把真实 `API Key`、`Access Token`、AppID 写入 Git。真实配置只放后台数据库或本地私有配置。

## 2. 智控台字段

### 通用字段

| 字段 | 推荐值 | 说明 |
| --- | --- | --- |
| `ws_url` | `wss://openspeech.bytedance.com/api/v3/tts/bidirection` | 固定双向流式地址 |
| `api_key` | 控制台 API Key | 优先鉴权方式 |
| `appid` / `access_token` | 留空或旧版凭据 | 仅旧版鉴权兼容 |
| `resource_id` | 见上表 | 不要混填 |
| `speaker` | 控制台音色 `Voice_type` | 音色编码，不是显示名称 |
| `format` | `pcm` | 设备实时播报推荐 `pcm`，试听文件可用 `wav`/`mp3` |
| `enable_ws_reuse` | `true` | 降低首包延迟，但空闲连接会占并发 |

### 声音效果字段

| 字段 | 范围 | 默认 | 说明 |
| --- | --- | --- | --- |
| `speech_rate` | `-50` 到 `100` | `0` | 语速 |
| `loudness_rate` | `-50` 到 `100` | `0` | 音量 |
| `pitch` | `-12` 到 `12` | `0` | 音高 |
| `emotion` | `neutral` / `happy` / `sad` / `angry` / `fearful` / `disgusted` / `surprised` | 留空 | 仅部分音色支持 |
| `emotion_scale` | `1` 到 `5` | 留空 | 情感强度，仅 `emotion` 生效时使用 |

常用字段会由运行时自动归并到火山请求的 `audio_params` 和 `additions`。除非需要厂商新增参数，不必手写 JSON。

### 高级字段

| 字段 | 用途 |
| --- | --- |
| `audio_params` | 透传火山 `audio_params`，普通参数优先用上方独立字段 |
| `additions` | 透传火山 `additions`，普通音高优先用 `pitch` |
| `mix_speaker` | 仅语音合成大模型混音场景使用，普通音色不要填 |

## 3. 默认配置示例

`main/xiaozhi-server/config.yaml` 已保留两个脱敏示例：

```yaml
TTS:
  DoubaoBigTTSStream:
    type: huoshan_double_stream
    ws_url: wss://openspeech.bytedance.com/api/v3/tts/bidirection
    api_key: 你的火山引擎语音合成服务APIKey
    appid:
    access_token:
    resource_id: volc.service_type.10029
    speaker: zh_female_wanwanxiaohe_moon_bigtts
    format: pcm
    speech_rate: 0
    loudness_rate: 0
    pitch: 0
    enable_ws_reuse: True

  DoubaoSeedTTS2Stream:
    type: huoshan_double_stream
    ws_url: wss://openspeech.bytedance.com/api/v3/tts/bidirection
    api_key: 你的火山引擎语音合成服务APIKey
    appid:
    access_token:
    resource_id: seed-tts-2.0
    speaker: zh_female_xiaohe_uranus_bigtts
    format: pcm
    speech_rate: 0
    loudness_rate: 0
    pitch: 0
    enable_ws_reuse: True
```

## 4. 音色克隆

音色克隆仍使用“豆包语音合成大模型”入口：

1. 在火山服务 `10007` 开通语音合成大模型和声音复刻能力。
2. 在控制台获取克隆音色的 `Voice_type` 或音色资源 ID。
3. 在智控台音色资源中绑定该音色。
4. 智能体 TTS 选择“豆包语音合成大模型”，音色选择对应克隆音色。

## 5. 验证

后台模型测试必须真实生成可播放音频，并显示：

- 测试文本
- `resource_id`
- `speaker`
- 音频参数
- 首音频耗时
- 音频预览

如果只看到配置校验成功，没有音频预览，不算豆包 TTS 验证通过。

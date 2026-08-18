# VADNet 与小智服务端 VAD：实时交互低延迟方案

## 结论

实时对话采用现有服务端 Silero VAD，并把句尾静音从 700 ms 调整为 200 ms；豆包录音 ASR 2.0 切换为项目已有的豆包流式 ASR 2.0。流式 ASR 的停止边界仍由本地 Silero VAD 决定：发送完当前静音尾帧后只发送一次 `LAST`，再等待云端最终 `definite` 结果。

RMS/SNR 门控目前是 `shadow` 观察模式，只记录“它会怎样判断”，不会改变最终的有声/静音结果。不能把它理解为“更好的门控已经生效”；当前真正生效的是 Silero 概率、双阈值、连续帧确认和 `min_silence_duration_ms`。

## 能力与职责对比

| 项目 | 乐鑫 VADNet | 当前小智服务端 Silero VAD |
| --- | --- | --- |
| 运行位置 | ESP 设备端语音前端 | xiaozhi-server 音频上行链路 |
| 核心职责 | 判断设备采集帧是否包含人声 | 判断上行帧是否包含人声，并形成服务端句首/句尾事件 |
| 模型输出用途 | 可用于设备端唤醒、降噪或录音状态控制 | 直接控制 ASR 分段、流式 ASR 的 `LAST` 以及 AEC 播报打断 |
| 句尾延迟 | 取决于设备侧状态机和连续静音策略 | 明确由 `min_silence_duration_ms` 贡献主要固定等待 |
| 当前项目中的权威性 | 未接管服务端 ASR 句尾 | 是服务端 ASR 句尾的权威判断 |

两者不是简单的“哪个模型更好”。VADNet 更靠近麦克风，适合设备端快速感知；Silero 位于服务端，能直接控制服务端 ASR 生命周期。当前交互延迟问题来自服务端句尾策略被配置成 700 ms，而不是因为缺少另一个 VAD 模型。

## 当前实际判定逻辑

```text
设备上传 Opus 音频
  -> 服务端解码并送入 Silero
  -> 概率超过句首阈值 + 连续帧确认：进入说话状态
  -> 概率低于句尾阈值：累计静音
  -> 连续静音达到 200 ms：client_voice_stop = true
  -> 豆包流式 ASR 发送当前尾帧
  -> 仅发送一次 LAST
  -> 等待最终 definite 文本
  -> LLM/TTS 继续下游响应
```

其中：

- `threshold: 0.5` 是语音开始阈值。
- `threshold_low: 0.3` 是语音结束方向的滞回阈值。
- `min_silence_duration_ms: 200` 是确认用户说完所需的连续静音。
- `speech_gate.mode: shadow` 下的 RMS/SNR、噪声底估计和 `tail_grace_ms` 不参与最终裁决。
- AEC 设备的播报打断看的是句首确认，不需要等待 200 ms 句尾，因此不要把“打断速度”和“句尾完整性”混为同一个参数。

## 为什么 700 ms 会明显拖慢

非流式录音 ASR 必须等 VAD 判停后才提交整段音频；流式 ASR 虽然边说边上传，但本项目必须等本地 VAD 判停后才发送 `LAST`。因此 700 ms 都会直接叠加在“最后一个字到 ASR 最终结果”之间。

改为 200 ms 后，理论上去掉约 500 ms 固定尾部等待。它不会消除云端返回最终 `definite`、LLM 首 token 或 TTS 首音频的耗时，但能消除当前最确定的一段人为延迟。

## 本次源码设置

新增 Liquibase 迁移 `202608181500.sql`：

1. 把标准模型参数表中遗留的 `VAD_SileroVAD.min_silence_duration_ms=700` 修正为 `200`；该表是运行时 `typed_config` 的权威来源。
2. 同步仍保留旧值的兼容 `config_json` 和系统默认参数，但不覆盖管理员已主动设置的其他数值。
3. 若豆包流式 ASR 2.0 已启用且有 API Key，则把仍使用豆包录音 ASR 2.0 的智能体迁移到流式 ASR；流式模型缺 Key 时可从同账号录音模型复制，已有流式 Key 不覆盖。
4. 若流式模型或凭据不可用，则保留原 ASR 绑定，避免迁移后语音识别完全不可用。

## 上线后的验收

启动 manager-api 执行迁移后，新连接的下发配置应满足：

```yaml
selected_vad: VAD_SileroVAD
vad:
  threshold: 0.5
  threshold_low: 0.3
  min_silence_duration_ms: 200
  speech_gate:
    mode: shadow

selected_asr: ASR_DoubaoStreamASRV2
asr:
  type: doubao_stream
```

日志验收应同时确认：

- 用户最后一个字后约 200 ms 连续静音触发 `client_voice_stop`。
- 同一轮流式 ASR 只发送一次 `LAST`。
- `LAST` 之后收到非空最终 `definite` 文本。
- 短停顿没有造成频繁截句；若出现截句，应先用真实样本评估 250-350 ms，而不是恢复固定 700 ms。
- `shadow` 日志只能用于比较 RMS/SNR 门控与 Silero 的分歧，不能当作已参与生产决策的证据。

## 参考位置

- `main/xiaozhi-server/core/providers/vad/silero.py`
- `main/xiaozhi-server/core/providers/asr/doubao_stream.py`
- `main/xiaozhi-server/config.yaml`
- `main/manager-api/src/main/java/xiaozhi/modules/config/service/impl/ConfigServiceImpl.java`
- `docs/local-customizations.md` 第 25 节
- 小智官方仓库核对提交：`ae3f970c92db820bd8d29e7ccafc4e7a00d0b964`

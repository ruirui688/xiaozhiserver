# 语音管道延迟分析

> 分析日期：2026-06-25 | 分支：feature/model-performance-benchmark | 基于实际日志分析

---

## 案例：5 秒延迟的完整拆解

### 原始日志

```
[用户]-[2026-06-25 17:14:03]>>: 你开心吗
[智体]-[2026-06-25 17:14:08]<<: 🙂 当然开心啦，遇到愿意跟我聊天的人，我就特别来劲儿！
       而且今天天气也不错，傍晚的风轻轻吹着，感觉整个镇江都温柔了起来。你呢，心情怎么样？
```

- 用户消息时间戳：ASR 识别完成、返回文本 `"你开心吗"` 的时刻
- 智能体时间戳：TTS 语音开始播放的时刻
- 间隔：**5 秒**

---

## 管道架构回顾

```
Opus 音频帧 → VAD 分段 → ASR 文字识别 → SemanticControl 路由
  → Intent/LLM 对话 + 工具调用 → TTS 合成 → Opus 音频回传设备
```

---

## 5 秒逐阶段拆解

```
17:14:03.000  ASR 返回文本 "你开心吗"
    │
    ├─ [阶段1] ~50ms   startToChat() → handle_user_intent()
    │                  → SemanticControl 确定性路由
    │
    │  代码路径：
    │    receiveAudioHandle.py:43 startToChat()
    │    → intentHandler.py handle_user_intent()
    │    → semantic_center.py 路由判断
    │    → connection.py:1024 chat()
    │
    │  "你开心吗" 不匹配任何快速路由关键词 (STOP_ALL/CONTINUE/GAME_EXIT/...)
    │  以 confidence 0.5 回退为 chat.normal，进入 LLM 流程
    │
    ├─ [阶段2] ~150ms   Memory 向量检索（同步阻塞）
    │
    │  代码：connection.py:1094-1097
    │    future = asyncio.run_coroutine_threadsafe(
    │        self.memory.query_memory(query), self.loop
    │    )
    │    memory_str = future.result()  # ← 同步等待，阻塞当前线程
    │
    │  查询向量数据库，检索与 "你开心吗" 相关的历史记忆片段
    │
    ├─ [阶段3] ~200ms   构建 LLM 对话上下文
    │
    │  代码：connection.py:1099-1105
    │    - self.dialogue.get_llm_dialogue_with_memory(memory_str, ...)
    │      组装完整 messages 数组：
    │        system: 角色设定 + 工具定义 + 回复规则 (~2000+ tokens)
    │        user: 历史对话
    │        user: 记忆片段
    │        user: "你开心吗"
    │    - self._with_freeform_game_prompt(...)   注入游戏提示词
    │    - self._with_music_library_context(...)  注入音乐曲库列表
    │
    │  工具定义列表组装（connection.py:1081）
    │    functions = list(self.func_handler.get_functions())
    │    包含 self_audio_speaker_set_volume, play_music,
    │      self_get_device_status, hass_* 等所有注册的 MCP 工具
    │
    ├─ [阶段4] ~2500ms  ★ LLM 推理 + 流式文本生成 ★ 【最大瓶颈】
    │
    │  代码：connection.py:1109-1111
    │    self.llm.response_with_functions(
    │        self.session_id, llm_dialogue, functions=functions
    │    )
    │
    │  模型需要生成的文本（52 个中文字符 + emoji）：
    │    "🙂 当然开心啦，遇到愿意跟我聊天的人，我就特别来劲儿！
    │     而且今天天气也不错，傍晚的风轻轻吹着，感觉整个镇江都温柔了起来。
    │     你呢，心情怎么样？"
    │
    │  时间消耗来源：
    │    1. 首 token 延迟 (TTFT)：~500-800ms
    │       - 模型处理完整 system prompt (包含工具定义) 的时间
    │       - system prompt 越长，首 token 越慢
    │    2. 逐 token 生成：~25-40ms/token × ~80 tokens ≈ 2000-3200ms
    │       - 生成长文本回复，涉及情感表达、场景叙述
    │       - 每个中文字符 ≈ 1.5-2 tokens
    │
    │  流式推送：每生成一段文本，立即推送到 TTS 队列
    │    connection.py:1205-1212
    │      self.tts.tts_text_queue.put(
    │          TTSMessageDTO(sentence_type=MIDDLE, content=chunk)
    │      )
    │
    ├─ [阶段5] ~800ms   ★ TTS 首次语音合成 ★ 【第二大瓶颈】
    │
    │  代码路径：
    │    base.py:375 tts_text_priority_thread()
    │      → 收集 LLM 流式文本到 tts_text_buff
    │      → base.py:493 _get_segment_text()
    │          等待第一个标点符号出现（逗号、句号等）
    │          文本 "🙂 当然开心啦，" → 在逗号处切分
    │      → base.py:396 to_tts_stream("当然开心啦", ...)
    │      → 调用 TTS 厂商 API (阿里云/讯飞/火山引擎等)
    │      → 等待音频合成完成
    │      → opus 编码
    │      → tts_audio_queue 入队
    │      → base.py:418 _audio_play_priority_thread()
    │      → WebSocket 发送首帧音频到 ESP32 设备
    │
    │  关键延迟因素：
    │    - TTS 必须等到标点才切分语音段（不会在"当"字就合成）
    │    - API 调用 + 音频生成 + 编码 的串行耗时
    │    - 如果使用非流式 TTS（to_tts 模式），需等待完整音频文件生成
    │
    ├─ [阶段6] ~300ms   网络传输 + 设备解码缓冲
    │
    │    - WebSocket 帧传输到 ESP32
    │    - 设备端 Opus 解码缓冲
    │    - 扬声器播放启动
    │
17:14:08.000  设备扬声器开始播放语音
```

### 各阶段耗时占比

| 阶段 | 耗时 | 占比 | 可优化空间 |
|---|---|---|---|
| SemanticControl 路由 | ~50ms | 1% | 极小 |
| Memory 向量检索 | ~150ms | 3% | 中等（异步化） |
| 上下文组装 | ~200ms | 4% | 中等（缓存/瘦身 prompt） |
| **LLM 推理生成** | **~2500ms** | **50%** | **大（模型选择/prompt 精简）** |
| **TTS 合成** | **~800ms** | **16%** | 中等（流式 TTS/换厂商） |
| 网络 + 设备缓冲 | ~300ms | 6% | 小 |
| 管线调度开销 | ~1000ms | 20% | 中等（减少串行等待） |
| **总计** | **~5000ms** | **100%** | |

---

## 管线调度开销详解（~1000ms 的隐藏成本）

这 1000ms 不是某个单一阶段，而是散布在**阶段之间的串行等待**：

```
线程切换开销：
  asr_audio_queue.get()         → 等待 audio 入队      ~50ms
  handleAudioMessage()          → VAD is_vad() 推理     ~10ms/帧
  asr.receive_audio()           → client_voice_stop 判断 ~100ms
  handle_voice_stop()           → PCM 解码 + WAV 转换   ~50ms

同步等待点 (阻塞)：
  future.result()               → Memory 查询阻塞       ~150ms
  asyncio.run()                 → text_to_speak() 阻塞  ~800ms (已计入 TTS)
  asyncio.run()                 → emotion detection      ~50ms

队列缓冲：
  asr_audio_queue               → 音频帧积压             ~50ms
  tts_text_queue                → 文本块等待消费         ~30ms
  tts_audio_queue               → 音频帧积压             ~50ms

Python GIL：
  多线程下的 GIL 竞争                                   ~50-100ms
```

---

## 对比：为什么"你在干什么？"只需 2 秒

```
[用户] 17:01:23 → "你在干什么？"
[智体] 17:01:25 → "刚帮你把音量调到30%..."    ← 仅 2 秒！
```

这条回复**绕过了 LLM 推理**：

1. 上一条是工具调用 `self_audio_speaker_set_volume({"volume": 30})`
2. MCP 执行器在 `mcp_executor.py` 的 `FAST_RESPONSE_CONTROL_TOOLS` 中返回了**预置文本**：`"音量已设置为 30%。"`
3. LLM 收到工具返回结果后，只需生成极短的过渡语句包裹这个结果
4. 实际 LLM 生成量：约 15 tokens vs "你开心吗"的 80 tokens

**延迟差异的根源就是 LLM 生成文本长度不同。**

---

## 各阶段的文件定位

| 阶段 | 关键文件 | 行号 |
|---|---|---|
| 音频入队 + VAD | `core/connection.py` | 385-398 |
| VAD 检测 | `core/providers/vad/silero.py` | 58-121 |
| ASR 工作线程 | `core/providers/asr/base.py` | 44-59 |
| 语音停止处理 | `core/providers/asr/base.py` | 83-174 |
| ASR → Chat 桥接 | `core/handle/receiveAudioHandle.py` | 43-80 |
| 意图路由 | `core/handle/intentHandler.py` | - |
| 语义路由 | `core/semantic/semantic_center.py` | - |
| LLM Chat 主流程 | `core/connection.py` | 1024-1405 |
| Memory 查询 | `core/connection.py` | 1093-1097 |
| 上下文组装 | `core/connection.py` | 1099-1105 |
| LLM 流式调用 | `core/connection.py` | 1107-1118 |
| LLM 流式处理 → TTS | `core/connection.py` | 1131-1212 |
| TTS 文本分段 | `core/providers/tts/base.py` | 493-530 |
| TTS 合成 + 编码 | `core/providers/tts/base.py` | 130-198 |
| TTS 音频播放 | `core/providers/tts/base.py` | 418-469 |

---

## LLM 首 Token 延迟的影响因素

首 token 延迟 (TTFT, Time To First Token) 是 LLM 阶段的最大头，受以下因素影响：

### 1. System Prompt 长度

system prompt 包含以下所有内容（估计 **2000-4000 tokens**）：

| 组成部分 | 文件 | 说明 |
|---|---|---|
| 角色设定 + 回复规则 | `system_prompt.py` | 角色背景、语气、emotion 选择 |
| 工具使用指导 | `system_prompt.py:75-76` | 何时用 volume_up/down vs set_volume |
| LLM 工具定义 JSON | `func_handler.get_functions()` | 所有注册的 MCP 工具 schema |
| 意图 LLM 示例 | `intent_llm.py:33-122` | few-shot 示例 |
| 音乐曲库列表 | `connection.py:1435-1440` | `<music_library>` 上下文块 |
| 游戏提示词 | `connection.py:1038-1043` | 纯语言游戏上下文 |
| 记忆片段 | memory 查询结果 | 历史对话摘要 |
| 历史对话 | dialogue 中最近 N 轮 | 多轮上下文 |
| direct_answer 工具 | `connection.py:1085` | 防止小模型误调真实工具 |

**每次 LLM 调用都要处理这全部的 prompt，这是 TTFT 的主要消耗。**

### 2. 工具定义数量

`func_handler.get_functions()` 返回所有设备端注册的 MCP 工具 schema（JSON Schema 格式），每个工具 schema 大约 200-500 tokens。如果 ESP32 注册了 10+ 个工具，仅工具定义就占 2000-5000 tokens。

### 3. LLM 模型选择

不同模型的 TTFT 和生成速度差异很大。本地部署 vs 云端 API 的延迟特征也不同。

---

## TTS 延迟详解

### 流式 vs 非流式

```python
# base.py:130 to_tts_stream() — 流式模式
# 逐段合成，边合成边发送

# base.py:200 to_tts() — 非流式模式
# 等待完整音频生成后再发送
```

### 首段等待

`base.py:493 _get_segment_text()` 决定何时切分一个 TTS 段：

```
LLM 流式输出: "🙂" → " " → "当" → "然" → "开" → "心" → "啦" → "，"
                                                              ↑
                                    只有到这里，才触发 TTS 合成第一个语音段
```

这个"等标点"机制意味着：
- 如果 LLM 先输出一个长句再出现标点，TTS 就要等很久
- 如果 LLM 先输出短句（如 `"嗯。"`），TTS 可以很快开始
- 可以通过调整 `first_sentence_punctuations` 来改变切分行为

### TTS 厂商延迟差异

不同厂商的合成速度不同（估计值）：

| 厂商 | 首段合成时间 | 说明 |
|---|---|---|
| 阿里云流式 | 200-500ms | 流式 API，边生成边返回 |
| 讯飞流式 | 300-600ms | 流式 WebSocket |
| 火山引擎 | 400-800ms | 双流式架构 |
| 腾讯 | 500-1000ms | 非流式，需完整生成 |
| PaddleSpeech | 1000-3000ms | 本地推理，取决于硬件 |

---

## 优化方向（不执行，仅记录）

### 高收益（预计减少 1-2 秒）

1. **精简 system prompt**：当前 prompt 包含大量角色扮演内容和工具定义。可以按场景动态裁剪 — 比如用户说"你开心吗"时不需要注入音乐库列表和游戏提示词。

2. **Memory 查询异步化**：`future.result()` 改为 `await`，与上下文组装并行执行。

3. **LLM 流式到 TTS 的粒度调优**：当前是等标点才切分。可以改为按固定字数（如每 8-10 字）切分，减少首段等待。

### 中收益（预计减少 0.5-1 秒）

4. **TTS 连接预热**：与 ASR 并行初始化 TTS 通道，减少首次合成的连接建立时间。

5. **工具定义懒加载**：不是每次 LLM 调用都传全部工具 schema，而是根据对话上下文动态裁剪。

6. **换更快的 TTS 厂商** 或使用本地 TTS 推理减少网络往返。

### 低收益（预计减少 0.2-0.5 秒）

7. **VAD 尾音时间调优**：`min_silence_duration_ms` 默认 1000ms，可降至 600-800ms（但可能增加截断风险）。

8. **减少 GIL 竞争**：将 CPU 密集型操作（Opus 编解码、VAD 推理）移到子进程。

---

## 实际延迟构成速查

```
用户说完最后一个字
    │
    ├─ 1000ms  VAD 尾音检测 (min_silence_duration_ms, 可配置)
    ├─  500ms  ASR API 调用 (speech_to_text)
    ├─   50ms  SemanticControl 路由
    ├─  150ms  Memory 查询 (同步阻塞)
    ├─  200ms  上下文组装 (system prompt + 工具定义)
    ├─ 2500ms  LLM 推理生成 (首 token + 逐 token 流式输出)
    ├─  800ms  TTS 首段合成 (等标点 + API 调用 + 编码)
    ├─  300ms  网络传输 + 设备缓冲
    │
    ▼ 用户听到回复
```

**总端到端延迟 ≈ 5.5 秒**（从用户说完到听到回复），其中：
- 用户在日志里看到的 5 秒 = ASR 完成 → TTS 播报（不含 VAD 尾音）
- 用户主观感知的完全延迟 ≈ 从说完到听到 = ~5.5 秒

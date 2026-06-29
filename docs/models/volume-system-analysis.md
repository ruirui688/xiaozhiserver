# 音量系统混乱分析

> 分析日期：2026-06-25 | 分支：feature/model-performance-benchmark | 状态：仅记录，不修复

---

## 概述

项目存在**三种完全不同的"音量"概念**，分布在**五个系统层**，取值范围、键名、校验逻辑全都不一致。根因是项目逐步演进过程中，各模块独立添加音量功能，但从未统一概念、标注差异、对齐范围。

---

## 三种"音量"概念（互不相干）

| # | 概念 | 控制对象 | 取值范围 | 所在位置 |
|---|---|---|---|---|
| 1 | **设备扬声器音量** | ESP32 物理喇叭响度 | 0–100 | MCP 工具 → ESP32 硬件 |
| 2 | **TTS 合成音量** | 语音合成时的音频响度 | 每种 TTS 厂商不同 | 智能体配置 → TTS 厂商 API |
| 3 | **浏览器播放器音量** | Web 页面 `<audio>` 元素 | 0–1 | AudioPlayer.vue（纯前端） |

用户说"调音量"时，LLM 走的是路线 1（设备扬声器），但用户在 Web 控制台拖动"音量"滑块时，改的是路线 2（TTS 合成音量）。两者互不相干，但没有代码向 LLM 解释这个区别。

---

## 混乱点详解

### 1. TTS 音量取值范围完全分裂

每种 TTS 厂商的 volume 范围不同，而 UI 滑块的 -100~100 跟任何一个都对不上：

| TTS 厂商 | 配置文件 | 配置键 | 实际范围 | 默认值 |
|---|---|---|---|---|
| 阿里云流式 | `aliyun_stream.py:91` | `ttsVolume` | 0–100 | 50 |
| 阿里云 | `aliyun.py:90` | `ttsVolume` | 0–100 | 50 |
| 讯飞流式 | `xunfei_stream.py:64` | `ttsVolume` | 0–100 | 50 |
| 阿里百炼流式 | `alibl_stream.py:23` | `ttsVolume` | 0–100 | 50 |
| 腾讯 | `tencent.py:14` | `ttsVolume` | **-10~10** | 0 |
| PaddleSpeech | `paddle_speech.py:20` | `ttsVolume` | **0~3** | 1.0 |
| MiniMax | `minimax_httpstream.py:62` | `ttsVolume` | **0.1~10** | 1.0 |
| 火山引擎 | `huoshan_double_stream.py:216` | `ttsVolume` | **-50~100** | 0 |
| SiliconFlow | `siliconflow.py:7` | `ttsVolume` | **-10~10** | 0 |

**UI 滑块 -100~100 是虚构的**，没有任何 TTS 厂商真正使用这个范围。用户在 UI 上把滑块拖到 50，值被原样传给 TTS 厂商 API，各厂商解读完全不同。

---

### 2. 设备音量 vs TTS 音量 — LLM 分不清

从实际日志：
```
用户: "把音量设至30%"
智体: self_get_device_status({})  →  返回 {volume: 20}
智体: self_audio_speaker_set_volume({"volume": 30})
```

LLM 调用的是**设备 MCP 工具**（设备扬声器音量）。

但如果在 Web 控制台拖动"音量"滑块，改的是 `ai_agent.tts_volume`（TTS 合成音量）。系统提示词 (`system_prompt.py:75-76`) 提到了音量工具，但从未解释设备音量和 TTS 音量的区别。

---

### 3. `self_get_device_status` 在服务端不存在

```bash
grep -rn 'self_get_device_status' main/xiaozhi-server/
# 无匹配
```

该工具由 ESP32 设备端 MCP 服务器在运行时动态注册，Python 服务端代码完全没有定义。Python 服务只是把 MCP 调用原样转发给设备。后果：
- 设备未注册该工具 → LLM 调用静默失败
- 服务端对设备状态一无所知，无缓存、无持久化、无兜底

---

### 4. 两套功能重复的音量 MCP 工具

LLM 可通过**两种不同工具、不同参数结构**设置设备音量：

```json
// 方式 1：专用工具
{"tool": "self_audio_speaker_set_volume", "arguments": {"volume": 50}}

// 方式 2：通用设备更新（参数结构完全不同）
{"tool": "self_update_device", "arguments": {"updates": {"audio_speaker": {"volume": 50}}}}
```

另有 `self_audio_speaker_volume_up` / `self_audio_speaker_volume_down` 用于相对调节。

定义位置：`core/providers/tools/device_mcp/mcp_executor.py:15-18`

---

### 5. 新建智能体时 `ttsVolume` 不会从模板复制

`AgentServiceImpl.java` 创建智能体时，以下字段**被遗漏**：

```java
// 被复制：ttsVoiceId, memModelId, intentModelId, systemPrompt, summaryMemory, ...
// 被遗漏：ttsVolume, ttsRate, ttsPitch
```

后果：新智能体 `tts_volume = NULL` → 运行时配置不输出该键 → Python TTS 使用硬编码默认值（通常 50）。

---

### 6. 全链路零校验

| 层级 | 文件 | 问题 |
|---|---|---|
| Java DTO | `AgentUpdateDTO.java:51-52` | `@Schema(example="50")` 但无 `@Min`/`@Max`，任意整数都接受 |
| Java 创建 | `AgentServiceImpl.java:471-566` | 不从模板复制 `ttsVolume`，也无默认值 |
| Python MCP | `mcp_executor.py:32-36` | 提取 `volume` 参数无范围检查 |
| UI 滑块 | `TtsAdvancedSettings.vue:18` / `speedPitch.vue:65` | 范围 -100~100，与任何 TTS 厂商都不匹配 |
| UI 硬编码 | `roleConfig.vue:1370` | 音频预览音量硬编码 1.0，不跟随 TTS 设置 |

---

### 7. i18n 两个"音量"标签完全相同

```javascript
// manager-web/src/i18n/zh_CN.js
'mcpToolCall.prop.volume': '音量',          // 设备扬声器（MCP 工具面板）
'roleConfig.ttsVolume': '音量',             // TTS 合成（智能体配置表单）

// manager-mobile/src/i18n/zh_CN.ts
'agent.ttsVolume': '音量',                  // TTS 合成
```

同名标签，指向两个完全不同的东西，用户无法区分。

---

## 完整数据流

```
用户说"调音量"
    │
    ├─► 语音 → ASR → LLM（SemanticControl 不处理音量，confidence 0.5 回退）
    │       │
    │       ├─► self_audio_speaker_set_volume({"volume": 50})  ← 设备扬声器 (0-100)
    │       ├─► self_audio_speaker_volume_up({})               ← 设备扬声器 (相对)
    │       ├─► self_audio_speaker_volume_down({})             ← 设备扬声器 (相对)
    │       └─► self_update_device({"updates": {...}})         ← 设备扬声器 (通用)
    │
    ├─► Web UI "音量" 滑块 (-100~100) ──── 移动端 speedPitch 同样 -100~100
    │       │
    │       └─► AgentUpdateDTO.ttsVolume → Java API → MySQL ai_agent.tts_volume
    │               │
    │               └─► ConfigServiceImpl 输出 "ttsVolume" → Python TTS 读取 "ttsVolume"
    │                       │
    │                       ├─► 阿里云/讯飞/阿里百炼: 0-100
    │                       ├─► 腾讯/SiliconFlow: -10~10
    │                       ├─► PaddleSpeech: 0~3
    │                       ├─► MiniMax: 0.1~10
    │                       └─► 火山引擎: -50~100
    │
    └─► 浏览器 AudioPlayer.vue (0-1, 纯本地 <audio> 元素，与后端完全无关)
```

---

## 涉及的关键文件

### Python 服务端 (xiaozhi-server)

| 文件 | 相关内容 |
|---|---|
| `core/providers/tools/device_mcp/mcp_executor.py` | MCP 音量工具执行器、FAST_RESPONSE 列表、超时配置 |
| `core/providers/tools/device_mcp/mcp_handler.py` | MCP JSON-RPC 传输，向设备发送工具调用 |
| `core/providers/tools/unified_tool_handler.py` | 统一工具分派，LLM function_call → 工具执行 |
| `core/handle/intentHandler.py` | 意图处理，提取 function_call 并路由到 unified_tool_handler |
| `core/connection.py` | 连接处理器，runtime intent 降级、工具调用超时 |
| `core/providers/intent/intent_llm/intent_llm.py` | 意图 LLM 系统提示，教 LLM 何时用哪种音量工具 |
| `core/providers/llm/system_prompt.py` | 主 LLM 系统提示，音量工具使用指导 |
| `core/providers/tts/aliyun_stream.py` | TTS 音量配置：`("ttsVolume", "volume", 0, 100, 50, int)` |
| `core/providers/tts/aliyun.py` | 同上 |
| `core/providers/tts/xunfei_stream.py` | 同上 |
| `core/providers/tts/alibl_stream.py` | 同上 |
| `core/providers/tts/tencent.py` | TTS 音量配置：`("ttsVolume", "volume", -10, 10, 0, ...)` |
| `core/providers/tts/paddle_speech.py` | TTS 音量配置：`("ttsVolume", "volume", 0, 3, 1.0, ...)` |
| `core/providers/tts/minimax_httpstream.py` | TTS 音量，手工校验 0.1~10 |
| `core/providers/tts/huoshan_double_stream.py` | TTS 音量，手工校验 -50~100 |
| `core/providers/tts/siliconflow.py` | TTS 音量配置：`("ttsVolume", "gain", -10, 10, 0, int)` |
| `core/semantic/router.py` | 语义路由 — **不处理音量**（confidence 0.5 回退到 LLM） |
| `core/semantic/policy_engine.py` | 策略引擎 — **不处理音量** |

### Java 管理 API (manager-api)

| 文件 | 相关内容 |
|---|---|
| `.../agent/entity/AgentEntity.java:55-56` | `Integer ttsVolume` 映射到 `ai_agent.tts_volume` |
| `.../agent/entity/AgentTemplateEntity.java:80-81` | 模板实体同样有 `ttsVolume` |
| `.../agent/dto/AgentUpdateDTO.java:51-52` | 更新 DTO，`ttsVolume` **无校验注解** |
| `.../agent/dto/AgentCreateDTO.java` | **缺少** `ttsVolume` 字段 |
| `.../agent/dto/AgentDTO.java` | **缺少** `ttsVolume`（列表不可见） |
| `.../agent/service/impl/AgentServiceImpl.java:322-323` | 更新时设置 volume，创建时**跳过** |
| `.../config/service/impl/ConfigServiceImpl.java:465-466` | 将 `ttsVolume` 写入运行时配置，键名 `"ttsVolume"` |
| `.../resources/db/changelog/202602061650.sql` | 添加 `tts_volume` 列到 `ai_agent` 和 `ai_agent_template` |

### 前端 (manager-web / manager-mobile)

| 文件 | 相关内容 |
|---|---|
| `manager-web/src/components/TtsAdvancedSettings.vue` | TTS 音量滑块，范围 **-100~100** |
| `manager-web/src/components/AudioPlayer.vue` | 浏览器播放器音量，范围 **0~1**（与 TTS 无关） |
| `manager-web/src/views/roleConfig.vue` | 智能体配置页，集成 TTS 音量设置 |
| `manager-mobile/src/pages/agent/speedPitch.vue` | 移动端 TTS 音量滑块，范围 **-100~100** |
| `manager-mobile/src/store/speedPitch.ts` | Pinia 存储，默认 `ttsVolume: 0` |
| `manager-mobile/src/pages/agent/edit.vue` | 移动端智能体编辑，同步音量到 store |

---

## 未来如果要修的建议方向

1. **明确概念边界**：设备扬声器音量和 TTS 合成音量是两个独立概念，应在 UI 和系统提示词中明确区分（如"设备音量" vs "语音响度"）
2. **统一 TTS 音量范围**：0-100 最通用（阿里云/讯飞/阿里百炼都支持），其他厂商做映射
3. **统一配置键名**：Java 侧写 `ttsVolume`，Python 侧读 `ttsVolume`（目前一致，但 `volume` 和 `ttsVolume` 并存容易混淆）
4. **加校验**：Java DTO 加 `@Min(0) @Max(100)`，Python MCP 执行器加范围检查
5. **修复创建遗漏**：`AgentServiceImpl.createAgent()` 从模板复制 `ttsVolume`、`ttsRate`、`ttsPitch`
6. **去重 MCP 工具**：`self_update_device` 和 `self_audio_speaker_set_volume` 选一个保留，或明确各自适用场景

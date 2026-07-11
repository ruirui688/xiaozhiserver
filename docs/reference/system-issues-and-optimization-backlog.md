# 系统问题与优化清单

> 分析日期：2026-06-25 | 分支：feature/model-performance-benchmark | 状态：仅记录，不修复

---

## 目录

- [一、音量系统（7 个问题）](#一音量系统)
- [二、语音管道延迟（5 秒端到端）](#二语音管道延迟)
- [三、豆包 TTS 只有一个音色可选](#三豆包-tts-只有一个音色可选)
- [四、短期记忆总结不显示在角色配置界面](#四短期记忆总结不显示在角色配置界面)
- [五、聊天记录不支持按设备分类](#五聊天记录不支持按设备分类)
- [六、模型管理与配置体系混乱](#六模型管理与配置体系混乱)
- [七、语义打断与端到端语音模型演进](#七语义打断与端到端语音模型演进) ← 🆕
- [八、问题总览表](#八问题总览表)

---

## 一、音量系统

### 概述

项目存在**三种完全不同的"音量"概念**，分布在五个系统层，取值范围、键名、校验逻辑全都不一致。

| # | 概念 | 控制对象 | 取值范围 | 所在位置 |
|---|---|---|---|---|
| 1 | 设备扬声器音量 | ESP32 物理喇叭响度 | 0–100 | MCP 工具 → ESP32 硬件 |
| 2 | TTS 合成音量 | 语音合成时的音频响度 | 每种 TTS 厂商不同 | 智能体配置 → TTS 厂商 API |
| 3 | 浏览器播放器音量 | Web 页面 `<audio>` 元素 | 0–1 | AudioPlayer.vue（纯前端） |

用户说"调音量"时，LLM 走路线 1（设备扬声器），但在 Web 控制台拖动"音量"滑块时，改的是路线 2（TTS 合成音量）。两者互不相干。

### 问题 1：TTS 音量取值范围完全分裂（6 种不同范围）

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

**UI 滑块 -100~100 是虚构的**，与任何 TTS 厂商的真实范围都不匹配。

### 问题 2：设备音量 vs TTS 音量 — LLM 分不清

系统提示词 (`system_prompt.py:75-76`) 提到了音量工具，但从未解释设备音量和 TTS 音量的区别。

### 问题 3：`self_get_device_status` 在服务端不存在

该工具由 ESP32 设备端 MCP 服务器在运行时动态注册，Python 服务端代码完全没有定义（grep 无匹配）。

### 问题 4：两套功能重复的音量 MCP 工具

LLM 可通过两种不同工具、不同参数结构设置设备音量：

```json
{"tool": "self_audio_speaker_set_volume", "arguments": {"volume": 50}}
{"tool": "self_update_device", "arguments": {"updates": {"audio_speaker": {"volume": 50}}}}
```

定义位置：`core/providers/tools/device_mcp/mcp_executor.py:15-18`

### 问题 5：新建智能体时 `ttsVolume` 不会从模板复制

`AgentServiceImpl.java` 创建智能体时，`ttsVolume`、`ttsRate`、`ttsPitch` 被遗漏。后果：新智能体 `tts_volume = NULL` → Python TTS 使用硬编码默认值。

### 问题 6：全链路零校验

| 层级 | 文件 | 问题 |
|---|---|---|
| Java DTO | `AgentUpdateDTO.java:51-52` | `@Schema(example="50")` 但无 `@Min`/`@Max` |
| Java 创建 | `AgentServiceImpl.java:471-566` | 不从模板复制 `ttsVolume`，也无默认值 |
| Python MCP | `mcp_executor.py:32-36` | 提取 `volume` 参数无范围检查 |
| UI 滑块 | `TtsAdvancedSettings.vue:18` / `speedPitch.vue:65` | 范围 -100~100，与任何 TTS 厂商不匹配 |
| UI 硬编码 | `roleConfig.vue:1370` | 音频预览音量硬编码 1.0 |

### 问题 7：i18n 两个"音量"标签完全相同

```javascript
// manager-web/src/i18n/zh_CN.js
'mcpToolCall.prop.volume': '音量',      // 设备扬声器（MCP 工具面板）
'roleConfig.ttsVolume': '音量',         // TTS 合成（智能体配置表单）
```

同名标签，指向两个完全不同的东西，用户无法区分。

### 涉及的关键文件（音量）

| 文件 | 相关内容 |
|---|---|
| `core/providers/tools/device_mcp/mcp_executor.py` | MCP 音量工具执行器 |
| `core/providers/tools/device_mcp/mcp_handler.py` | MCP JSON-RPC 传输 |
| `core/handle/intentHandler.py` | 意图处理，提取 function_call |
| `core/connection.py` | 连接处理器，工具调用超时 |
| `core/providers/intent/intent_llm/intent_llm.py` | 意图 LLM 系统提示 |
| `core/providers/llm/system_prompt.py` | 主 LLM 系统提示 |
| `core/semantic/router.py` | 语义路由 — 不处理音量（回退到 LLM） |
| `core/providers/tts/*.py` | 9 种 TTS 音量配置，6 种不同范围 |
| `AgentUpdateDTO.java` | `ttsVolume` 无校验注解 |
| `AgentServiceImpl.java` | 创建时遗漏 `ttsVolume` 复制 |
| `TtsAdvancedSettings.vue` / `speedPitch.vue` | UI 滑块 -100~100 |
| `AudioPlayer.vue` | 浏览器播放器音量 0-1 |

---

## 二、语音管道延迟

### 2026-07-11 真机更新

- 正常轮实测：ASR→LLM 请求约 121.5ms、LLM 首响应约 114.6ms、ASR→整句 TTS 音频生成约 1.321s；正常轮的主要矛盾已不是 LLM。
- 严重长尾已定位为豆包录音 ASR：查询曾约 60 秒超时、HTTPS 曾约 15 秒读超时，且同步 `requests.post`/`time.sleep` 会阻塞事件循环并导致多句聚合。
- 已在源码中改为 aiohttp 异步提交/查询，默认单次 HTTP 10 秒、轮询 0.2 秒、查询总预算 15 秒。
- 流式 ASR 增加初始化10秒、结果15秒超时；真实录音ASR 4/4、流式ASR修复前后5/5成功，无效凭据均在300ms内返回401。
- SeedTTS2真实连续5会话成功并修复不足60ms的Opus尾帧残留；PCM、Opus和FFmpeg解码均通过。
- DeepSeek v4-pro/v4-flash原生与仓库provider均成功；完整ASR→LLM→TTS链路连续3/3成功，平均约3.753秒。
- 唤醒链固定 2 秒音频丢弃改为 `wakeup_vad_ignore_ms`（默认0），`enable_greeting` 默认关闭；无AEC回声设备可单独恢复兼容窗口。
- 设备端已真实烧录并将唤醒到 listening 从约 2.7 秒降到约 0.42 秒，AFE ringbuffer full 从单轮39-45条降为0。
- 状态：源码和测试已更新，**未构建镜像、未部署生产**；用户手动发布后再关闭对应问题项。

### 5 秒端到端延迟拆解

```
用户说完最后一个字
    │
    ├─ 1000ms  VAD 尾音检测 (min_silence_duration_ms, 可配置)
    ├─  500ms  ASR API 调用 (speech_to_text)
    ├─   50ms  SemanticControl 路由
    ├─  150ms  Memory 查询 (同步阻塞 → future.result())
    ├─  200ms  上下文组装 (system prompt + 工具定义 ~2000-4000 tokens)
    ├─ 2500ms  LLM 推理生成 (首 token ~500-800ms + 逐 token ~25-40ms × 80 tokens)
    ├─  800ms  TTS 首段合成 (等标点 + API 调用 + Opus 编码)
    ├─  300ms  网络传输 + 设备缓冲
    │
    ▼ 用户听到回复
```

### 各阶段耗时占比

| 阶段 | 耗时 | 占比 | 瓶颈说明 |
|---|---|---|---|
| SemanticControl 路由 | ~50ms | 1% | 极小 |
| Memory 向量检索 | ~150ms | 3% | 同步阻塞（`future.result()`） |
| 上下文组装 | ~200ms | 4% | system prompt + 工具定义 2000-4000 tokens |
| **LLM 推理生成** | **~2500ms** | **50%** | ★ 最大瓶颈 |
| **TTS 合成** | **~800ms** | **16%** | ★ 第二大瓶颈 |
| 网络 + 设备缓冲 | ~300ms | 6% | — |
| 管线调度开销 | ~1000ms | 20% | 线程切换、队列缓冲、GIL 竞争 |

### LLM 首 Token 延迟（TTFT）的影响因素

System prompt 包含以下内容（估计 **2000-4000 tokens**）：

| 组成部分 | 说明 |
|---|---|
| 角色设定 + 回复规则 | 角色背景、语气、emotion 选择 |
| 工具使用指导 | 何时用 volume_up/down vs set_volume |
| LLM 工具定义 JSON | 所有注册的 MCP 工具 schema |
| 意图 LLM 示例 | few-shot 示例 |
| 音乐曲库列表 | `<music_library>` 上下文块 |
| 游戏提示词 | 纯语言游戏上下文 |
| 记忆片段 | memory 查询结果 |
| 历史对话 | 最近 N 轮上下文 |

### TTS 首段等待（等标点）

```
LLM 流式输出: "🙂" → " " → "当" → "然" → "开" → "心" → "啦" → "，"
                                                            ↑
                                  只有到这里，才触发 TTS 合成第一个语音段
```

`base.py:493 _get_segment_text()` 必须等到标点符号才切分 TTS 段。

### 同步阻塞点

- `future.result()` — Memory 查询阻塞当前线程 ~150ms
- `asyncio.run()` — `text_to_speak()` 阻塞 ~800ms
- `asyncio.run()` — emotion detection 阻塞 ~50ms

### 涉及的关键文件（延迟）

| 文件 | 行号 | 阶段 |
|---|---|---|
| `core/providers/vad/silero.py` | 58-121 | VAD 检测 |
| `core/providers/asr/base.py` | 44-174 | ASR 工作线程 + 语音停止 |
| `core/handle/receiveAudioHandle.py` | 43-80 | ASR → Chat 桥接 |
| `core/handle/intentHandler.py` | - | 意图路由 |
| `core/semantic/semantic_center.py` | - | 语义路由 |
| `core/connection.py` | 1024-1405 | LLM Chat 主流程 |
| `core/connection.py` | 1093-1097 | Memory 查询（同步阻塞） |
| `core/connection.py` | 1099-1105 | 上下文组装 |
| `core/connection.py` | 1107-1212 | LLM 流式调用 + TTS 推送 |
| `core/providers/tts/base.py` | 493-530 | TTS 文本分段（等标点） |
| `core/providers/tts/base.py` | 130-198 | TTS 合成 + 编码 |
| `core/providers/tts/base.py` | 418-469 | TTS 音频播放 |

---

## 三、豆包 TTS 只有一个音色可选

### 问题现象

豆包（火山引擎）TTS 语音合成在管理界面只能看到一个音色"魅力苏菲"。

### 根因：数据库 `ai_tts_voice` 表只插入了一条记录

迁移脚本 `db/changelog/202606251815.sql` **先 DELETE 了所有旧音色，然后只插回了一条**：

```sql
DELETE FROM `ai_tts_voice` WHERE `tts_model_id` = @seed_tts_model_id;

INSERT INTO `ai_tts_voice` (...) VALUES
('TTS_HSDSTTS_V2_SOPHIE', @seed_tts_model_id, '魅力苏菲',
 'zh_female_sophie_uranus_bigtts', '普通话、英语', NULL,
 '唯一保留音色，和 Seed TTS 2.0 探测脚本一致。', ...)
```

### 不是 API 不支持其他音色

- Python 侧 `huoshan_double_stream.py:171-174` 只透传 `speaker` 给火山 API，代码完全不限制音色
- 火山引擎 Seed TTS 2.0 支持多种 speaker ID（如 `zh_male_shaonianzixin_moon_bigtts` 等）
- 旧版豆包 HTTP TTS 用的就是不同的 speaker

### 原因：迁移保守策略

项目从旧版豆包 HTTP TTS → Seed TTS 2.0 双向流式 WebSocket 的迁移过程采用了保守策略：只保留**一个经过探测脚本验证的**音色，避免引入未验证的 speaker ID 导致 TTS 调用失败。

### 如何增加音色

通过 Web 管理界面（模型配置 → TTS 模型 → 音色管理 → 添加）或直接 SQL 写入 `ai_tts_voice` 表即可。

### 涉及的关键文件（豆包音色）

| 文件 | 行号 | 说明 |
|---|---|---|
| `db/changelog/202606251815.sql` | 46-60 | DELETE 旧音色 + INSERT 唯一音色 |
| `core/providers/tts/huoshan_double_stream.py` | 171-174 | speaker 透传，无限制 |
| `core/providers/tts/doubao.py` | 1-17 | 旧版 HTTP TTS 已废弃 |
| `timbre/service/impl/TimbreServiceImpl.java` | 121-159 | `getVoiceNames()` 查询音色列表 |
| `roleConfig.vue` | 937-974 | 前端音色下拉框构建 |
| `components/TtsModel.vue` | - | 音色管理弹窗 |

---

## 四、短期记忆总结不显示在角色配置界面

### 问题现象

设备已开启短期记忆总结（`mem_local_short`），Python 日志显示 "Save memory successful"，但角色配置界面的「记忆历史」文本框始终为空。

### 核心问题：`mem_local_short` 有两条互斥的保存路径

```python
# mem_local_short.py:135-193
if self.save_to_file:
    # 路径 A：Python LLM 总结 → YAML 文件 (data/.memory.yaml)
    self.save_memory_to_file()
else:
    # 路径 B：调用 Java API → Java 侧生成总结 → MySQL
    await generate_and_save_chat_summary(summary_id)
```

`save_to_file` 的值由 `connection.py:908` 决定：

```python
save_to_file=not self.read_config_from_api
```

| `read_config_from_api` | `save_to_file` | 保存位置 | 角色配置界面能显示？ |
|---|---|---|---|
| `True`（生产模式） | `False` | 路径 B → MySQL | ✅ 能（但依赖多个条件） |
| `False`（开发/本地模式） | `True` | 路径 A → YAML 文件 | ❌ 不能（界面读 MySQL） |

### 路径 B（生产模式）的 6 个静默失败点

1. **找不到设备**：`getDeviceBySessionId()` 查 `agent_chat_history` 表
2. **找不到智能体**：设备未绑定智能体
3. **记忆模型类型被跳过**：只有 `Memory_mem_local_short` 会生成总结
4. **聊天记录为空**：`agent_chat_history` 中没有该 session_id 的记录
5. **LLM 服务不可用**：SLM 模型未配置或调用失败
6. **事务问题**：在非 Spring 管理的线程中运行，`@Transactional` 可能不生效

### 额外问题

- Python `ManageApiClient` 可能未初始化，异常被 `try/except` 吞掉
- 前端切换记忆模型类型时会清空 `summaryMemory`，刷新页面后无法恢复
- Python 日志 "Save memory successful" 仅表示 API 调用成功（HTTP 200），不代表总结已写入数据库

### 涉及的关键文件（记忆总结）

| 文件 | 行号 | 说明 |
|---|---|---|
| `core/providers/memory/mem_local_short/mem_local_short.py` | 135-198 | `save_memory()` 两条路径分支 |
| `core/providers/memory/mem_local_short/mem_local_short.py` | 190-193 | 路径 B → 调用 Java API |
| `core/connection.py` | 904-908 | `init_memory()` → `save_to_file` |
| `core/connection.py` | 312-339 | `_save_and_close()` → 守护线程 |
| `config/manage_api_client.py` | 198-207 | `generate_and_save_chat_summary()` |
| `agent/controller/AgentController.java` | 136-155 | 异步线程启动总结 |
| `agent/service/impl/AgentChatSummaryServiceImpl.java` | 94-136 | `generateAndSaveChatSummary()` |
| `agent/service/impl/AgentServiceImpl.java` | 278-344 | `updateAgentById()` → `summaryMemory` |
| `agent/entity/AgentEntity.java` | 78 | `summaryMemory` 字段 |
| `config/service/impl/ConfigServiceImpl.java` | 238, 544 | 输出 `summaryMemory` 到设备配置 |
| `mapper/agent/AgentDao.xml` | 33, 76 | SQL 映射 |
| `roleConfig.vue` | 140, 144 | 记忆文本框绑定与禁用条件 |
| `roleConfig.vue` | 1049-1055 | 切换记忆模型时清空 |

---

## 五、聊天记录不支持按设备分类

### 问题现象

一个智能体可能绑定多个设备（多台 ESP32）。在查看聊天记录时，所有设备的会话混在一起，无法区分哪个会话属于哪个设备，也无法按设备筛选。

用户反馈：_"一个智能体里面可能会有多个设备，看聊天记录时希望可以按照不同的设备分类"_

### 根因分析

#### 1. 数据层已有关联字段，但查询未利用

`agent_chat_history` 表已有 `mac_address` 字段（每条聊天记录都记录了发起设备）：

```java
// AgentChatHistoryEntity.java:37-38
@TableField(value = "mac_address")
private String macAddress;
```

但这个字段在会话列表查询中**完全未使用**。

#### 2. 会话列表查询不返回设备信息

`AgentChatHistoryServiceImpl.getSessionListByAgentId()` (line 49) 的 SQL：

```sql
SELECT session_id, MAX(created_at) as created_at, COUNT(*) as chat_count
FROM ai_agent_chat_history
WHERE agent_id = ?
GROUP BY session_id
ORDER BY created_at DESC
```

- 只按 `agent_id` 过滤，按 `session_id` 分组
- **不返回 `mac_address`**
- **不支持按 `mac_address` 过滤**

#### 3. `AgentChatSessionDTO` 缺少设备字段

```java
// AgentChatSessionDTO.java — 当前只有 4 个字段
public class AgentChatSessionDTO {
    private String sessionId;
    private LocalDateTime createdAt;
    private Integer chatCount;
    private String title;
    // ❌ 缺少 macAddress
}
```

#### 4. Controller API 不接受设备筛选参数

```java
// AgentController.java:209 — getAgentSessions 只接受 page/limit
public Result<PageData<AgentChatSessionDTO>> getAgentSessions(
        @PathVariable("id") String id,
        @RequestParam Map<String, Object> params) {
    params.put("agentId", id);
    // ❌ 不接受 macAddress 参数
    PageData<AgentChatSessionDTO> page = agentChatHistoryService.getSessionListByAgentId(params);
}
```

#### 5. 前端完全不展示设备信息

`ChatHistoryDialog.vue` 的会话列表（line 15-24）每条只显示时间和消息数，没有设备标签：

```html
<div v-for="session in sessions" :key="session.sessionId" class="session-item">
    <img :src="getUserAvatar(session.sessionId)" class="avatar" />
    <div class="session-info">
        <div class="session-time">{{ session.title || formatTime(session.createdAt) }}</div>
        <div class="message-count">{{ session.chatCount > 99 ? '99' : session.chatCount }}</div>
    </div>
    <!-- ❌ 没有设备名称/MAC 标签 -->
</div>
```

唯一的设备信息是消息列表第一项的 `macAddress`（line 423-424），必须点击进入会话才能看到，无法用于筛选。

### 需要改动的代码位置

#### 后端

| 文件 | 位置 | 改动 | 难度 |
|---|---|---|---|
| `AgentChatSessionDTO.java` | — | 新增 `macAddress` 字段 | 低 |
| `AgentChatHistoryServiceImpl.java` | L49-88 | `getSessionListByAgentId()` SQL 加 `mac_address`，支持 macAddress 过滤参数 | 中 |
| `AgentController.java` | L202-214 | `getAgentSessions()` 接受 `macAddress` 可选参数 | 低 |
| `AgentController.java` | 新增 | 提供 `GET /{agentId}/devices` 返回该智能体下所有设备列表（用于前端下拉选项） | 中 |

#### 前端

| 文件 | 位置 | 改动 | 难度 |
|---|---|---|---|
| `ChatHistoryDialog.vue` | L14-24 | 会话列表每条增加设备标签（MAC/设备名） | 低 |
| `ChatHistoryDialog.vue` | L392-416 | `loadSessions()` 支持 `macAddress` 筛选参数 | 低 |
| `ChatHistoryDialog.vue` | 顶部 | 新增设备下拉选择器，用于筛选会话 | 中 |
| `agent.js` | L119 | API 调用支持 `macAddress` 参数 | 低 |

### 推荐方案

```
┌─ ChatHistoryDialog ────────────────────────────────────────────┐
│                                                                 │
│  智能体: 我的助手                                                │
│  设备筛选: [全部设备 ▾] [设备A (aa:bb:cc)] [设备B (dd:ee:ff)]    │
│                                                                 │
│  ┌─ 会话列表 ────────────────────────────────────────────────┐  │
│  │                                                           │  │
│  │  🧑 今天 14:30                      [设备A]          9    │  │
│  │  🧑 今天 10:15                      [设备B]          23   │  │
│  │  🧑 昨天 18:00                      [设备A]          15   │  │
│  │  🧑 6月23日 09:00                   [设备C]          7    │  │
│  │                                                           │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

核心思路：
1. SQL 查询 `session_id` 时同时取 `MAX(mac_address)`（同一个 session 的 mac_address 应一致），返回给前端
2. 前端在会话列表中每条显示设备标签
3. 顶部增加设备下拉筛选，选择后 API 调用带上 `macAddress` 参数过滤

### 涉及的关键文件（聊天记录设备筛选）

| 文件 | 行号 | 说明 |
|---|---|---|
| `agent/entity/AgentChatHistoryEntity.java` | 37-38 | 已有 `macAddress` 字段 |
| `agent/dto/AgentChatSessionDTO.java` | — | 缺少 `macAddress` 字段 |
| `agent/service/impl/AgentChatHistoryServiceImpl.java` | 49-88 | `getSessionListByAgentId()` 需加 device 维度 |
| `agent/controller/AgentController.java` | 202-214 | `getAgentSessions()` 需加 macAddress 参数 |
| `components/ChatHistoryDialog.vue` | 4, 14-24 | 标题有 MAC 但列表无设备标签 |
| `components/ChatHistoryDialog.vue` | 392-416 | `loadSessions()` 无设备筛选 |
| `apis/module/agent.js` | 119 | API 调用需支持 macAddress 参数 |

---

## 六、模型管理与配置体系混乱

> 📄 完整分析见 [model-management-architecture-analysis](../models/model-management-architecture-analysis.md)

### 概述

当前项目的模型管理体系存在 5 个维度的混乱：模型类型枚举不一致、Python/Java 两套注册体系不对齐、7+ 种协议无统一标注、新旧版本标记缺失、配置链路有两条完全不同的路径（本地 config.yaml vs 远程 Java API）。

**统计**：9 种模型类型 × 52 个 Python 适配器文件 × 6 张 Java DB 表 × 3 套前端 UI × 2 条配置路径 = 高度碎片化的管理体验。

### 核心混乱点

#### 1. Model Type 枚举在三处不一致

| 位置 | 枚举值 |
|---|---|
| Java `ModelConfigEntity` 注释 | Memory/ASR/VAD/LLM/TTS（5 种） |
| Java `ConfigServiceImpl` 遍历数组 | VAD, ASR, TTS, Memory, Intent, LLM, VLLM, SLM, RAG（9 种） |
| 前端 `ModelConfig.vue` 导航 | vad, asr, llm, slm, vllm, intent, tts, memory, rag（9 种） |
| 前端 `ProviderManagement.vue` | ASR, TTS, LLM, SLM, VLLM, Intent, Memory, VAD, Plugin, RAG（10 种） |
| Python `config.yaml` | VAD, ASR, LLM, TTS, VLLM, Memory, Intent（7 种，无 SLM/RAG） |

#### 2. 两套注册体系不对齐

- **Python 侧**：52 个 provider 文件，通过文件系统约定动态 import（如 `core/providers/asr/{type}.py`）
- **Java 侧**：`ai_model_provider` / `ai_model_template` / `ai_model_config` 三张 DB 表管理注册
- **矛盾**：Python 完全不感知 Java 的 template/field schema 体系，Java 精心设计的字段校验到了 Python 侧全部丢失

#### 3. TTS 配置盲区

config.yaml 的 TTS 段只有 **1 个模型**（`DoubaoSeedTTS2Stream`），但 Python 侧有 **19 个** TTS 适配器文件。用户想用其他 18 个 TTS（Edge、OpenAI、SiliconFlow 等）必须手动在 DB 创建配置，无 UI 引导。

#### 4. 火山引擎 TTS 特殊处理膨胀

`ModelRuntimeAssembler.java` 中有 **7 个**专为火山引擎双向流 TTS 设计的方法和 **4 个**硬编码常量，这些逻辑本应在 provider 层面配置，却硬编码在通用 Assembler 中。

#### 5. SLM 概念模糊

Java 侧有 `SLM` 模型类型和 `slm_model_id` 字段，但 Python 侧完全没有 SLM 概念——SLM 和 LLM 共用 `llm.py` 工厂。移动端 SLM 选择器实际用 LLM 的模型列表。

#### 6. 废弃管理缺失

- `tts/doubao.py` 抛出 RuntimeError 但文件仍在仓库
- Java `ai_model_template.deprecated` 字段是 2026-06-23 才加的，大部分模板默认为 0
- 旧版 DB 配置行（如 `TTS_DoubaoTTS`）的 `adapterCode` 可能指向已废弃代码
- config.yaml 中无任何 deprecated 标记

#### 7. 配置键名在不同层不一致

| 概念 | config.yaml | Java DB | Python config.get() |
|---|---|---|---|
| API 端点 | `base_url` / `ws_url` | `endpoint_url` | `base_url` / `ws_url` |
| 模型名称 | `model_name` | `upstream_model_id` | `model_name` / `model` |
| API 密钥 | `api_key` | `secret_value`（独立表） | `api_key` |

#### 8. 前端硬编码泛滥

`ModelEditDialog.vue` 中有大量 provider 相关的硬编码判断：阿里百炼模型匹配逻辑、字段 placeholder、help text 中文文本等。新增 provider 时这些都可能遗漏。

### 涉及的关键文件

| 层级 | 文件 | 说明 |
|---|---|---|
| Python | `core/utils/asr.py, tts.py, llm.py, vad.py, vllm.py, intent.py, memory.py` | 7 个工厂函数，按文件系统约定动态加载 |
| Python | `core/connection.py` | `_initialize_components()` 初始化所有模型 |
| Python | `config/manage_api_client.py` | `get_agent_models()` 从 Java API 拉取配置 |
| Python | `config.yaml` | 800 行本地配置模板，含所有模型配置段 |
| Java | `model/service/impl/ModelRuntimeAssembler.java` | 540 行，运行时配置组装核心 |
| Java | `model/service/impl/ModelConfigServiceImpl.java` | 模型 CRUD + 测试 + 缓存 |
| Java | `model/service/impl/ModelCatalogServiceImpl.java` | 模型目录（~50+ 模板条目在 Java 代码中硬编码） |
| Java | `config/service/impl/ConfigServiceImpl.java` | 设备配置输出（`getAgentModelsConfig()`） |
| DB | `db/changelog/202606221645.sql` | 核心迁移：新增 4 张表 + 6 列 + 数据迁移 |
| DB | `db/changelog/202606232230.sql` | 模板状态字段（deprecated/selectable/hidden...） |
| 前端 | `src/views/ModelConfig.vue` | 主模型管理页（9 个 tab） |
| 前端 | `src/views/ProviderManagement.vue` | 供应器管理页（10 个类型） |
| 前端 | `src/components/ModelEditDialog.vue` | 模型编辑弹窗（含大量硬编码） |

---

## 七、语义打断与端到端语音模型演进

> 📄 完整分析见 [semantic-interruption-and-e2e-voice-model-analysis](../architecture/semantic-interruption-and-e2e-voice-model-analysis.md)

### 概述

当前系统使用传统打断机制——仅通过 Silero VAD 的声音能量判断是否需要打断 TTS 播放。用户咳嗽、说"嗯"、环境噪声都会触发打断。语义打断尝试判断用户发出的声音究竟是：真正想打断、简单附和、无意义背景音、咳嗽、环境噪声还是正常的短暂回应。

同时，语音交互链路短（管道式 VAD→ASR→LLM→TTS），系统调度、状态管理和服务维护相对简单。但对于音频事件感知、情绪识别等能力不足。如果产品售卖出去后积攒了足量的用户交互数据，有能力训练自己的端到端语音模型，将大幅降低对第三方 ASR/LLM/TTS 厂商的依赖。

### 当前打断机制回顾

**核心变量**（`connection.py` L122-126）：
- `client_abort`：布尔标志，用户是否请求停止
- `abort_generation`：单调计数器，每次打断 +1
- `client_is_speaking`：TTS 是否正在播放
- `client_listen_mode`："auto"（允许打断）或 "manual"（禁止打断）

**三条触发路径**：

| 路径 | 触发条件 | 延迟 |
|---|---|---|
| WebSocket abort 消息 | 设备端主动发送 | 即时 |
| VAD 语音触发 | TTS 播放中 + auto 模式 + VAD 检测到新语音 | VAD(~1s) + ASR(~0.5s) |
| 语义中心关键词匹配 | 用户说"停/停止/闭嘴"等 17 个短语 | 关键词匹配 < 10ms |

**6 处打断检查点**：LLM 流式循环 → TTS 文字处理 → TTS 音频播放 → 音频 WebSocket 发送 → 工具结果处理 → 各流式 TTS 自检

### 核心问题

#### 1. 无法区分真打断和背景音

**当前表现**：用户咳嗽、清嗓子、环境噪声超过 VAD 阈值（0.5）即触发打断流程

**技术根因**：Silero VAD 仅输出 `speech_prob` 概率值（0.0–1.0），不做语义分类。整个打断决策链是：

```
音频 → Silero ONNX → speech_prob (0-1) → 双阈值判断 → is_voice → 触发打断
```

没有任何环节判断"这声音是用户想说话，还是只是咳嗽"。

**影响**：打断准确率低，对话被无故中断，用户需要重复"刚才说到哪了"。

#### 2. 简单附和被当作打断

**当前表现**：用户在 TTS 播放中说"嗯"、"好的"、"对" → ASR 识别到文字 → 触发打断 → LLM 重新开始对话

**技术根因**：`receiveAudioHandle.py` L94-96 的判断条件是：

```python
if conn.client_is_speaking and conn.client_listen_mode != "manual":
    await handleAbortMessage(conn)  # 只要有人在说话，就打断
```

没有任何 backchannel 过滤逻辑。即使 ASR 只识别出一个"嗯"字，也触发完整打断流程。

**影响**：中国用户习惯性附和（"嗯嗯"、"对"、"好的"）被频繁当作打断，对话体验生硬。

#### 3. 打断决策在 ASR 完成后，响应滞后

**当前流程**：

```
用户发声 → VAD 检测语音 → 等待 1s 静音 → 判定说话结束 → ASR 识别（~500ms）
  → startToChat → 判断 client_is_speaking → 打断
```

TTS 在用户说完后 1.5 秒才停止，用户听到的是"自己已经说完了，系统还在继续说话"。

**技术根因**：VAD 需要等 `min_silence_duration_ms=1000ms` 才能确认语音结束，ASR 在语音结束后才开始。整个决策在管道后端，不在前端。

**理想状态**：用户第一个音节发出 200ms 内，系统就开始判断是否要打断，在用户还在说话时 TTS 就开始平滑淡出。

#### 4. VAD 阈值是全局静态的

**当前表现**：所有用户、所有环境共用一套 VAD 参数（threshold=0.5, threshold_low=0.2, min_silence=1s）

**技术根因**：`silero.py` 的参数从 config.yaml 全局读取，没有 per-device 或 per-user 的个性化机制。

**影响**：
- 安静环境用户：阈值太低，环境噪声可能触发 VAD
- 嘈杂环境用户：阈值太高，真正的轻声说话检测不到
- 话多型用户：希望更灵敏的打断
- 思考型用户：希望更高的打断门槛

#### 5. 打断后状态恢复生硬

**当前表现**：`handleAbortMessage` 立即清空所有队列（TTS 文字、音频、报告），抛弃正在生成的 LLM 文本

**技术根因**：`clear_queues()` 调用 `get_nowait()` 循环清空三个队列，被打断的 LLM 文本和 TTS 音频全部丢弃。

**影响**：打断前说了一半的话全部丢失，用户可能需要问"你刚才说什么来着"。

#### 6. 管道架构对音频事件感知不足

**当前表现**：管道式架构（VAD → ASR → LLM → TTS）每步只传递文本，丢失了完整的音频信息

**丢失的信息**：
- **情绪**：ASR 只输出文字，愤怒/开心/悲伤的语气全部丢失
- **语速**：用户说话快慢反映紧迫程度，LLM 无法感知
- **环境背景**：音乐声、车声、多人说话等场景信息丢失
- **用户身份**：声纹信息仅在 ASR 阶段用于说话人识别，不传递给 LLM

**长期影响**：这些丢失的信息正是训练端到端模型的关键数据。如果现在不开始采集，未来训练自有模型时面临数据缺失。

#### 7. 缺乏数据采集基础设施

**当前表现**：系统只上报聊天记录（`chat-history/report`），不上报原始音频或交互过程数据

**缺失的数据采集**：
- 原始音频交互对（用户音频 → 系统音频）
- 打断事件的时间戳和上下文
- 用户隐式反馈（重复提问、打断、会话放弃）
- 多轮对话完整上下文

**战略影响**：没有数据就无法训练端到端模型，永远依赖第三方 API。

### 语义打断改进方案（分层分类）

将用户发声分为 4 个等级处理：

```
用户发声
  ├── 🟢 Level 0: 无意义背景音（咳嗽、环境噪声）
  │     → 忽略，继续 TTS
  ├── 🟡 Level 1: 简单反馈（"嗯"、"好的"、"对"）
  │     → 不打断，记录 ACK 用于评估
  ├── 🟠 Level 2: 正常打断（新话题、纠正、补充）
  │     → 正常打断流程
  └── 🔴 Level 3: 紧急打断（重复呼喊、愤怒语气）
        → 立即打断 + 优先级提升
```

**关键技术组件（Phase 1）**：
1. 流式 ASR 中间结果预判——不等静音结束就开始识别
2. 轻量语义分类器——复用 Intent LLM 做打断意图分类
3. 紧急关键词快速通道——"停/救命/别说"等 < 50ms 响应
4. 个性化 VAD 阈值——按设备学习合适的打断敏感度
5. 打断平滑淡出——替代当前暴力静音

### 端到端模型三阶段演进路线

| 阶段 | 时间 | 架构 | 算力需求 | 关键交付 |
|---|---|---|---|---|
| Phase 1 | 当前–1 年 | 管道优化（语义打断 + 流式识别 + 数据采集） | 无额外需求 | 打断准确率 > 90%，数据采集基础就绪 |
| Phase 2 | 1–2 年 | 混合端到端（Freeze-Omni 模式：冻结 LLM + 语音编解码器） | 4-8 GPU，3-7 天训练 | 端到端原型，保留工具调用能力 |
| Phase 3 | 2–3 年 | 完整端到端（Thinker-Talker 架构，Qwen3-Omni 模式） | 8-64 GPU，2-4 周训练 | 自有端到端模型，降低第三方依赖 |

**Phase 2 推荐架构**（风险最低）：
```
ESP32 Opus → Mimi 分词器(12.5Hz) → 冻结 LLM(Qwen) → CosyVoice 2 解码器 → Opus → ESP32
```
LLM 保持冻结 → **不丢失工具调用能力**（IoT、MCP、Home Assistant）。

### 涉及的关键文件（未来改动）

| 优先级 | 文件 | 改动 |
|---|---|---|
| P0 | `core/handle/receiveAudioHandle.py` | 语义分类器替换简单 VAD 判断 |
| P0 | `core/handle/abortHandle.py` | 平滑淡出 + 分级打断 |
| P0 | `core/providers/vad/silero.py` | 个性化阈值支持 |
| P1 | `core/utils/` (新增) | 语义分类器、数据采集器 |
| P1 | `core/connection.py` | 打断上下文保留 |
| P2 | `core/providers/tts/base.py` | 流式打断检查优化 |
| P2 | 新增 DB 表 | 交互数据存储、训练数据导出 |

---

## 八、问题总览表

| # | 类别 | 问题 | 严重程度 | 根因 |
|---|---|---|---|---|
| 1 | 音量 | 三种"音量"概念混淆，LLM 和用户都无法区分 | 🔴 高 | 各模块独立添加，从未统一 |
| 2 | 音量 | TTS 音量 6 种不同范围，UI -100~100 全不匹配 | 🔴 高 | 每家厂商 API 不同，未做映射 |
| 3 | 音量 | `self_get_device_status` 服务端无定义 | 🟡 中 | ESP32 MCP 动态注册，服务端无感知 |
| 4 | 音量 | 两套重复的 MCP 音量工具 | 🟡 中 | 专用工具 + 通用 update_device 并存 |
| 5 | 音量 | 新智能体 `ttsVolume` 不从模板复制 | 🟡 中 | 创建方法遗漏字段 |
| 6 | 音量 | 全链路零校验 | 🟡 中 | 无 `@Min/@Max`，无范围检查 |
| 7 | 音量 | i18n 两个"音量"标签完全同名 | 🟢 低 | 翻译键名冲突 |
| 8 | 延迟 | LLM 推理占 50% 延迟（~2500ms） | 🔴 高 | system prompt 2000-4000 tokens + 流式生成长文本 |
| 9 | 延迟 | TTS 等标点才合成首段（~800ms） | 🟡 中 | `_get_segment_text()` 必须等到标点 |
| 10 | 延迟 | Memory 查询同步阻塞（`future.result()`） | 🟡 中 | 同步等待，不可与上下文组装并行 |
| 11 | 延迟 | Python GIL 竞争、队列缓冲、线程切换 | 🟢 低 | 多线程架构固有开销 |
| 12 | 音色 | 豆包 TTS 只有一个音色可选 | 🔴 高 | 迁移脚本只保留了 1 个验证过的音色 |
| 13 | 音色 | 豆包 Seed TTS 2.0 仅支持 1 个 resource_id | 🟡 中 | `huoshan_double_stream.py` 硬编码校验 |
| 14 | 记忆 | 记忆总结保存到 YAML 而非 MySQL | 🔴 高 | `save_to_file=True` 时走文件路径 |
| 15 | 记忆 | Java 侧总结生成静默失败 | 🟡 中 | 异步线程中执行，6 个失败点 |
| 18 | 聊天记录 | 多设备会话混在一起，无法按设备分类筛选 | 🔴 高 | 会话列表查询未利用已有 `mac_address` 字段 |
| 19 | 聊天记录 | `AgentChatSessionDTO` 缺少 `macAddress` 字段 | 🟡 中 | DTO 设计时未考虑设备维度 |
| 20 | 聊天记录 | 前端会话列表无设备标签、无筛选下拉 | 🟡 中 | UI 未考虑多设备场景 |
| 21 | 模型管理 | Model Type 枚举在 3 处不一致（5/7/9/10 种） | 🔴 高 | 各模块独立定义，从未统一 |
| 22 | 模型管理 | Python/Java 两套模型注册体系互不对齐 | 🔴 高 | Python 文件系统发现 vs Java DB 驱动 |
| 23 | 模型管理 | TTS 配置盲区：19 个适配器仅 1 个可通过 UI 配置 | 🔴 高 | config.yaml 和 DB 均只暴露豆包 TTS |
| 24 | 模型管理 | 火山引擎 TTS 特殊处理硬编码在通用 Assembler 中 | 🟡 中 | 7 个专用方法 + 4 个常量不应在通用层 |
| 25 | 模型管理 | SLM 概念模糊，Python 侧无 SLM 支持 | 🟡 中 | SLM 与 LLM 完全共用代码，仅在 Java 侧区分 |
| 26 | 模型管理 | 废弃模型管理缺失：旧代码文件、旧 DB 行、无标记 | 🟡 中 | 三处（代码/DB/配置）废弃不一致 |
| 27 | 模型管理 | 相同概念在不同层用不同键名（5 种键名混乱） | 🟡 中 | 各层独立命名，无统一 schema |
| 28 | 模型管理 | 前端硬编码大量 provider 判断，新增 provider 易遗漏 | 🟡 中 | 特殊逻辑未走配置驱动 |
| 29 | 语义打断 | 仅靠 VAD 能量判断，无法区分真打断 vs 咳嗽/噪声 | 🔴 高 | VAD 只输出概率值，无语义分析 |
| 30 | 语义打断 | 简单附和（"嗯""好的"）被当作打断触发完整中断流程 | 🟡 中 | 无 backchannel 过滤，ASR 识别到任何文字都打断 |
| 31 | 语义打断 | 打断决策滞后 1.5s：VAD 等静音 + ASR 识别完成后才判断 | 🟡 中 | 决策在管道后端，不在检测到语音的前端 |
| 32 | 语义打断 | VAD 阈值全局静态（0.5/0.2），无法按设备/用户个性化 | 🟡 中 | 无 per-device 参数配置通道 |
| 33 | 语音管道 | 管道架构丢失音频情绪、语速、环境信息 | 🟡 中 | ASR 只输出文字，LLM/TTS 不接触原始音频 |
| 34 | 数据战略 | 缺乏用户交互数据采集基础设施（原始音频、打断事件） | 🔴 高 | 当前仅上报文本聊天记录，无音频数据积累 |
| 35 | 数据战略 | 无训练数据导出工具和隐私脱敏机制 | 🟢 低 | 尚未进入数据采集阶段 |

### 严重程度说明

- 🔴 **高**：直接影响用户体验或功能可用性
- 🟡 **中**：影响特定场景或存在潜在风险
- 🟢 **低**：改善项，不影响核心功能

---

## 修复建议方向（仅记录）

### 音量系统
1. 明确概念边界：设备音量 vs TTS 音量 vs 浏览器音量
2. 统一 TTS 音量范围到 0-100，各厂商做范围映射
3. Java DTO 加 `@Min(0) @Max(100)`，Python MCP 执行器加范围检查
4. 修复 `AgentServiceImpl.createAgent()` 从模板复制 `ttsVolume`/`ttsRate`/`ttsPitch`
5. i18n 区分标签："设备音量" vs "语音响度"

### 管道延迟
1. 精简 system prompt，按场景动态裁剪（闲聊时不注入音乐库/游戏提示词）
2. Memory 查询改为 `await` 异步化，与上下文组装并行
3. TTS 首段粒度调优：按固定字数切分，减少等标点时间
4. 换更快的 TTS 厂商或使用本地推理
5. 工具定义按需加载，不每次全量传

### 豆包音色
1. 在 `ai_tts_voice` 表中补全火山引擎 Seed TTS 2.0 支持的其他音色
2. 验证后再添加，避免引入不可用的 speaker ID

### 记忆总结
1. 生产模式下确保 `save_to_file=False`，使总结写入 MySQL
2. Java 侧总结生成改为同步等待或提供状态查询接口
3. 失败时在 Python 日志中输出明确错误信息
4. 前端切换记忆模型时不立即清空 `summaryMemory`

### 聊天记录设备分类
1. `AgentChatSessionDTO` 新增 `macAddress` 字段
2. `getSessionListByAgentId()` SQL 增加 `mac_address` 返回，支持 `macAddress` 过滤参数
3. `AgentController.getAgentSessions()` 接受 `macAddress` 可选查询参数
4. 提供 `GET /{agentId}/devices` 接口，返回智能体下所有设备列表
5. 前端 `ChatHistoryDialog.vue` 增加设备下拉筛选器，会话列表每条显示设备标签

### 语义打断
1. 新增轻量语义分类器（复用 Intent LLM），输入流式 ASR 部分结果，输出打断意图分类（真打断 / backchannel / 噪声）
2. 新增附和词表（"嗯""好的""对""知道了""哦"），匹配到的短文本不触发打断
3. 流式 ASR 中间结果用于预判：不等 1s 静音也不等完整 ASR 结果，检测到语音 300ms 后即开始分析
4. 个性化 VAD 阈值：按 device MAC 存储调整后的 VAD 参数，支持从管理后台配置
5. 打断平滑淡出：ESP32 端收到 stop 后 50ms 音量渐变，替代当前暴力静音
6. 打断上下文保留：被打断的 LLM 文本存入临时缓存，用户说"继续"时恢复

### 端到端模型与数据战略
1. **数据采集基础设施**（P0）：新增交互数据采集器，记录音频交互对、打断事件时间戳、用户隐式反馈
2. **数据隐私脱敏**（P0）：自动检测和移除 PII（姓名、电话、地址），提供用户数据查看/导出/删除功能
3. **冷启动数据策略**：混合使用公开中文语音数据集（AISHELL-3、wenetspeech）+ 自有数据 + 合成数据
4. **Phase 1（当前）**：管道优化 + 语义打断 + 数据采集就绪。零额外算力需求
5. **Phase 2（1-2 年）**：混合端到端原型。推荐 Freeze-Omni 架构（冻结 LLM + 语音编解码器），4-8 GPU 训练 3-7 天。保留工具调用能力
6. **Phase 3（2-3 年）**：完整端到端模型。推荐 Thinker-Talker 架构（Qwen3-Omni 模式），8-64 GPU 训练。降低对第三方 API 的依赖

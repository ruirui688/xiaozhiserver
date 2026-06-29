# 豆包 TTS 只有一个音色可选 — 问题分析

> 分析日期：2026-06-25 | 分支：feature/model-performance-benchmark | 状态：仅记录，不修复

---

## 问题现象

豆包（火山引擎）TTS 语音合成在管理界面**只能看到一个音色**"魅力苏菲"，无法选择其他音色。

用户反馈：_"为什么这里的豆包的语音合成不支持别的音色，这里只能选择一个，一点都不合理"_

---

## 根因分析

### 1. 直接原因：数据库 `ai_tts_voice` 表只插入了一条记录

音色列表来自数据库表 `ai_tts_voice`，查询逻辑：

```
前端 roleConfig.vue → Api.model.getModelVoices(modelId)
  → ModelController.getVoiceList(modelId)
    → TimbreServiceImpl.getVoiceNames(ttsModelId, voiceName)
      → SELECT * FROM ai_tts_voice WHERE tts_model_id = ?
```

迁移脚本 `db/changelog/202606251815.sql:49-60` **只插入了一条音色**：

```sql
INSERT INTO `ai_tts_voice`
(`id`, `tts_model_id`, `name`, `tts_voice`, `languages`, `voice_demo`, `remark`, ...)
VALUES
('TTS_HSDSTTS_V2_SOPHIE', @seed_tts_model_id, '魅力苏菲',
 'zh_female_sophie_uranus_bigtts', '普通话、英语', NULL,
 '唯一保留音色，和 Seed TTS 2.0 探测脚本一致。', ...)
```

注意 `remark` 字段明确指出：**"唯一保留音色，和 Seed TTS 2.0 探测脚本一致"**。

而且迁移脚本在第 46-47 行**先清空了旧的音色记录**：

```sql
DELETE FROM `ai_tts_voice`
WHERE `tts_model_id` = @seed_tts_model_id;
```

### 2. 不是火山引擎 API 不支持其他音色

火山引擎 Seed TTS 2.0 双向流式 API **支持多种音色**。证据：

- 旧版豆包 HTTP TTS 配置（`202504221555.sql:3`）用的是 `zh_male_shaonianzixin_moon_bigtts`（少年梓欣-男声）
- 探测脚本 `scripts/test_huoshan_tts.py:28` 把 `speaker` 做成可配置参数，默认 `zh_female_sophie_uranus_bigtts`
- Python 侧 `core/providers/tts/huoshan_double_stream.py:171-174` 直接透传 `speaker` 给火山 API：

```python
# huoshan_double_stream.py:171-174
if config.get("private_voice"):
    self.voice = config.get("private_voice")
else:
    self.voice = config.get("speaker")
```

每次 TTS 请求都会把 `speaker` 传给火山引擎：

```python
# huoshan_double_stream.py:496
await self.send_text(self.voice, txt, self.conn.sentence_id)
```

```python
# huoshan_double_stream.py:719-720 — payload 构造
payload = self.get_payload_bytes(
    event=EVENT_TaskRequest, text=text, speaker=speaker
)
```

**代码层面完全没有限制音色数量**，任何有效的 `speaker` ID 都可以工作。

### 3. 为什么只保留一个？—— 迁移保守策略

项目从旧版豆包 HTTP TTS → Seed TTS 2.0 双向流式 WebSocket 的迁移过程中（2026-06-25），采用了保守策略：

1. **旧版豆包 HTTP TTS** (`doubao.py`) 已废弃，`__init__` 直接抛 `RuntimeError`：
   ```python
   # doubao.py
   DEPRECATION_MESSAGE = (
       "DoubaoTTS HTTP provider 已不再作为本项目有效豆包 TTS 入口；"
       "请使用 TTS.DoubaoSeedTTS2Stream..."
   )
   ```

2. **新版只支持** `resource_id = "seed-tts-2.0"`（`huoshan_double_stream.py:165-168` 有校验，其他 resource_id 会报 `ValueError`）

3. **音色清空重建**：迁移脚本 `DELETE` 了旧音色，只插回了**一个经过探测脚本验证的**音色——确保不引入未验证的音色导致 TTS 调用失败

4. **模板字段固定**：`ai_model_template_field` 的 default_value 也锁定为 `zh_female_sophie_uranus_bigtts`（第 65 行）

**这是安全起见的人为保守策略，不是技术限制。**

---

## 完整数据流

```
Web 管理端 roleConfig.vue          移动端（暂无音色选择 UI）
       │
       ▼
GET /model/{modelId}/voices  ────  ModelController.getVoiceList()
       │
       ▼
TimbreServiceImpl.getVoiceNames(ttsModelId)
       │
       ▼
SELECT * FROM ai_tts_voice
WHERE tts_model_id = 'TTS_HSDSTTS_V2'  ────  结果：只有 1 条记录
       │
       ▼
前端音色下拉框：只有「魅力苏菲」一个选项
       │
       ▼
用户选择 → ttsVoiceId 写入 ai_agent.tts_voice
       │
       ▼
ConfigServiceImpl 输出 speaker → Python TTSProvider 透传给火山 API
```

---

## 涉及的代码位置

### 音色数据层

| 层级 | 文件 | 行号 | 说明 |
|---|---|---|---|
| DB 迁移 | `db/changelog/202606251815.sql` | 46-60 | DELETE 旧音色 + INSERT 唯一音色 |
| DB 表 | `ai_tts_voice` | - | 音色存储表，按 `tts_model_id` 查询 |
| Entity | `timbre/entity/TimbreEntity.java` | - | JPA 实体，映射 `ai_tts_voice` 表 |
| Service | `timbre/service/impl/TimbreServiceImpl.java` | 121-159 | `getVoiceNames()` 查询音色列表 |
| Controller | `model/controller/ModelController.java` | 307-314 | `/model/{modelId}/voices` API |

### Python TTS 提供商（音色透传）

| 文件 | 行号 | 说明 |
|---|---|---|
| `core/providers/tts/huoshan_double_stream.py` | 171-174 | 从 config 读取 `speaker` 或 `private_voice` |
| `core/providers/tts/huoshan_double_stream.py` | 496 | 每次 TTS 请求携带 speaker |
| `core/providers/tts/huoshan_double_stream.py` | 719-720 | payload 构造含 speaker |
| `core/providers/tts/doubao.py` | 1-17 | 旧版 HTTP TTS 已废弃 |
| `scripts/test_huoshan_tts.py` | 28 | 探测脚本，speaker 可配置 |

### 前端音色选择

| 文件 | 行号 | 说明 |
|---|---|---|
| `roleConfig.vue` | 444-453 | 音色选择器（El-Select） |
| `roleConfig.vue` | 937-974 | `getModelVoices` → 构建 voiceOptions |
| `roleConfig.vue` | 978-1011 | `filterVoicesByLanguage()` 语言筛选 |
| `components/TtsModel.vue` | - | 音色管理弹窗（增删改查） |

### 模板固定

| 文件 | 行号 | 说明 |
|---|---|---|
| `db/changelog/202606251815.sql` | 62-66 | 模板 default_value 固定为唯一音色 |

---

## 如何增加其他音色

### 方式 1：通过 Web 管理界面

模型配置 → TTS 模型 → 豆包语音合成 2.0 → 点击音色管理（TtsModel 弹窗）→ 添加新行

填入参数：
- `voiceCode`: 火山引擎 speaker ID
- `voiceName`: 显示名称
- `languageType`: 支持的语言

### 方式 2：直接 SQL 写入

```sql
INSERT INTO ai_tts_voice
(id, tts_model_id, name, tts_voice, languages, sort, creator, create_date, updater, update_date)
VALUES
('TTS_HSDSTTS_V2_0002', 'TTS_HSDSTTS_V2', '少年梓欣',
 'zh_male_shaonianzixin_moon_bigtts', '普通话', 2, 1, NOW(), 1, NOW()),
('TTS_HSDSTTS_V2_0003', 'TTS_HSDSTTS_V2', '甜美女声',
 'zh_female_tianmei_moon_bigtts', '普通话', 3, 1, NOW(), 1, NOW());
```

### 前提条件

⚠️ 添加前需确认这些 speaker ID 在**火山引擎 Seed TTS 2.0 双向流式 API** 中确实有效。种子 TTS 2.0 的 speaker 格式通常为 `zh_female_<name>_<model>_bigtts` 或 `zh_male_<name>_<model>_bigtts`。

---

## 结论

**这不是技术限制，是项目迁移时人为只保留了一个已验证的音色。** 旧版迁移清理掉了之前所有音色记录，新版只加了回一个探测脚本确认通过的默认值，并未补全火山引擎支持的其他音色。代码架构完全支持多音色，只需要在 `ai_tts_voice` 表中添加更多经过验证的记录即可。

相关问题：
- [[volume-system-analysis]] — TTS 音量范围混乱问题
- 豆包 Seed TTS 2.0 仅支持 `seed-tts-2.0` 这一个 resource_id（`huoshan_double_stream.py:160-168` 硬编码限制），无法使用火山引擎的其他语音服务

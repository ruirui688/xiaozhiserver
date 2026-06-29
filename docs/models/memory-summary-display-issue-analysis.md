# 短期记忆总结不显示在角色配置界面 — 问题分析

> 分析日期：2026-06-25 | 分支：feature/model-performance-benchmark | 状态：仅记录，不修复

---

## 问题现象

用户反馈设备已经开启了短期记忆总结（`mem_local_short`），Python 日志也显示"Save memory successful"，但角色配置界面的「记忆历史」文本框始终为空，没有显示任何总结内容。

---

## 根因分析

### 核心问题：`mem_local_short` 存在两条完全不同的保存路径

`mem_local_short.py` 根据 `save_to_file` 标志走两个互斥的保存路径：

```python
# mem_local_short.py:135-193
async def save_memory(self, msgs, session_id=None):
    # ...
    if self.save_to_file:
        # 路径 A：Python LLM 本地总结 → 写入 YAML 文件
        result = self.llm.response_no_stream(...)
        self.short_memory = json_str
        self.save_memory_to_file()  # → data/.memory.yaml
    else:
        # 路径 B：调用 Java API → Java 侧生成总结 → 写入 MySQL
        await generate_and_save_chat_summary(summary_id)
```

`save_to_file` 的值由 `connection.py:908` 决定：

```python
self.memory.init_memory(
    role_id=self.device_id,
    llm=self.llm,
    summary_memory=self.config.get("summaryMemory", None),
    save_to_file=not self.read_config_from_api,  # ← 关键！
)
```

| `read_config_from_api` | `save_to_file` | 保存路径 | 是否写入 MySQL |
|---|---|---|---|
| `True`（生产模式） | `False` | 路径 B → Java API | ✅ 是 |
| `False`（开发/本地模式） | `True` | 路径 A → YAML 文件 | ❌ 否 |

**当 `save_to_file=True` 时，记忆被保存到 `data/.memory.yaml` 文件，永远不进 MySQL。角色配置界面读取的是 MySQL `ai_agent.summary_memory` 字段，所以界面上看不到。**

### 问题 2：Java 侧总结生成是异步的，容易静默失败

即使走路径 B（生产模式），总结生成也依赖多个条件：

```java
// AgentController.java:136-155
@PostMapping("/chat-summary/{sessionId}/save")
public Result<Void> generateAndSaveChatSummary(@PathVariable String sessionId) {
    // 在一个新线程中异步执行，立即返回 200
    new Thread(() -> {
        agentChatSummaryService.generateAndSaveChatSummary(sessionId);
    }).start();
    return new Result<Void>().ok(null);
}
```

Python 侧调用 API 后**不等待**总结生成完成，立即返回。`save_memory` 末尾的日志：

```python
logger.bind(tag=TAG).info(
    f"Save memory successful - Role: {self.role_id}, Session: {session_id}"
)
```

这条日志**只是表示 API 调用成功（HTTP 200），不代表总结已生成并写入数据库。**

#### Java 侧总结生成的失败点

`AgentChatSummaryServiceImpl.generateAndSaveChatSummary()` 有以下失败点：

**失败点 1：找不到设备**

```java
DeviceEntity device = getDeviceBySessionId(sessionId);
if (device == null) {
    log.info("未找到与会话 {} 关联的设备", sessionId);
    return false;  // 静默失败
}
```

`getDeviceBySessionId` 查询 `agent_chat_history` 表的 `mac_address`，再查设备表。如果聊天记录尚未刷入数据库，或 session_id 不匹配，就直接返回。

**失败点 2：找不到智能体**

```java
String agentId = device.getAgentId();
String memModelId = agentService.getAgentById(agentId).getMemModelId();
```

如果设备未绑定智能体，或 `agentId` 为空，`agentService.getAgentById(agentId)` 会抛异常。

**失败点 3：记忆模型类型被跳过**

```java
if (memModelId == null || memModelId.equals(Constant.MEMORY_MEM_REPORT_ONLY)) {
    log.info("使用仅上报聊天记录模式，跳过记忆总结");
    return true;
}

boolean shouldSummarizeMemory = !memModelId.equals(Constant.MEMORY_NO_MEM)
        && !memModelId.equals(Constant.MEMORY_MEM0AI)
        && !memModelId.equals(Constant.MEMORY_POWERMEM);
```

只有 `Memory_mem_local_short` 才会真正生成总结。`Memory_mem_report_only` / `Memory_nomem` 等都会被跳过。

**失败点 4：聊天记录为空**

```java
List<AgentChatHistoryDTO> chatHistory = getChatHistoryBySessionId(sessionId);
if (chatHistory == null || chatHistory.isEmpty()) {
    return new AgentChatSummaryDTO(sessionId, "未找到该会话的聊天记录");
}
```

如果本次会话的聊天记录还未上报完成，或 `agent_chat_history` 表中没有该 session_id 的记录，总结生成会失败。

**失败点 5：LLM 服务不可用**

```java
String summary = callJavaLLMForSummaryWithHistory(conversation, historyMemory, agentId);
// 如果 SLM 模型未配置或调用失败，会抛出异常
```

**失败点 6：更新智能体的事务问题**

`generateAndSaveChatSummary` 在**非 Spring 管理的原始线程**中运行，`@Transactional` 注解在该线程上下文中可能不生效。如果 `updateAgentById` 的事务未正确提交，`summaryMemory` 不会被持久化。

### 问题 3：Python 侧 ManageApiClient 可能未初始化

```python
# mem_local_short.py:193
await generate_and_save_chat_summary(summary_id)

# manage_api_client.py:198-207
async def generate_and_save_chat_summary(session_id: str) -> Optional[Dict]:
    try:
        return await ManageApiClient._instance._execute_async_request(
            "POST", f"/agent/chat-summary/{session_id}/save",
        )
    except Exception as e:
        print(f"生成并保存聊天记录总结失败: {e}")  # 仅打印，不抛异常
        return None
```

如果 `ManageApiClient._instance` 为 `None`（未初始化），调用会失败但异常被吞掉。

### 问题 4：前端 UI 在切换记忆模型时会清空显示

```javascript
// roleConfig.vue:1049-1055
if (value === "Memory_nomem" || value === "Memory_mem_report_only") {
    this.tempSummaryMemory = this.form.summaryMemory;
    this.form.summaryMemory = "";  // ← 清空！
}
```

如果用户切换过记忆模型（比如从 `mem_local_short` 切到 `mem_report_only` 再切回来），`summaryMemory` 会被清空。虽然切换回 `mem_local_short` 时会尝试恢复：

```javascript
} else if (this.tempSummaryMemory !== "" && this.form.summaryMemory === "") {
    this.form.summaryMemory = this.tempSummaryMemory;  // 恢复
    this.tempSummaryMemory = "";
}
```

但如果在清空后刷新了页面，`tempSummaryMemory` 就丢失了。

---

## 完整数据流

```
┌─ 路径 A: save_to_file=True ─────────────────────────────────────┐
│                                                                   │
│  Python mem_local_short.save_memory()                             │
│    → LLM 生成本地总结                                              │
│    → 写入 data/.memory.yaml                                       │
│    → 记录日志 "Save memory successful"                             │
│    → ❌ 不写入 MySQL                                               │
│    → ❌ 角色配置界面看不到                                          │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘

┌─ 路径 B: save_to_file=False ─────────────────────────────────────┐
│                                                                   │
│  Python mem_local_short.save_memory()                             │
│    → 调用 Java API POST /agent/chat-summary/{sessionId}/save     │
│    → Java AgentController 接收请求                                 │
│    → 启动新线程（异步）→ 立即返回 200                               │
│    → Python 收到 200 → 记录日志 "Save memory successful"           │
│                                                                   │
│    异步线程中:                                                      │
│    → AgentChatSummaryServiceImpl.generateAndSaveChatSummary()     │
│    → 从 agent_chat_history 查 session_id → mac_address            │
│    → 从 ai_device 查设备 → agent_id                                │
│    → 从 ai_agent 查智能体 → memModelId                             │
│    → 检查: 是否为 mem_local_short?                                 │
│      ├─ 否 → 跳过                                                  │
│      └─ 是 → 查询聊天记录                                          │
│              → 调用 LLM/SLM 生成总结                                │
│              → agentService.updateAgentById(summaryMemory)         │
│              → 写入 ai_agent.summary_memory                        │
│                                                                   │
│  任一环节失败 → 静默失败，仅打印日志                                 │
│                                                                   │
│  成功 → MySQL 写入 summaryMemory                                   │
│       → 下次 config API 调用时返回 updated summaryMemory            │
│       → 角色配置界面显示                                            │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘

┌─ 角色配置界面加载 ────────────────────────────────────────────────┐
│                                                                   │
│  roleConfig.vue → fetchAgentConfig(agentId)                       │
│    → GET /agent/{id}                                              │
│    → AgentServiceImpl.getAgentById(id)                            │
│    → AgentDao.selectAgentInfoById(id)                             │
│    → SQL: SELECT summary_memory AS summaryMemory FROM ai_agent    │
│    → 返回 AgentInfoVO.summaryMemory                                │
│    → 前端: form.summaryMemory = data.data.summaryMemory           │
│    → 文本框 v-model="form.summaryMemory"                           │
│                                                                   │
│  文本区域被禁用条件:                                                 │
│    :disabled="form.model.memModelId !== 'Memory_mem_local_short'" │
│                                                                   │
│  注：disabled 只是不可编辑，内容仍然可见                              │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘
```

---

## 涉及的关键文件

| 层级 | 文件 | 行号 | 说明 |
|---|---|---|---|
| Python | `core/providers/memory/mem_local_short/mem_local_short.py` | 98-202 | 记忆保存入口，两条路径分支 |
| Python | `core/providers/memory/mem_local_short/mem_local_short.py` | 135-198 | `save_memory()` → `save_to_file` 分支 |
| Python | `core/providers/memory/mem_local_short/mem_local_short.py` | 190-193 | 路径 B：调用 Java API |
| Python | `core/providers/memory/mem_local_short/mem_local_short.py` | 194-196 | 日志 "Save memory successful" |
| Python | `core/connection.py` | 904-908 | `init_memory()` → `save_to_file` 决定 |
| Python | `core/connection.py` | 312-339 | `_save_and_close()` → 守护线程调用 `save_memory` |
| Python | `core/connection.py` | 298-310 | `_run_async_in_daemon_thread()` → 新线程+新事件循环 |
| Python | `config/manage_api_client.py` | 198-207 | `generate_and_save_chat_summary()` → HTTP 调用 Java API |
| Java | `agent/controller/AgentController.java` | 136-155 | `/chat-summary/{sessionId}/save` → 异步线程启动 |
| Java | `agent/service/impl/AgentChatSummaryServiceImpl.java` | 94-136 | `generateAndSaveChatSummary()` 核心逻辑 |
| Java | `agent/service/impl/AgentChatSummaryServiceImpl.java` | 110-112 | `shouldSummarizeMemory` 判断 |
| Java | `agent/service/impl/AgentChatSummaryServiceImpl.java` | 503-519 | `getDeviceBySessionId()` 查找设备 |
| Java | `agent/service/impl/AgentChatSummaryServiceImpl.java` | 498 | `getMemorySummaryModelId()` 获取 SLM 模型 |
| Java | `agent/service/impl/AgentServiceImpl.java` | 278-344 | `updateAgentById()` → `summaryMemory` 写入 DB |
| Java | `agent/entity/AgentEntity.java` | 78 | `summaryMemory` DB 字段映射 |
| Java | `config/service/impl/ConfigServiceImpl.java` | 238 | 将 `summaryMemory` 输出到设备配置 |
| Java | `config/service/impl/ConfigServiceImpl.java` | 544 | `result.put("summaryMemory", summaryMemory)` |
| MyBatis | `mapper/agent/AgentDao.xml` | 33, 76 | SQL: `summary_memory AS summaryMemory` |
| Vue | `roleConfig.vue` | 140 | `v-model="form.summaryMemory"` |
| Vue | `roleConfig.vue` | 144 | `:disabled="memModelId !== 'Memory_mem_local_short'"` |
| Vue | `roleConfig.vue` | 789 | `fetchAgentConfig()` → `tempSummaryMemory = ""` |
| Vue | `roleConfig.vue` | 1049-1055 | `handleModelChange()` → 切换记忆模型时清空 |

---

## 排查建议

### 1. 确认当前使用的是哪条保存路径

```bash
# 查看 Python 日志，确认 save_to_file 值
grep -n 'save_to_file\|read_config_from_api' <log_file>
```

### 2. 检查 YAML 文件（路径 A）

```bash
cat data/.memory.yaml
# 如果文件中有内容但角色配置界面为空，说明走的是路径 A（YAML），需要切换到路径 B
```

### 3. 检查 Java 侧总结生成日志（路径 B）

```bash
# 查看 Java 日志中是否有总结生成记录
grep '开始生成会话\|成功生成会话\|未找到与会话\|跳过记忆总结\|保存会话' <java_log>
```

### 4. 确认数据库中的 summaryMemory 字段

```sql
SELECT id, agent_name, mem_model_id, summary_memory
FROM ai_agent
WHERE mem_model_id = 'Memory_mem_local_short';
```

### 5. 确认设备绑定的智能体和记忆模型

```sql
SELECT d.mac_address, d.agent_id, a.agent_name, a.mem_model_id
FROM ai_device d
JOIN ai_agent a ON d.agent_id = a.id
WHERE d.mac_address = '<your_device_mac>';
```

---

## 结论

短期记忆总结「生成但不在界面显示」的原因有以下几个可能：

1. **最可能：走的是路径 A**（`save_to_file=True`，开发/本地模式），总结被写入 `data/.memory.yaml` 文件而非 MySQL，角色配置界面读的是 MySQL，所以看不到。

2. **可能：Java 侧异步总结生成静默失败**（路径 B），虽然 Python 日志显示 "Save memory successful"（HTTP 200），但 Java 线程中的总结生成因找不到设备/聊天记录/LLM 配置而失败。

3. **可能：记忆模型选错了** — `Memory_mem_report_only` 只上报聊天记录不总结，`Memory_nomem` 完全不记录。

4. **可能：前端 UI 清空** — 如果在角色配置界面切换过记忆模型类型，`summaryMemory` 被临时清空，刷新页面后无法恢复。

相关文档：
- [[volume-system-analysis]] — 音量系统混乱分析
- [[doubao-tts-voice-selection-analysis]] — 豆包 TTS 音色选择问题

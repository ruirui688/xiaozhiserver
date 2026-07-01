# 记忆系统完整链路

本文档说明 xiaozhi-esp32-server 长期记忆系统的完整链路：从 Python 侧事件上报、记忆查询注入，到 Java 侧 PowerMem 双阶段提取决策、事实存储与维护。

先读：[提示词注入工程](prompt-injection-engineering.md)、[长期记忆维护架构](long-term-memory-maintenance.md)。

## 1. 三层架构总览

```text
第1层：运行时注入 (Python, 进程内)
  Memory Provider → 查询/存储对话事实 → 注入 LLM 上下文

第2层：事件上报 (Python → Java HTTP)
  每轮对话 → MemorySourceEvent → manager-api 事件存储

第3层：托管记忆管道 (Java, manager-api)
  PowerMem 双阶段 LLM 管道 → 提取/去重/确认 → MySQL 持久化
```

## 2. Memory Provider 层 (Python)

### 2.1 Provider 类型

| Provider | 文件 | 查询方式 | 存储方式 |
|----------|------|---------|---------|
| `nomem` | `nomem/nomem.py` | 返回空 | No-op |
| `mem_report_only` | `mem_report_only/mem_report_only.py` | 返回空 | 仅事件上报 |
| `mem_local_short` | `mem_local_short/mem_local_short.py` | 返回缓存 `short_memory` | LLM 摘要 → YAML |
| `mem0ai` | `mem0ai/mem0ai.py` | mem0 SDK search | mem0 SDK add |
| `powermem` | `powermem/powermem.py` | PowerMem SDK search | PowerMem SDK add |
| `managed_memory` | `managed_memory/managed_memory.py` | HTTP → Java API | 委托 Java |

### 2.2 Provider 初始化

```text
utils/modules_initialize.py:64-72
  → memory.create_instance(type_name, runtime_config, summaryMemory)
  → ConnectionHandler._initialize_memory()
    → self.memory.init_memory(role_id=device_id, llm=self.llm, ...)
```

**注意**：`summaryMemory` 参数来自 `AgentEntity.summaryMemory` (Java)。只有 `mem_local_short` 使用它；`managed_memory` 和 `powermem` provider 接受但忽略（由 Java 侧独立处理摘要）。

### 2.3 记忆查询注入

```text
connection.py:_query_memory_context(query)
  1. 先查询 Java 管理记忆 (query_managed_memory)
  2. 如果 Java 返回结果 → 跳过本地 provider（避免双源冲突）
  3. 如果 Java 无结果 → 查询本地 provider
  4. 拼接结果 → 注入 LLM 动态 system prompt 的 <memory> 标签
```

```text
dialogue.py:get_llm_dialogue_with_memory(memory_str, voiceprint_config)
  1. 静态 system prompt（缓存前缀）
  2. 少量示例（is_temporary 消息）
  3. 动态上下文 + <memory>{memory_str}</memory> + 声纹信息
  4. 对话历史（截断到 max_history_turns）
```

## 3. 事件上报 (Python → Java)

### 3.1 实时上报

```text
handle/reportHandle.py:report()
  → POST /config/memory/events
    - event_id: SHA256(content) 幂等去重
    - chat_type: 1=user / 2=assistant / 3=tool
    - event_type: "chat" / "tool"
```

### 3.2 上报线程管理

```text
handle/reportThreadManager.py  ReportThreadManager
  - start(conn): 启动 2 个守护线程（ASR + TTS 上报）
  - _report_worker(): 从队列消费 → 调用 report()
  - stop(conn): 优雅停止
```

## 4. 托管记忆管道 (Java)

### 4.1 服务分层

| 服务 | 文件 | 行数 | 职责 |
|------|------|------|------|
| `MemoryServiceImpl` | `impl/MemoryServiceImpl.java` | 491 | 事件摄入编排 + 事实 CRUD |
| `MemoryQueryServiceImpl` | `impl/MemoryQueryServiceImpl.java` | 266 | 语义 + 关键词查询 |
| `MemoryMaintenanceServiceImpl` | `impl/MemoryMaintenanceServiceImpl.java` | 1061 | PowerMem 双阶段管道 + 定时衰减 |

### 4.2 数据模型

| 表 | 实体 | 用途 |
|----|------|------|
| `ai_memory_source_event` | `MemorySourceEventEntity` | 原始对话事件 |
| `ai_memory_candidate` | `MemoryCandidateEntity` | 未确认候选事实 |
| `ai_memory_fact` | `MemoryFactEntity` | 确认长期事实 (embedding + importance + tier) |
| `ai_memory_revision` | `MemoryRevisionEntity` | 变更审计日志 |
| `ai_memory_profile` | `MemoryProfileEntity` | 用户画像摘要 |
| `ai_memory_tool_behavior` | `MemoryToolBehaviorEntity` | 工具使用统计 |
| `ai_memory_forget_tombstone` | `MemoryForgetTombstoneEntity` | 遗忘模式阻止列表 |

### 4.3 PowerMem 双阶段管道

```text
Stage 1: 事实提取
  对话内容 → EXTRACTION_PROMPT + LLM → {"facts": [{text, category, memory_type, ...}]}

Stage 2: 语义搜索 + 决策
  每个事实 → generateEmbedding()
    → cosine similarity 搜索现有事实 (top 10)
    → DECISION_PROMPT + LLM → {"action": "ADD|UPDATE|DELETE|NONE", ...}
    → applyMaintenanceDecision()
      - ADD: 插入 + 去重 (SHA256 hash)
      - UPDATE: 查找 related_memory_id + 检查 canonical_text 重复
      - DELETE: 软删除
      - NONE: 跳过
```

### 4.4 三种触发场景

| 触发 | 时机 | 方法 |
|------|------|------|
| 增量提取 | 每条用户消息 (chatType=1) | `createCandidatesWithMaintenanceModel()` (runAfterCommit) |
| 会话分析 | 断开连接时 | `processSessionMemories()` (全量事件重处理) |
| 手动重建 | 管理 UI | `rebuildFromRecentChatHistory()` (多会话 + review) |

### 4.5 记忆查询

```text
MemoryQueryServiceImpl.query()
  1. 解析 scope (agent device / agent-level)
  2. 获取用户画像
  3. 语义搜索（embedding + cosine similarity）OR 关键词 LIKE 回退
  4. 获取 tool behaviors (top 5) + forget tombstones (top 10)
  5. 构建 rich context 字符串
```

### 4.6 定时衰减（新增）

```text
@Scheduled(cron = "0 0 3 * * ?")  每日凌晨 3:00
  → applyDecayToActiveFacts()
    - 加载所有 active 事实
    - Ebbinghaus 遗忘曲线: retention = initialRetention * exp(-decayRate * daysSinceLastSeen)
    - 低于 FORGET_THRESHOLD (0.15) → 归档
    - 写入 revision 审计记录
```

记忆层级：
- Working (importance < 0.4): 衰减 5%/天
- Short-term (0.4-0.7): 衰减 1%/天
- Long-term (> 0.7): 衰减 0.2%/天

## 5. 配置链路

### 5.1 Java → Python

```text
AgentEntity.memModelId → selected_module.Memory
AgentEntity.summaryMemory → config["summaryMemory"]（直接传递）
AgentEntity.chatHistoryConf → 0=不记录 / 1=纯文本 / 2=文本+音频
```

### 5.2 禁用记忆

```text
memModelId = "Memory_nomem"
  → chatHistoryConf = 0（Java 强制）
  → Python 加载 nomem provider
  → _query_memory_context() 跳过 Java API 查询
```

## 6. 关键文件

| 文件 | 行数 | 职责 |
|------|------|------|
| `core/providers/memory/base.py` | ~30 | 抽象基类 |
| `core/providers/memory/nomem/nomem.py` | ~15 | 禁用 |
| `core/providers/memory/mem_report_only/mem_report_only.py` | ~20 | 仅上报 |
| `core/providers/memory/mem_local_short/mem_local_short.py` | ~250 | LLM 摘要 + YAML |
| `core/providers/memory/mem0ai/mem0ai.py` | ~120 | mem0 cloud SDK |
| `core/providers/memory/powermem/powermem.py` | ~200 | PowerMem SDK |
| `core/providers/memory/managed_memory/managed_memory.py` | ~30 | 委托 Java |
| `core/utils/memory.py` | ~30 | Provider 工厂 |
| `core/utils/dialogue.py` | ~400 | 记忆注入 LLM 对话 |
| `core/handle/reportHandle.py` | ~50 | 事件上报 |
| `core/handle/reportThreadManager.py` | 68 | 上报线程 |
| `config/manage_api_client.py` | ~370 | HTTP API 客户端（含重试） |
| **Java 侧** | | |
| `modules/memory/service/impl/MemoryServiceImpl.java` | 491 | 编排器 |
| `modules/memory/service/impl/MemoryQueryServiceImpl.java` | 266 | 查询服务 |
| `modules/memory/service/impl/MemoryMaintenanceServiceImpl.java` | 1061 | PowerMem 管道 + 定时衰减 |
| `modules/memory/prompt/PowerMemPrompts.java` | ~150 | 所有 LLM 提示词模板 |
| `modules/memory/utils/MemoryHelper.java` | 230 | SHA256/UUID/文本清理 |
| `modules/memory/utils/MemorySimilarityUtils.java` | ~100 | Cosine/Jaccard/等价判断 |
| `modules/memory/utils/MemoryTierHelper.java` | ~80 | Ebbinghaus 衰减常量 |
| `modules/memory/entity/MemoryFactEntity.java` | ~120 | 核心事实表 |
| `modules/memory/controller/MemoryConfigController.java` | ~60 | 运行时 API |
| `modules/memory/controller/MemoryManagementController.java` | ~80 | 管理 UI API |

## 7. 已知修复（2026-07）

| # | 问题 | 修复 |
|---|------|------|
| 1 | 断开时守护线程记忆保存丢失 | 改为非守护 + join(10s) |
| 2 | 双源记忆冲突（本地 + Java） | Java 优先，有结果时跳过本地 |
| 3 | mem_local_short LLM 非 JSON 静默丢失 | JSON 修复重试 + 原始文本回退 |
| 4 | session_memory_processing HTTP 无重试 | 指数退避重试（3次） |
| 5 | embedding 失败时返回所有事实而非相似 | 返回空列表 |
| 6 | MemoryTierHelper 衰减从未调用 | @Scheduled 每日 3:00 定时任务 |
| 7 | summaryMemory 配置泄露 | 文档注释说明 managed/powermem 忽略 |
| 8 | UPDATE 路径缺少 canonical_text 去重 | 新增 source_hash 检查 + merge |

## 8. 排查方法

```bash
# 查看记忆查询注入
rg -n "query_memory|get_llm_dialogue_with_memory|_query_memory_context" \
  main/xiaozhi-server/core

# 查看 PowerMem 处理
tail -f runtime/logs/manager-api.log | grep -E "maintenance|candidate|extraction"

# 检查记忆表状态
mysql> SELECT status, count(*) FROM ai_memory_fact GROUP BY status;
mysql> SELECT memory_tier, count(*) FROM ai_memory_fact WHERE status='active' GROUP BY memory_tier;
```

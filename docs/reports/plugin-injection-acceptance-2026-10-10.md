---
status: report
owner: runtime
last_verified: 2026-10-10
source_paths:
  - main/manager-api/src/main/java/xiaozhi/modules/model/support/PluginCatalogContract.java
  - main/manager-web/src/utils/pluginCatalog.js
  - main/xiaozhi-server/core/game/interactions.py
  - main/xiaozhi-server/core/providers/intent/intent_llm
  - main/xiaozhi-server/core/session/realtime_tools.py
  - main/xiaozhi-server/tools/compare_plugin_prompt_inputs.py
  - main/xiaozhi-server/tools/evaluate_plugin_journeys.py
  - main/xiaozhi-server/tools/evaluate_intent_weather.py
---

# 插件注入与天气路由验收（2026-10-10）

本轮修复统一了目录、角色覆盖和实际运行时工具的边界；活动规则从全量常驻改成按需注入，
并移除只有时间信息的 Intent 独立回答分支。当前为工作区代码与测试结果，尚未部署。
本报告记录一次验收样本；当前合同以[提示词注入工程](../architecture/prompt-injection-engineering.md)
和[功能插件架构](../architecture/function-plugin-architecture.md)为准。

## 1. 修复后的行为

| 范围 | 修复结果 |
| --- | --- |
| 静态目录 | 显示生命周期、启用条件、注入阶段与模型通路；不把目录条数称为本轮实时工具数 |
| 角色规则 | 缺字段继承目录，显式空值保留；页面、保存与运行时使用同一语义，取消勾选再选择也保留待保存值 |
| 重复配置 | 删除 Web 独立推荐规则；工具 description 归目录，角色只保存覆盖值和业务参数 |
| 退役与删除 | 退役项禁止编辑/启用/执行；删除目录清理映射、提交后刷新；旧页面保存失效 ID 被拒绝 |
| 语言活动 | 固定空参数的短工具启动，完整规则只进入当前活动；续聊不重复启动，退出清除规则 |
| 提示词重复 | 曲库只保留一份；禁工具请求过滤不匹配的工具示例；搜索 schema 不保存过期时间 |
| 模型通路 | 明确原生调用、Intent 分类、Realtime 与托管应用的不同能力，未支持的通路不声称已注入 |
| Realtime | 续轮规则与禁工具配置 ACK 后再返回结果；同批逐项门控，打断取消旧任务，恢复 ACK 前不提交新输入 |
| 天气 Intent | 删除 result_for_context 时间专用回答；按事实目标选择真实工具；历史/缓存/过期分类结果按身份与配置隔离 |

新增 `plugin-interaction-control-20261010.sql` 只登记不存在的条件内置 `end_interaction` 目录项，
已加入 Liquibase master，没有修改旧 changeSet、执行数据库迁移或自动启用角色。
`core/connection.py` 的请求组装职责下沉到 `core/session/`，没有继续加入插件业务逻辑。

主要文件范围（另含对应测试）：

- Web：`FunctionPluginManagement.vue`、`FunctionDialog.vue`、`role/RoleEditDialog.vue`、
  `resource/PluginResourceCard.vue`、新增 `resource/PluginRuleOverrides.vue` 与 `utils/pluginCatalog.js`。
- Java：新增 `model/support/PluginCatalogContract.java`；修改 `ModelProviderDTO`、
  `ModelProviderServiceImpl`、`AgentPluginMappingServiceImpl`、`AgentServiceImpl`、`ConfigServiceImpl`；
  新增目录迁移并登记 `db.changelog-master.yaml`。
- Python 活动/工具：`core/game/{base,freeform,interactions}.py`、
  `core/providers/tools/server_plugins/{plugin_executor,policy}.py`、`unified_tool_manager.py`。
- Python 请求/控制：`core/session/{prompt_context,tool_examples,realtime_tools,conversation_context}.py`、
  `core/utils/dialogue.py`、`core/connection.py`、`plugins_func/functions/change_role.py`；
  `core/providers/realtime/{qwen_omni_realtime,doubao_duplex}.py`。
- 天气路由：`core/handle/intentHandler.py`、`core/providers/intent/intent_llm/{intent_llm,routing}.py`。
- 验收工具：`tools/{evaluate_plugin_journeys,compare_plugin_prompt_inputs,evaluate_intent_weather,
  evaluate_prompt_journeys,evaluate_activity_prompts}.py`；架构文档、本地定制记录与文档索引同步更新。

## 2. 提示词减少多少

这里的“提示词”按供应商 `usage.prompt_tokens` 计算，包含完整 messages 与 tools，
不只是 system 字符数。模型均为服务器已有的 `deepseek-v4-flash`，temperature=0.3，
thinking=disabled，max_tokens=650。

基线为**本轮插件修复开始前的工作区源码快照**，已经包含上一阶段正确性修复，
不是官方原版或最初未修复代码。因此不能把本表与前一阶段不同样本直接相减。

| 口径 | 修复前 | 修复后 | 减少 |
| --- | ---: | ---: | ---: |
| 固定 9 个请求：平均输入 token/请求 | 2,793.78 | 1,806.11 | 987.67（35.35%） |
| 固定 9 个请求：输入合计 | 25,144 | 16,255 | 8,889 |
| 自然 10 轮对话：平均输入 token/用户轮 | 3,840.00 | 2,874.70 | 965.30（25.14%） |
| 自然 10 轮对话：输入合计 | 38,400 | 28,747 | 9,653 |
| 自然 10 轮对话：模型请求数 | 12 | 13 | 增加 1 次 |

严格配对采用相同角色、用户、助手历史、工具结果、曲库和时间，初始化同样的 system 与 7 条示例。
两版均由真实 Connection.chat/OpenAI SDK 序列化请求，18 次模型调用只用于获取真实用量，
输出不写入下一个配对样本。新版本实际过滤示例、缩短工具说明与按需活动均属于被测改动。

自然多轮样本让各版模型回答继续进入后续历史，因此衡量的是这组交互的实际消耗，
不是相同历史下的纯压缩率。原生路径通常在启动、切换、退出时需要“选工具＋回复”两次请求，
后续普通活动轮只需一次；短会话或频繁切换的节省比例可能更小，个别轮次可能更高。
两种口径都不是按生产流量加权的平均值，也不是金额或延迟保证。

固定配对的明细：

| 场景 | 修复前 | 修复后 |
| --- | ---: | ---: |
| 普通问候 | 3,012 | 2,003 |
| 睡前放松，含固定历史 | 3,028 | 2,019 |
| 问曲库，不播放 | 3,015 | 2,006 |
| 问接龙规则，不开始 | 3,016 | 2,007 |
| 拒绝活动，改聊月亮 | 3,014 | 2,005 |
| 已开始接龙，继续求提示 | 3,053 | 2,436 |
| 已开始朗诵，重读第三段 | 3,069 | 2,242 |
| 音乐结果回注，禁用后续工具 | 1,974 | 578 |
| 活动结果回注，禁用后续工具 | 1,963 | 959 |

前一阶段因补回必要角色、身份、记忆及工具结果，部分请求会变长；正确性修复本身不等于压缩。
本轮的节省来自删除重复信息、减少闲置玩法规则和过滤无效示例，同时保留必要上下文。

## 3. 真实模型交互与天气原因

插件路径完成 10 用户轮、13 次模型请求：普通聊天、问曲库但不播放、问玩法但不开始、
明确拒绝活动、开始接龙、“这局不要结束”、退出换话题、完整三段朗诵、重读第三段。
结构与行为断言通过，朗诵未被旧通用短句/猜谜规则截断。基线同脚本完成 10 轮、12 请求。

截图中的天气失败机制在旧代码中确实存在：若分类器返回 `result_for_context`，
就由 Intent 模型用日期、时间、农历另行回答，天气工具不会被调用。
这支持“路由误分类导致缺少天气数据”的解释；没有读取截图那次请求的完整线上日志，
不能据此断言当时天气供应商、凭据或网络正常。

修复后用真实 DeepSeek 分类与主回复，经过本地 IntentProvider → intentHandler →
统一工具边界 → Connection.chat。仅天气 API 结果使用合成数据，连续验证：

| 用户输入 | 实际路由/结果 |
| --- | --- |
| 今天天气怎么样 | get_weather；主模型依据工具中的天气回答 |
| 明天杭州会下雨吗 | get_weather；结果缺明天数据时如实说明，没有补造 |
| 请查北京今天天气 | get_weather，location=北京 |
| 那里的天气预警呢 | get_weather，location=北京、query_type=warning |
| 现在几点 | continue_chat；主模型按当前时间回答 |
| 天气这个词是什么意思 | continue_chat；正常解释词义 |
| 未启用天气时询问天气 | continue_chat；未执行天气工具，说明没有实时数据 |

有效天气验收 7 轮、14 请求，断言全部通过。首次驱动把 `response_no_stream()` 底层汇总流式
响应误标为主回复，已在 2 请求后停止；修正阶段标记后完成上述验收，没有自动重试。
旧 `evaluate_prompt_journeys.py` 同步适配新活动合同，并增加离线正常/反例回归。

## 4. 两种调用方式的选择

`function_call` 由主模型结合完整上下文和结构化 tools 选择，普通聊天通常一次调用。
`intent_llm` 先用独立提示和最近同身份历史选择，再交主模型回答，普通聊天通常两次调用；
查询后总结时两种模式通常都是两次，但前者分类上下文更少，存在额外误判入口。
Intent 工具结果后的主回复禁用工具；原生调用可以按策略继续调用，能力并不完全相同。

本项目支持原生协议的 DeepSeek 优先使用 `function_call`；不支持原生调用的适配器会降级到
`intent_llm`，所以页面选择不一定就是最终运行模式。Realtime 是另一个协议通路。
详细对照见[架构说明](../architecture/prompt-injection-engineering.md#21-两种工具调用方式与天气路由)。

## 5. 自动验证

| 验证 | 结果 |
| --- | --- |
| 项目 Conda 全量 pytest | 1103 passed；6 条现有 SDK 弃用/版本警告 |
| 项目 Conda 全模块 compileall | 通过 |
| manager-api `mvn test` | 559 项，0 失败/0 错误，17 跳过 |
| manager-web `npm run test:unit` → `npm run build` | 226 项通过，随后构建成功；保留既有 Browserslist/包体积提示 |
| diff、文档链接、迁移登记、敏感路径检查 | 通过；未修改私有配置或历史迁移 |

Python 命令使用 `PYTHONNOUSERSITE=1 PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 conda run
--no-capture-output -n xiaozhi-esp32-server python -s -m pytest -q`，避免本机 ROS pytest
自动插件依赖缺失干扰项目测试，没有使用系统 Python。Java 跳过项为依赖 MySQL/Redis 的
DeviceTest 2 项、loginControllerTest 3 项及缺少 MEMORY_TEST_MYSQL_URL 的
MemoryConversationJourneyTest 12 项；未为补齐这些测试启动长期服务。

新增天气文件共 34 项回归，覆盖错误/旧格式、缓存历史、身份变化、慢分类取消、角色/会话刷新和
带身份的完整工具回复。初期全套发现旧 Qwen `__new__` 测试夹具缺少策略状态，补齐真实初始化后
原协议断言通过。Realtime 的 reader/ACK、同批门控和取消验证均离线执行。
移动端和部署文件属于既有并行改动，本任务未修改，未重跑对应构建或生产命令。

## 6. 证据与复现边界

合成请求与响应保留在 Git 忽略的 `runtime/`，不保存密钥，也不导出生产会话、角色或记忆：

- `plugin-input-comparison-20261010-1726.json` 及同前缀 before/after/fixture/usage 文件：固定配对。
- `plugin-live-journeys-20261010.jsonl`、`plugin-baseline-live-journeys-20261010.jsonl`：自然多轮。
- `intent-weather-live-20261010-verified.jsonl`：有效天气验收；未完成首次记录单独保存。

复现工具分别为 `tools/compare_plugin_prompt_inputs.py`、`tools/evaluate_plugin_journeys.py`、
`tools/evaluate_intent_weather.py`。必须在项目 Conda 运行；云端调用须显式 `--live`，
输出路径必须是新的 `runtime/` 文件，模型凭据只在远端进程内读取，限制调用次数且不重试。
基线归档保留在本机 `/tmp/xiaozhi-plugin-fix-before.8zpSne/sources.tar.gz`，临时目录不保证长期存在。

本次未执行生产部署、数据库迁移、ESP32 真机音频、真实天气供应商调用或 Realtime 云端调用。
桥接返回完整模型结果再转换本地 SSE，因此不代表端到端流式时延、首音延迟和吞吐。
提示边界测试不保证模型永远正确；外部数据仍须按不可信内容处理。

多智能体分工：`config_origins` 负责 Java 配置合同、固定 token 配对及旧验收驱动；
`dynamic_injections` 负责 Web 目录/继承交互与 Java 保存边界；`runtime_chain` 负责 Python
活动、工具门控、Realtime ACK/取消及 Intent 身份隔离；主智能体负责组装、天气路由、
云端多轮验收、迁移登记、关键 diff 复核与最终集成验证。各自改动已集成到同一工作区。

既有 README、移动端及其他并行改动予以保留，没有提交、推送、回滚或清理用户文件/构建缓存。
运行记录留在忽略目录用于复核；只移除了本任务一次失败捕获留下的空文件，无密钥或私有配置改动。

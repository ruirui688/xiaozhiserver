# 官方上游同步策略

本文规定本项目从官方 `xiaozhi-esp32-server` 同步源码时的边界、检查清单和冲突处理规则。它与 [本地定制记录](../local-customizations.md)、[项目架构](../architecture/project-architecture.md)、[运行流程](../architecture/runtime-flows.md)、[提示词注入](../architecture/prompt-injection-engineering.md) 一起使用。

## 1. 边界定义

本项目基于官方源码二次开发，必须同时维护两条边界：

| 边界 | 含义 | 规则 |
| --- | --- | --- |
| 官方源码边界 | 来自官方 `upstream` 的通用功能、修复、依赖、协议和文档更新 | 可以同步，但不能默认覆盖本地定制 |
| 本地定制边界 | 本仓库为了业务、部署、插件、提示词、状态机、配置治理、运行稳定性做的长期改造 | 必须保护；官方同名文件变更时人工合并 |
| 私有运行边界 | 生产 `.config.yaml`、`.env`、密钥、数据库、Redis、模型文件、运行日志、上传和缓存 | 不属于官方同步内容，禁止提交或被同步覆盖 |

同步官方更新的目标不是“让本地仓库等于官方仓库”，而是在保留本地产品能力的前提下吸收官方有价值的修复和升级。

## 2. 同步前检查

开始官方同步前必须完成以下检查：

```text
1. 确认当前工作区没有与同步无关的未提交改动；如果有，先隔离或说明来源。
2. 确认 remote：origin 是私有仓库，upstream 是官方仓库。
3. 从最新 origin/main 创建 sync/upstream-YYYYMMDD 分支。
4. 读取 docs/local-customizations.md，列出本次可能受影响的本地保护点。
5. 读取 docs/architecture/project-architecture.md、runtime-flows.md、prompt-injection-engineering.md，理解配置、提示词、工具和 MCP 链路。
6. 先查看 upstream diff，再决定合并范围；不要盲目执行大范围覆盖。
7. 标记高风险区域：数据库、配置、Docker、OTA、WebSocket、设备协议、MCP、插件、PromptManager、Dialogue、UnifiedToolHandler、Intent、TTS、打断处理。
8. 确认私有运行配置和生产数据不会被纳入 Git。
```

建议同步分支命名：

```text
sync/upstream-YYYYMMDD
```

禁止：

- 在 `main` 上直接合并官方更新。
- 把本地定制分支推送到官方远端。
- 使用“接受全部 theirs”或重新拷贝官方目录的方式处理冲突。
- 为了解决冲突执行无关格式化、重排目录或删除本地文档。

## 3. 冲突处理原则

冲突分三类处理：

| 类型 | 例子 | 处理方式 |
| --- | --- | --- |
| 纯文本冲突 | 注释、文案、README 局部变化 | 保留更准确、更符合当前仓库的表述，避免整段复制 |
| 行为冲突 | 官方和本地都改了连接初始化、工具执行、配置下发、数据库字段 | 逐字段、逐函数人工合并；先写出运行链路差异，再改代码 |
| 治理冲突 | 官方新增部署方式、配置项、脚本，与本地私有部署规范冲突 | 保留本地生产安全边界；把官方新增能力转成可选配置或文档说明 |

处理行为冲突时必须回答四个问题：

```text
官方改动解决了什么问题？
本地定制保护了什么能力？
两者是否能同时保留？
合并后需要更新哪些文档、配置模板、数据库 changelog 或测试？
```

如果官方更新触及本地定制文件，不能只让测试通过，还要确认本地约定仍成立。例如工具列表仍能生成、状态机游戏仍不退回 REQLLM、设备端 MCP 超时和取消逻辑仍有效、天气接口异常仍有兜底。

## 4. 禁止覆盖的本地定制清单

以下清单来自 [本地定制记录](../local-customizations.md)，同步时必须逐项保护。实际文件列表以该文档为准，本文只做治理导航。

| 定制领域 | 禁止被官方覆盖的能力 |
| --- | --- |
| Function Plugin 顶层模型 | 服务端插件执行器、后台插件配置、声明式互动插件、`custom_plugin` 兼容字段、`parameters_schema`、`opening_message`、`instructions` 等约定 |
| 提示词和工具注入文档 | 云端运行架构、提示词注入工程、工具 schema 注入、MCP 三类链路说明 |
| xiaozhi-server 后台启动稳定性 | 非交互式后台启动不监听 stdin，开发启动脚本后台进程不受调用者进程组影响 |
| 语音回复质量 | 句首口头禅进入 TTS 和历史前清理，工具结果绑定本轮生成，打断后旧工具结果不污染下一轮 |
| 声明式互动和状态机游戏 | `guess_what_i_say`、`idiom_chain`、`xiaozhi_thinks_you_guess` 等游戏状态保存在服务端，隐藏答案不交给 LLM 维护，高频游戏优先 `Action.RESPONSE` |
| 设备端 MCP 稳定性 | 连接关闭时取消等待中的 MCP 调用，控制类 MCP 短超时并及时返回，发送失败立即回复 |
| 天气查询容错 | 和风接口异常时使用 Open-Meteo 兜底，区县输入规范化，接口错误不交给 LLM 改写成地点不存在 |
| Ubuntu 源码开发环境 | 本地源码编译、依赖安装、服务启动和验证流程不被服务器部署文档替换 |
| 配置与运行产物边界 | 私有 `.config.yaml`、密钥、生产地址、运行日志、缓存、上传、构建产物不进入 Git |

## 5. 高风险文件与人工合并要求

同步时如果官方改动触及以下区域，必须人工审查，不允许自动覆盖：

```text
main/xiaozhi-server/core/connection.py
main/xiaozhi-server/core/utils/prompt_manager.py
main/xiaozhi-server/core/utils/dialogue.py
main/xiaozhi-server/core/providers/tools/
main/xiaozhi-server/core/providers/intent/
main/xiaozhi-server/core/game_session.py
main/xiaozhi-server/core/handle/
main/xiaozhi-server/plugins_func/functions/
main/xiaozhi-server/agent-base-prompt.txt
main/xiaozhi-server/config.yaml
main/xiaozhi-server/config_from_api.yaml
main/manager-api/src/main/java/xiaozhi/modules/config/
main/manager-api/src/main/java/xiaozhi/modules/model/
main/manager-api/src/main/resources/db/changelog/
main/manager-web/src/views/FunctionPluginManagement.vue
scripts/
Dockerfile*
main/xiaozhi-server/docker-compose*.yml
docs/architecture/
docs/architecture/prompt-injection-engineering.md
docs/local-customizations.md
```

人工合并要求：

- 对配置字段做结构级对比，确认新增默认值、旧私有配置兼容性和生产覆盖方式。
- 对数据库变更确认 changelog 顺序、幂等性、回滚风险和测试数据影响。
- 对设备协议、OTA、WebSocket、MQTT、MCP 变更做端到端验证。
- 对插件、提示词和状态机变更同时检查 `function_call` 与 `intent_llm` 两种路径。
- 对 Docker 和脚本变更检查本地开发、测试环境、生产私有镜像三种场景。

## 6. 同步后验证

完成合并后至少验证：

```text
1. 工作区只包含本次同步相关改动，没有私有配置或运行产物。
2. Java 后台能启动或通过可用测试，数据库 changelog 顺序正确。
3. xiaozhi-server 能加载配置，WebSocket 和 OTA 地址输出符合部署形态。
4. 后台保存的智能体配置能下发到 Python 私有配置。
5. 系统提示词、memory、voiceprint、context_providers 能进入 LLM 请求。
6. 服务端插件、外部 MCP Endpoint、服务器侧 MCP、设备端 MCP 工具能出现在当前支持函数列表。
7. 状态机游戏、天气查询、设备控制、打断处理保留本地定制行为。
8. 单模块和全模块部署文档仍与实际端口、配置文件、Compose 文件一致。
```

如果无法完整验证，必须在同步说明中列出未验证项和风险，不能把“无冲突”当成“可上线”。

## 7. 文档更新规则

官方同步改变以下内容时，必须同步更新文档：

| 变化 | 需要更新 |
| --- | --- |
| 连接初始化、配置拉取、OTA、WebSocket、MQTT | `docs/architecture/runtime-flows.md`、`device-interaction-chain.md` 和本文相关检查项 |
| PromptManager、Dialogue、Memory、voiceprint、context_providers | `docs/architecture/prompt-injection-engineering.md` |
| 工具执行器、插件注册、MCP executor、Intent provider | `docs/architecture/prompt-injection-engineering.md` 和 `docs/local-customizations.md` |
| 新增或调整本地长期定制 | `docs/local-customizations.md` |
| 部署、端口、Docker、配置文件路径 | 部署文档、开发规范和项目架构导航 |

文档更新应说明链路和边界，不要把官方文档或源码注释整段复制进来。

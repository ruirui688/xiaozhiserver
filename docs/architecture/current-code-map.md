# 当前代码地图与梳理报告

本文档记录当前仓库代码、文档、脚本和运行边界的梳理结果，用于后续开发前快速判断“该读哪些文件、该改哪里、该如何验证”。本文基于当前工作区状态整理，包含已经存在但尚未提交的改动。

## 1. 本轮梳理范围

本轮按多智能体并行梳理了以下范围：

```text
main/xiaozhi-server/     Python 实时服务、配置、插件、测试、脚本
main/manager-api/        Java 管理 API、数据库迁移、配置、Mapper
main/manager-web/        Web 管理后台页面、组件、API 封装、路由
main/manager-mobile/     移动端管理后台配置、页面结构、构建脚本
docs/                    项目文档、规范、部署说明、专题集成文档
scripts/                 本地开发、私有镜像构建、服务器安装脚本
deploy/                  部署资产归档边界
config/examples/         脱敏配置示例
Dockerfile*              Docker 构建入口
```

排除 `.git`、`runtime/`、`logs/`、`tmp/`、`node_modules/`、`target/` 等运行或依赖产物后，仓库包含 Python 服务、Java 管理 API、Web/移动管理端、脚本、文档、音乐和前端静态资源。文本源码与文档按模块读取和搜索梳理；图片、音频、模型和压缩前端库等二进制资源按路径和用途盘点，不逐字读取。

## 2. 总体架构

项目是官方 `xiaozhi-esp32-server` 的本地定制版本，主要运行链路如下：

```text
ESP32 设备 / WebSocket 客户端
  -> xiaozhi-server WebSocket 8000
  -> VAD / ASR / 替换词 / 语义控制中枢
  -> Intent / 工具系统 / LLM / Memory
  -> TTS
  -> WebSocket 音频帧返回设备

manager-web / manager-mobile
  -> manager-api HTTP 8002 /xiaozhi
  -> MySQL / Redis / sys_params / 智能体配置
  -> xiaozhi-server 通过 /config/** 拉取配置
```

本地开发统一入口是：

```bash
scripts/dev doctor
scripts/dev start all
scripts/dev status
scripts/dev logs server -f
scripts/dev stop
```

`main/xiaozhi-server` 必须使用 Conda 环境 `xiaozhi-esp32-server`，禁止用系统 `python`、`python3`、`pip`、`pytest` 验证该模块。

## 3. xiaozhi-server

`main/xiaozhi-server` 是 Python 实时服务，核心职责是接入设备 WebSocket、OTA/视觉 HTTP、ASR/VAD/LLM/TTS、工具调用、MCP、插件和会话状态。

关键目录：

```text
app.py                         服务入口，启动 WebSocket、HTTP、GC 管理
config/                        配置加载、日志、manager-api 客户端
core/websocket_server.py       WebSocket 入口、认证、连接对象创建、配置刷新
core/http_server.py            OTA、视觉、设备 MCP HTTP 路由
core/connection.py             单设备连接主状态机
core/handle/                   设备协议消息处理
core/providers/                ASR/VAD/LLM/TTS/Memory/Intent/VLLM/工具 provider
core/semantic/                 语义控制中枢
core/utils/                    Prompt、对话、音频、缓存、纠错词等工具
plugins_func/                  天气、新闻、音乐、知识库、HomeAssistant 等插件
tests/                         语义、游戏、VLLM 等单测
scripts/                       辅助脚本与无代码 MCP 工具桥
```

启动链路：

```text
scripts/dev start server
  -> Conda Python
  -> app.py
  -> load_config()
  -> WebSocket 8000 + HTTP 8003
  -> ConnectionHandler
  -> 私有配置 / 模型 / Prompt / 工具初始化
```

语音处理顺序：

```text
音频帧 -> VAD -> ASR -> 替换词 -> 语义控制中枢 -> 音乐直达/Intent/工具 -> LLM -> TTS
```

当前工作区新增或变更的 Python 能力：

- 替换词在 ASR 文本进入语义和意图前生效，新增 `core/utils/correct_words.py`。
- 音乐意图支持“有什么歌”“播放某首歌”“换一首”等直达路由。
- 音乐库支持 `.xiaozhi-music-metadata.json` 描述元数据，匹配时可参考提示词。
- VLLM OpenAI 兼容视觉模型支持自然口语化默认 prompt，并透传 `max_tokens`、`temperature`、`top_p`。
- 自由游戏规则补充“说我猜”“我已经想了一个物品”等场景，避免角色反转。
- `scripts/no_code_mcp_tools.py` 可把后台可视化 HTTP 工具配置注册成 MCP tools。

主要风险和缺口：

- 音乐上下文 `last_tool_context == "music"` 需要后续明确过期策略。
- 无代码 MCP HTTP 工具可访问任意 URL，需要权限、域名/IP 限制和密钥边界。
- 替换词输入格式要和 TTS provider 支持格式保持一致。
- 缺少音乐直达意图、纠错词、无代码 MCP 桥、HTTP/WS 集成测试。

## 4. manager-api

`main/manager-api` 是 Spring Boot 管理 API，入口是 `AdminApplication.java`，默认端口 `8002`，上下文 `/xiaozhi`。技术栈包括 Spring Boot 3.4.3、JDK 21、MyBatis-Plus、Liquibase、Redis、Shiro、Knife4j。

主要目录：

```text
common/              Result、异常、Redis、XSS、MyBatis、分页、工具类
modules/security/    登录、token、Shiro、server.secret 过滤
modules/sys/         用户、参数、字典、服务端控制
modules/agent/       智能体、模板、标签、聊天记录、MCP、上下文源、声纹
modules/config/      xiaozhi-server 配置下发接口
modules/device/      设备绑定、注册、OTA、地址簿、调试
modules/model/       模型供应器、模型配置、模型测试
modules/knowledge/   本地知识库、RAGFlow、文档切片和召回
modules/music/       服务器音乐库管理
resources/mapper/    MyBatis XML
resources/db/changelog/ Liquibase 数据库变更
```

配置下发链路：

```text
manager-web / manager-mobile
  -> manager-api
  -> MySQL / Redis / sys_params
  -> /config/** 使用 server.secret 保护
  -> xiaozhi-server 拉取配置
  -> Python 会话初始化 Prompt / tools / MCP / plugins
```

当前工作区新增或变更的 Java 能力：

- 新增智能体可视化 MCP HTTP 工具配置：`AgentMcpNoCodeTool*` DAO、DTO、Entity、Service、Controller。
- 管理端接口：`GET/PUT /agent/mcp/no-code-tools/{agentId}`，有智能体归属校验。
- 服务端接口：`GET /config/mcp/no-code-tools/{agentId}`，供 Python 桥接脚本使用。
- 数据表：`ai_agent_mcp_no_code_tools`，迁移文件 `202606161720.sql`。
- VLLM 视觉字段补全迁移：`202606161805.sql`。
- 本地知识库支持 `HY-KB-*` 预切片 Markdown。
- 音乐库支持描述元数据编辑、搜索、重命名和删除同步。

主要风险：

- no-code HTTP tool 后端仍需加强字段校验和 SSRF 防护。
- `/config/mcp/no-code-tools/{agentId}` 依赖 `server.secret` 安全边界，不面向普通用户。
- 首次并发保存 no-code tool 可能撞唯一键，后续可考虑 upsert。
- 新迁移使用 MySQL 8 的 `utf8mb4_0900_ai_ci`。

## 5. manager-web

`main/manager-web` 是 Vue 2 + Vue CLI + Element UI 管理后台。

主要结构：

```text
src/views/          页面
src/components/     复用弹窗和业务组件
src/apis/module/    API 封装
src/router/         路由
src/store/          Vuex 状态
src/utils/          工具函数
```

本地启动：

```bash
cd main/manager-web
npm install
npm run serve
```

也可使用：

```bash
scripts/dev start web
```

当前工作区新增或变更的 Web 能力：

- `FunctionDialog.vue` 新增“本地 HTTP 工具”配置面板，可编辑 method、URL、headers、query、body 模板、timeout 和参数 schema。
- `src/apis/module/agent.js` 新增 no-code MCP tools 读取和保存接口。
- `MusicManagement.vue` 新增音乐提示词列和编辑能力。
- `src/apis/module/music.js` 新增音乐描述更新接口。

## 6. manager-mobile

`main/manager-mobile` 是 uni-app v3 + Vue 3 + Vite 移动端管理后台，支持 H5、App、微信小程序等。

常用命令：

```bash
cd main/manager-mobile
pnpm install --frozen-lockfile
pnpm dev:h5
pnpm type-check
pnpm build:h5
```

也可使用：

```bash
scripts/dev start mobile
```

主要结构：

```text
src/pages/              页面
src/api/                API 封装
src/http/request/       alova 请求层
src/store/              Pinia 状态
src/layouts/fg-tabbar/  底部导航
env/                    Vite/uni-app 环境变量
```

当前没有发现该模块未提交改动。

## 7. 文档、脚本与部署边界

当前入口已经形成：

```text
AGENTS.md                         AI 开工规则
README.md                         项目总入口
docs/README.md                    文档分类索引
docs/standards/*.md               强制规范
docs/architecture/current-code-map.md 当前代码地图
scripts/dev                       本地开发统一入口
```

脚本边界：

- `scripts/dev` 是本地开发唯一推荐入口。
- `scripts/start-dev.sh` 仅作兼容转发。
- `scripts/docker-build-private.sh`、`scripts/install-private-docker.sh` 当前仍是私有镜像构建和安装兼容入口，长期可迁入 `deploy/` 并保留转发脚本。
- `docker-setup.sh` 是官方兼容脚本，当前 shebang 与 Bash 语法不完全匹配，后续应单独处理。

部署文档优先级：

```text
私有生产发布：docs/deployment/private-docker-release.md
部署资产边界：docs/deployment/deployment-asset-policy.md
官方兼容部署：docs/deployment/Deployment.md / Deployment_all.md
历史源码升级：docs/deployment/dev-ops-integration.md
```

## 8. 当前未提交能力清单

当前工作区未提交改动跨越文档、脚本、Java、Web、Python。主要能力包括：

- AI agent 入口规则和 Conda 运行时硬约束。
- 文档治理规范与文档索引修复。
- no-code MCP HTTP 工具配置、下发和 Python 桥接脚本。
- 音乐库提示词元数据、播放意图直达和播放前介绍语控制。
- 替换词在 ASR/文本进入语义前生效。
- 本地知识库 `HY-KB-*` 预切片 Markdown 支持。
- VLLM 视觉回复自然表达和生成参数透传。
- 自由游戏提示词规则补充。

提交前必须复查：

```text
main/xiaozhi-server/music/.xiaozhi-music-metadata.json 是否作为运行数据处理
main/manager-api/src/main/resources/db/changelog/202606161720.sql
main/manager-api/src/main/resources/db/changelog/202606161805.sql
AGENTS.md 是否纳入版本控制
```

## 10. 建议验证矩阵

按修改范围选择验证：

```bash
bash -n scripts/dev scripts/start-dev.sh
scripts/dev doctor
```

Python：

```bash
cd main/xiaozhi-server
conda run -n xiaozhi-esp32-server python -m compileall .
conda run -n xiaozhi-esp32-server pytest
```

Java：

```bash
cd main/manager-api
mvn -DskipTests package
```

如需真实执行测试，需要注意 `pom.xml` 当前有 `skipTests=true`，可显式覆盖。

Web：

```bash
cd main/manager-web
npm run build
```

Mobile：

```bash
cd main/manager-mobile
pnpm type-check
pnpm build:h5
```

部署前还必须验证 Liquibase 迁移能在 MySQL 8 空库顺序执行。

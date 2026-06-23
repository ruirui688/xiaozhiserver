# 文档治理策略

本文档规定项目文档的分类、命名、索引和移动规则。它用于后续整理文档时保持入口清晰、链接稳定，避免把部署资产、运行产物和说明文档混在一起。

## 1. 文档入口

文档入口职责如下：

```text
README.md                         项目总入口，只保留项目概览、常用入口和关键规范链接
AGENTS.md                         AI 编程工具的最短开工规则
docs/README.md                    文档分类索引，说明每类文档解决什么问题
docs/standards/README.md          规范目录入口，只解释 standards 下各规范职责
docs/standards/*.md               强制规范，是具体规则来源
main/README.md                    main 模块目录入口，只说明模块职责和文档去向
main/<module>/README.md           模块开发入口，只说明该模块构建、启动、验证和本地约定
```

避免在多个入口重复大段规则。规则变更应优先修改对应 `docs/standards/*.md`，入口文档只链接到规则来源。

## 2. 分类规则

新文档按主题放入以下目录：

```text
docs/architecture/     架构、运行链路、模块边界
docs/development/      本地开发、调试、启动、工具链
docs/deployment/       部署、运维、发布、服务器流程
docs/device/           ESP32 固件、OTA、设备协议、MQTT
docs/integrations/     第三方服务、插件、外部 API 集成
docs/mcp/              MCP 接入点、MCP 工具、设备上下文
docs/models/           ASR、TTS、LLM、声纹、记忆、RAG、性能测试
docs/reference/        FAQ、贡献说明、通用参考
docs/standards/        强制规范、治理策略
docs/images/           文档图片资源
```

禁止在 `main/` 下新增通用 `doc/` 或 `docs/` 目录。`main/` 只保留业务模块和模块级 README；通用专题、排障记录、架构说明、部署教程、设备/MCP/模型/集成说明必须归入根目录 `docs/` 的对应分类。

依赖目录、构建产物和缓存目录中的 README、报告或 txt 文件不纳入项目文档索引，例如：

```text
node_modules/
target/
.pytest_cache/
dist/
```

部署脚本、Nginx 配置、Compose 文件、安装脚本等可执行或可直接投产的资产，优先放在 `deploy/` 或 `scripts/`，不要新增到 `docs/`。

## 3. 命名规则

新增 Markdown 文档使用小写 kebab-case：

```text
local-dev-workflow.md
documentation-policy.md
```

历史文档如果已经被外部或内部链接引用，可以暂缓重命名。重命名前必须统一检查并修复链接，必要时保留兼容说明。

## 4. 索引维护

新增、删除或移动文档时必须同步检查：

```text
docs/README.md
docs/standards/README.md
README.md
相关专题文档中的相对链接
```

新增强制规范时，还必须同步检查：

```text
AGENTS.md
docs/standards/ai-coding-agent-instructions.md
docs/local-customizations.md
```

## 5. 移动规则

移动文档前先搜索引用：

```bash
rg "old-file-name|old/path" README.md docs main deploy scripts
```

移动后必须检查：

```text
Markdown 相对链接
图片相对路径
README 或 docs/README.md 索引
AI 必读规则是否仍指向正确位置
```

不要把大规模文档搬迁和业务代码改动混在同一次任务中。涉及官方旧文档路径的移动，应优先在独立文档整理任务中完成。

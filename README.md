# 小智 ESP32 Server

这是一个用于 `xiaozhi-esp32` 智能硬件设备的本地后端服务项目，基于官方 `xiaozhi-esp32-server` 进行本地部署和二次开发。

项目主要用于为 ESP32 设备提供语音交互、智能体管理、OTA 配置、模型调用、管理后台和相关扩展能力。

## 主要功能

- ESP32 设备 WebSocket 接入
- OTA 配置接口
- 语音识别、语音合成、大模型对话能力接入
- 智能体、设备、模型参数等后台管理
- 长期记忆、记忆维护任务与声纹隔离
- 本地知识库 / RAGFlow、文档解析、切片和召回
- 管理后台 Web 页面与移动端/微信小程序管理
- MCP、插件、工具调用等扩展能力
- Docker / Docker Compose 部署
- 本地开发与阿里云服务器部署流程支持

## 当前维护状态

2026-07 可靠性维护已覆盖记忆重建任务恢复、知识库切片状态与资源授权、智能体表单草稿恢复、微信端知识库/记忆管理，以及动态提示词上下文预算。这里的“已覆盖”表示源码与自动测试已经提交，不表示已经部署到生产服务器。

服务器验收仍需使用真实 Redis、MySQL、RAGFlow、外部模型和微信开发者工具/真机完成。故障根因、与官方源码的能力差异、自动测试记录和未验证风险统一见 [2026-07 功能可靠性与上游差异审计](./docs/reports/reliability-and-upstream-audit-2026-07.md)。

## 项目结构

```text
main/                 业务模块（[入口](./main/README.md)）
  xiaozhi-server/    Python 核心服务
  manager-api/       Java Spring Boot 管理后台 API
  manager-web/       Web 管理后台
  manager-mobile/    移动端管理后台
docs/                 项目文档（[入口](./docs/README.md)）
scripts/              本地开发与发布脚本（[入口](./scripts/README.md)）
runtime/              本地运行产物目录，仅提交 .gitkeep
config/examples/      脱敏配置示例
deploy/               部署资产归档边界（[入口](./deploy/README.md)）
```

常用服务端口：

```text
8000  WebSocket 服务
8003  HTTP / OTA / 视觉接口
8002  管理后台接口与页面
```

## 文档入口

第一次接触项目，按以下顺序阅读：

1. [项目架构总览](./docs/architecture/project-architecture.md) - 理解模块边界、控制面、实时数据面和关键链路。
2. [文档任务门户](./docs/README.md) - 按开发、部署、排障或专项任务进入对应文档。
3. [本地开发工作流](./docs/development/local-dev-workflow.md) - 使用统一脚本启动和验证服务。
4. [机器人门口迎宾产品化](./docs/architecture/robot-reception-productization.md) - 当前未全部完成；查看客户档案、预约、跨端协议、时延、导航和分阶段实施。

生产环境只使用 [ACR 镜像生产部署](./docs/deployment/private-docker-release.md)。历史官方部署教程和服务器源码自动升级流程不属于本项目生产入口。

## 开发规范

本项目的本地开发、Git 管理、数据库变更、配置管理、阿里云部署和官方上游同步，必须遵守：

- [小智服务器开发规范](./docs/standards/development-specification.md)
- [文档任务门户](./docs/README.md)
- [项目架构总览](./docs/architecture/project-architecture.md)

AI 编程工具只以根目录 [AGENTS.md](./AGENTS.md) 为规则入口，不再维护第二份重复指令。

本地开发统一入口：

```bash
scripts/dev doctor
scripts/dev start all
scripts/dev status
scripts/dev logs server -f
scripts/dev stop
```

详细说明见 [本地开发工作流](./docs/development/local-dev-workflow.md)。

AI 编程工具开工入口：

```text
AGENTS.md
```

`main/xiaozhi-server` 必须使用 Conda 环境 `xiaozhi-esp32-server` 启动和验证。禁止直接使用系统 `python`、`python3`、`pip` 或 `pytest` 排查该模块；`scripts/dev` 已经内置该限制。

模块开发说明：

- [manager-api 开发说明](./main/manager-api/README.md)
- [manager-web 开发说明](./main/manager-web/README.md)
- [manager-mobile 开发说明](./main/manager-mobile/README.md)

## 配置说明

项目中的默认配置文件只作为模板或默认值使用。真实环境配置不要提交到 Git。

重点注意：

- `main/xiaozhi-server/config.yaml` 是默认配置。
- `main/xiaozhi-server/data/.config.yaml` 是本地或服务器私有配置，不应提交。
- `config/examples/` 保存脱敏示例配置，可以提交。
- `runtime/config/` 保存本地或环境私有配置，不应提交。
- 数据库密码、Redis 密码、模型 API Key、短信密钥等敏感信息不应提交。
- `runtime/` 下的日志、PID、缓存、上传文件、临时文件属于运行时数据，不应提交。

## 开发与发布规则

标准开发发布流程：

```text
本地开发
  -> Git 提交
  -> 推送到私有仓库
  -> 本地/构建机打包并推送 ACR 镜像
  -> 生产服务器拉取已发布镜像并启动
```

私有仓库的标准远端名为 `origin`，官方上游仓库的标准远端名为 `upstream`。远端 URL 可能随环境或仓库迁移而变化，操作前必须以以下命令的实际输出为准：

```bash
git remote -v
```

`origin` 必须指向允许推送的私有仓库；`upstream` 用于获取官方更新，禁止向其推送。本地二次开发代码只能推送到核对后的 `origin`。

如果需要跟进官方仓库更新，必须先在 `sync/upstream-YYYYMMDD` 分支中合并 `upstream/main`，完成冲突处理和测试后，再合并到本地 `main` 分支。

## License

本项目基于原官方开源项目进行本地二次开发，遵循原项目许可证要求。

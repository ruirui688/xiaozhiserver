# 小智 ESP32 Server

这是一个用于 `xiaozhi-esp32` 智能硬件设备的本地后端服务项目，基于官方 `xiaozhi-esp32-server` 进行本地部署和二次开发。

项目主要用于为 ESP32 设备提供语音交互、智能体管理、OTA 配置、模型调用、管理后台和相关扩展能力。

## 主要功能

- ESP32 设备 WebSocket 接入
- OTA 配置接口
- 语音识别、语音合成、大模型对话能力接入
- 智能体、设备、模型参数等后台管理
- 管理后台 Web 页面
- 移动端管理后台
- MCP、插件、工具调用等扩展能力
- Docker / Docker Compose 部署
- 本地开发与阿里云服务器部署流程支持

## 项目结构

```text
main/
  xiaozhi-server/    Python 核心服务
  manager-api/       Java Spring Boot 管理后台 API
  manager-web/       Web 管理后台
  manager-mobile/    移动端管理后台
docs/                项目文档
```

常用服务端口：

```text
8000  WebSocket 服务
8003  HTTP / OTA / 视觉接口
8002  管理后台接口与页面
```

## 开发规范

本项目的本地开发、Git 管理、数据库变更、配置管理、阿里云部署和官方上游同步，必须遵守：

- [小智服务器开发规范](./docs/standards/development-specification.md)
- [AI 编程工具工作指令](./docs/standards/ai-coding-agent-instructions.md)
- [文档分类索引](./docs/README.md)

补充部署文档：

- [官方 Docker 部署说明](./docs/deployment/Deployment.md)
- [官方全模块部署说明](./docs/deployment/Deployment_all.md)

模块开发说明：

- [manager-api 开发说明](./main/manager-api/README.md)
- [manager-web 开发说明](./main/manager-web/README.md)
- [manager-mobile 开发说明](./main/manager-mobile/README.md)

## 配置说明

项目中的默认配置文件只作为模板或默认值使用。真实环境配置不要提交到 Git。

重点注意：

- `main/xiaozhi-server/config.yaml` 是默认配置。
- `main/xiaozhi-server/data/.config.yaml` 是本地或服务器私有配置，不应提交。
- 数据库密码、Redis 密码、模型 API Key、短信密钥等敏感信息不应提交。
- MySQL 数据、Redis 数据、上传文件、模型文件属于运行时数据，不应提交。

## 开发与部署规则

标准流程：

```text
本地开发
  -> Git 提交
  -> 推送到私有仓库
  -> 测试环境验证
  -> 创建 release tag
  -> 阿里云服务器部署 tag
```

私有仓库的标准远端名为 `origin`，官方上游仓库的标准远端名为 `upstream`。本项目当前约定：

```text
origin    ssh://git@10.144.92.32:222/gaoruirui688/xiaozhi-esp32-server.git
upstream  https://github.com/xinnan-tech/xiaozhi-esp32-server.git
```

本地二次开发代码必须推送到 `origin`，禁止推送到官方 `upstream`。

如果需要跟进官方仓库更新，必须先在 `sync/upstream-YYYYMMDD` 分支中合并 `upstream/main`，完成冲突处理和测试后，再合并到本地 `main` 分支。

## License

本项目基于原官方开源项目进行本地二次开发，遵循原项目许可证要求。

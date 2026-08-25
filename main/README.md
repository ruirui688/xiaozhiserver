# main 模块目录

`main/` 只存放项目主要业务模块。通用架构、部署、设备、MCP、模型、集成和排障文档统一放入根目录 `docs/`，不要在 `main/` 下新增通用 `docs/` 或 `doc/` 目录。

模块职责：

```text
xiaozhi-server/    Python 核心实时服务，负责设备 WebSocket、OTA、模型调用、插件和 MCP 运行时
manager-api/       Java Spring Boot 管理 API，负责后台接口、配置、权限、数据库和异步编排
manager-web/       Vue 管理后台 Web 页面
manager-mobile/    uni-app 移动端管理后台
maintenance-agent/ 可选 DeepSeek Harness 协议适配器，只生成有界维护提案
digital-human/     数字人相关模块
```

项目总入口见 [../README.md](../README.md)。

文档入口见 [../docs/README.md](../docs/README.md)，模块边界和代码入口见 [../docs/architecture/project-architecture.md](../docs/architecture/project-architecture.md)。

模块开发入口：

- [manager-api/README.md](manager-api/README.md)
- [manager-web/README.md](manager-web/README.md)
- [manager-mobile/README.md](manager-mobile/README.md)
- [maintenance-agent/README.md](maintenance-agent/README.md)

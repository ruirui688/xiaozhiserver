# main 模块目录

`main/` 只存放项目主要业务模块。通用架构、部署、设备、MCP、模型、集成和排障文档统一放入根目录 `docs/`，不要在 `main/` 下新增通用 `docs/` 或 `doc/` 目录。

模块职责：

```text
xiaozhi-server/    Python 核心实时服务，负责设备 WebSocket、OTA、模型调用、插件和 MCP 运行时
manager-api/       Java Spring Boot 管理 API，负责后台接口、配置、权限、数据库和异步编排
manager-web/       Vue 管理后台 Web 页面
manager-mobile/    uni-app 移动端管理后台
digital-human/     数字人相关模块
```

项目总入口见 [../README.md](../README.md)。

文档入口见 [../docs/README.md](../docs/README.md)，代码结构说明见 [../docs/architecture/current-code-map.md](../docs/architecture/current-code-map.md)。

模块开发入口：

- [manager-api/README.md](manager-api/README.md)
- [manager-web/README.md](manager-web/README.md)
- [manager-mobile/README.md](manager-mobile/README.md)

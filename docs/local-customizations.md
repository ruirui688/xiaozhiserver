# 本地定制记录

本文档记录本项目相对官方 `xiaozhi-esp32-server` 的长期本地定制。同步官方 `upstream` 前必须先阅读本文档，避免误覆盖本地业务能力。

## 1. 功能插件架构与声明式互动模式

```text
定制模块：xiaozhi-server 服务端插件执行器
改动原因：把天气、新闻、音乐播放、HomeAssistant 和讲段子、热梗、成语接龙、你说我猜等能力统一为 Function Plugin 顶层模型；声明式互动玩法不需要为每个玩法新增 Python 插件文件。
关键文件：
  - main/xiaozhi-server/core/providers/tools/server_plugins/plugin_executor.py
  - docs/integrations/function-plugin-architecture.md
是否允许被官方覆盖：否
官方同步时的处理规则：如果官方也修改插件注册、工具描述生成或 ServerPluginExecutor 执行流程，必须人工合并，保留 Function Plugin 顶层模型、声明式互动模式、custom_plugin 兼容字段、parameters_schema、opening_message、instructions 等配置约定。
```

## 2. Ubuntu 源码开发环境文档

```text
定制模块：本地开发环境说明
改动原因：记录本地 Ubuntu 开发机源码编译、依赖安装、服务启动和验证流程，区别于服务器部署文档。
关键文件：
  - docs/development/ubuntu-source-development-environment.md
是否允许被官方覆盖：否
官方同步时的处理规则：官方部署文档更新时可以补充本文档，但不要用部署流程替换本地源码开发流程。
```

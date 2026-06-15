# 配置与运行产物治理策略

本文规定项目配置、私有环境参数和运行产物的边界。当前策略只建立新边界，不移动现有配置文件。

## 1. 四层配置模型

### 默认模板配置

默认模板配置定义项目默认值；只要不包含真实密钥、生产地址或用户数据，就可以提交。现有文件包括：

```text
main/xiaozhi-server/config.yaml
main/xiaozhi-server/config_from_api.yaml
main/manager-api/src/main/resources/application.yml
main/manager-api/src/main/resources/application-dev.yml
main/manager-web/.env.development
```

### 可提交示例配置

`config/examples/` 下的文件是脱敏示例，用于本地初始化和自动化脚本。示例可以展示变量名、配置结构和本地回环地址，但必须使用占位符或无敏感默认值。

禁止在示例配置中写入：

```text
真实 API Key
数据库密码
Redis 密码
生产 URL
设备密钥
客户数据
内网敏感地址
```

### 环境私有配置

开发、CI、测试和生产环境的真实值必须放在被 Git 忽略的私有配置文件或部署平台密钥中。推荐本地路径：

```text
runtime/config/xiaozhi-server.local.yaml
runtime/config/manager-api.local.env
runtime/config/manager-web.local.env
```

兼容说明：当前 `xiaozhi-server` 仍支持 `main/xiaozhi-server/data/.config.yaml` 作为本地覆盖配置。它属于环境私有配置，必须保持忽略，禁止提交。

### 运行时生成配置

程序运行中生成的配置快照、合并配置或临时配置应写入 `runtime/`，尤其是 `runtime/config/`。这些文件默认被 Git 忽略；只提交 `.gitkeep` 保留目录契约。

## 2. 目录职责

`config/examples/` 用于提交脱敏示例：

```text
config/examples/xiaozhi-server.local.yaml.example
config/examples/manager-api.local.env.example
config/examples/manager-web.local.env.example
```

`runtime/config/` 用于本地运行、CI、部署或包装脚本读取的私有配置或生成配置。它可以保存真实地址和凭据，但前提是这些文件只存在于受保护的本机或部署环境中。

`runtime/` 子目录职责：

| 目录 | 职责 |
| --- | --- |
| `runtime/logs/` | 日志文件 |
| `runtime/run/` | PID、socket、进程状态 |
| `runtime/tmp/` | 临时文件 |
| `runtime/uploads/` | 上传文件 |
| `runtime/cache/` | 缓存和生成数据 |
| `runtime/config/` | 私有或生成配置 |

## 3. 提交规则

禁止提交：

```text
API Key、Access Token、签名密钥、私钥证书、设备密钥
数据库密码、Redis 密码、云服务凭据
生产、测试、客户或私有网络真实地址
main/xiaozhi-server/data/.config.yaml
任意 data/.config.yaml
日志、PID、上传文件、缓存、生成配置、临时文件
```

新增配置项时必须按顺序处理：

```text
1. 如果适合默认值，写入现有默认模板配置。
2. 如果运维或开发者需要手工设置，补充 config/examples/ 脱敏示例。
3. 真实值从被忽略的私有配置或部署密钥读取。
4. 生成文件默认写入 runtime/；只有兼容旧实现时才继续写旧路径。
```

## 4. 迁移原则

当前阶段不强制移动现有配置，只建立新边界。

后续迁移必须遵守：

```text
先保留旧路径兼容入口。
再让新脚本优先读取 runtime/config/。
最后更新文档和检查脚本。
不得在同一次提交中混合配置迁移、业务逻辑重构和部署改造。
```

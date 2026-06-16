# 手动开发启动

默认启动后台开发环境：

```bash
scripts/dev start api
scripts/dev start web
```

默认会启动：

- MySQL
- Redis
- `main/manager-api`
- `main/manager-web`

如果还要启动小智核心服务：

```bash
scripts/dev start server
```

访问地址：

```text
manager-web:    http://127.0.0.1:8001
manager-api:    http://127.0.0.1:8002/xiaozhi/doc.html
xiaozhi-server: ws://127.0.0.1:8000/xiaozhi/v1/
```

日志目录：

```text
runtime/logs/dev/
```

PID 文件目录：

```text
runtime/run/
```

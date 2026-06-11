# 手动开发启动

默认启动后台开发环境：

```bash
scripts/start-dev.sh
```

默认会启动：

- MySQL
- Redis
- `main/manager-api`
- `main/manager-web`

如果还要启动小智核心服务：

```bash
scripts/start-dev.sh --with-xiaozhi
```

访问地址：

```text
manager-web:    http://127.0.0.1:8001
manager-api:    http://127.0.0.1:8002/xiaozhi/doc.html
xiaozhi-server: ws://127.0.0.1:8000/xiaozhi/v1/
```

日志目录：

```text
logs/dev/
```

PID 文件目录：

```text
.run/
```

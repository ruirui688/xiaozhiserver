# 本地开发工作流

`scripts/dev` 是本项目的本地开发统一入口，用于启动、停止、查看状态、查看日志和做环境体检。旧的 `scripts/start-dev.sh` 只作为兼容包装器保留，会把旧参数转发到 `scripts/dev`，不再维护独立的 `.run/` 和 `logs/dev/` 运行环境。

## 常用命令

在项目根目录执行：

```bash
scripts/dev help
scripts/dev doctor
scripts/dev start api
scripts/dev start web
scripts/dev start server
scripts/dev start mobile
scripts/dev start all
scripts/dev status
scripts/dev logs api
scripts/dev logs all -f
scripts/dev stop
```

目标说明：

- `api`：启动 `main/manager-api`，执行 `mvn spring-boot:run -Dspring-boot.run.profiles=dev`。
- `web`：启动 `main/manager-web`，执行 `npm run serve`。
- `server`：启动 `main/xiaozhi-server`，优先使用 `~/miniconda3/envs/xiaozhi-esp32-server/bin/python app.py`，否则尝试 `conda run` 或系统 Python。
- `mobile`：启动 `main/manager-mobile` H5 开发服务，执行 `pnpm dev:h5`。
- `all`：按 `api -> web -> server -> mobile` 顺序提交启动命令。

启动 `api` 或 `all` 时，脚本会尝试用非交互方式启动 MySQL 和 Redis；如果当前用户没有免密 sudo，会跳过并提示手动启动。临时跳过基础服务检查可以使用：

```bash
DEV_SKIP_INFRA=1 scripts/dev start api
```

## 服务端口

| 服务 | 默认地址 |
| --- | --- |
| manager-web | `http://127.0.0.1:8001` |
| manager-api | `http://127.0.0.1:8002/xiaozhi/doc.html` |
| xiaozhi-server WebSocket | `ws://127.0.0.1:8000/xiaozhi/v1/` |
| xiaozhi-server OTA | `http://127.0.0.1:8003/xiaozhi/ota/` |
| manager-mobile H5 | `http://127.0.0.1:9000` |
| MySQL | `127.0.0.1:3306` |
| Redis | `127.0.0.1:6379` |

`doctor` 会检查这些端口。对于应用端口，未监听通常表示可以启动；已监听时需要结合 `scripts/dev status` 判断是否已经由本入口管理，避免重复启动。

## 运行状态和日志

新入口默认写入：

```text
runtime/logs/dev/
runtime/run/
```

常用查看方式：

```bash
scripts/dev status
scripts/dev logs web
scripts/dev logs server -f
```

旧入口 `scripts/start-dev.sh` 已经改为转发到 `scripts/dev`。如果本机还存在历史 `.run/` 或 `logs/dev/`，说明它们是旧版本脚本留下的运行产物，应在确认旧服务已停止后清理。

## doctor 检查内容

```bash
scripts/dev doctor
```

`doctor` 只检查，不启动服务，也不安装依赖。检查项包括：

- 基础命令：`java`、`mvn`、`node`、`npm`、`pnpm`、Python 或 Conda。
- 可选命令：`mysql`、`redis-cli`、`ffmpeg`、端口检查工具。
- 关键目录：`main/xiaozhi-server`、`main/manager-api`、`main/manager-web`、`main/manager-mobile`。
- 私有配置：`main/xiaozhi-server/data/.config.yaml`、`main/manager-api/src/main/resources/application-dev.yml`。
- 端口占用：3306、6379、8000、8001、8002、8003、9000。

## 常见排查

端口已占用：

```bash
scripts/dev status
ss -lntp | grep -E ':(8000|8001|8002|8003|9000)\b' || true
```

如果端口由 `scripts/dev` 管理，使用 `scripts/dev stop <目标>` 停止；如果是 IDE、旧脚本或手动命令启动的进程，需要在对应工具中停止。

前端依赖缺失：

```bash
cd main/manager-web && npm install
cd main/manager-mobile && pnpm install
```

`scripts/dev start web` 和 `scripts/dev start mobile` 会在 `node_modules` 不存在时自动安装依赖；`doctor` 不会安装。

xiaozhi-server 私有配置缺失：

```bash
cd main/xiaozhi-server
mkdir -p data
cp config_from_api.yaml data/.config.yaml
```

然后确认 `data/.config.yaml` 中的 `manager-api.url` 指向 `http://127.0.0.1:8002/xiaozhi`，`manager-api.secret` 使用智控台参数管理中的本地 `server.secret`。

MySQL 或 Redis 未监听：

```bash
sudo systemctl start mysql
sudo systemctl start redis-server
```

如果是在不支持 `systemctl` 的环境中：

```bash
sudo service mysql start
sudo service redis-server start
```

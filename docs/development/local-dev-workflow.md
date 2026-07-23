# 本地开发环境与工作流

`scripts/dev` 是本项目的本地开发统一入口，用于启动、停止、查看状态、查看日志和做环境体检。旧的 `scripts/start-dev.sh` 只作为兼容包装器保留，会把旧参数转发到 `scripts/dev`，不再维护独立的 `.run/` 和 `logs/dev/` 运行环境。

本文只面向 Ubuntu 22.04/24.04 的本地源码开发。服务器部署使用 `docs/deployment/`，不要把本文的开发账号、明文 HTTP 地址或本地服务配置复制到生产环境。

## 首次准备环境

本项目需要 JDK 21、Maven、Node.js 22 LTS、npm、pnpm 10.10.x、Conda、MySQL 8、Redis、FFmpeg 和 Opus。先安装系统依赖：

```bash
sudo apt update
sudo apt install -y ca-certificates curl wget gnupg lsb-release \
  software-properties-common build-essential pkg-config git git-lfs \
  unzip ffmpeg libopus0 libopus-dev libsndfile1 portaudio19-dev \
  libasound2-plugins openjdk-21-jdk maven mysql-server redis-server

git lfs install
sudo systemctl enable --now mysql redis-server
```

WSL2 不支持 `systemctl` 时，改用 `sudo service mysql start` 和 `sudo service redis-server start`。安装 Node.js 与 pnpm：

```bash
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt install -y nodejs
sudo corepack enable
corepack prepare pnpm@10.10.0 --activate
```

安装 Miniconda 后创建项目规定的 Python 3.10 环境。即使已经激活环境，项目命令仍统一写成 `conda run`，避免误用系统 Python：

```bash
conda create -n xiaozhi-esp32-server python=3.10 -y
conda install -n xiaozhi-esp32-server -y -c conda-forge ffmpeg libopus libiconv
cd main/xiaozhi-server
conda run -n xiaozhi-esp32-server python -m pip install -r requirements.txt
```

CPU 开发机可先按 PyTorch 官方 CPU wheel 说明安装项目锁定的版本，再安装其余锁定依赖；GPU 环境必须根据驱动和 CUDA 版本选择官方 wheel，不要混装。

安装前端依赖：

```bash
cd main/manager-web && npm install
cd ../manager-mobile && pnpm install
```

创建仅供本机使用的 MySQL 数据库和低权限开发账号；账号、密码必须自行生成，不要使用本文档固定值，也不要提交到 Git。`manager-api` 启动时会通过 Liquibase 应用迁移。私有 Python 配置放在 `main/xiaozhi-server/data/.config.yaml`：

```bash
cd main/xiaozhi-server
mkdir -p data
cp config_from_api.yaml data/.config.yaml
```

确认其中 `manager-api.url` 指向本地 API，`manager-api.secret` 与智控台参数管理中的本地 `server.secret` 一致。默认 ASR 模型放在 `main/xiaozhi-server/models/SenseVoiceSmall/model.pt`；模型文件、私有配置、密钥和运行日志都禁止提交。

首次完成后运行 `scripts/dev doctor`，再进入日常启动流程。

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

推荐启动顺序是 MySQL、Redis、manager-api、manager-web、xiaozhi-server，最后按需启动 manager-mobile。通常直接执行 `scripts/dev start all`；只调试管理后台时执行 `scripts/dev start api` 和 `scripts/dev start web`。

目标说明：

- `api`：启动 `main/manager-api`，默认使用已编译 classes 和缓存依赖 classpath 直接 `java` 快速启动；设置 `DEV_API_MAVEN_RUN=1` 时才执行 `mvn spring-boot:run -Dspring-boot.run.profiles=dev`。
- `web`：启动 `main/manager-web`，执行 `npm run serve`。
- `server`：启动 `main/xiaozhi-server`，优先使用 `~/miniconda3/envs/xiaozhi-esp32-server/bin/python app.py`，否则使用 `conda run -n xiaozhi-esp32-server python app.py`。脚本会拒绝退回系统 Python。
- `mobile`：启动 `main/manager-mobile` H5 开发服务，执行 `pnpm dev:h5`。
- `all`：按 `api -> web -> server -> mobile` 顺序提交启动命令。

启动 `api` 或 `all` 时，脚本会尝试用非交互方式启动 MySQL 和 Redis；如果当前用户没有免密 sudo，会跳过并提示手动启动。临时跳过基础服务检查可以使用：

```bash
DEV_SKIP_INFRA=1 scripts/dev start api
```

如果修改了 Java 代码但没有手动编译，可以使用：

```bash
DEV_API_FORCE_COMPILE=1 scripts/dev start api
```

如果需要调试 Maven/Spring Boot 插件启动行为，可以使用兼容慢路径：

```bash
DEV_API_MAVEN_RUN=1 scripts/dev start api
```

## Python 运行时约束

`main/xiaozhi-server` 的运行时依赖必须安装在 Conda 环境 `xiaozhi-esp32-server` 中。开发脚本和 AI 编程工具不得直接使用系统 `python`、`python3`、`pip` 或 `pytest` 来启动、验证或排查该模块。

正确命令：

```bash
conda run -n xiaozhi-esp32-server python -m compileall .
conda run -n xiaozhi-esp32-server pytest
scripts/dev start server
```

如果本机 Conda 安装路径不是 `~/miniconda3`，但已经有可用解释器，可以显式指定：

```bash
XIAOZHI_SERVER_PYTHON=/path/to/conda/envs/xiaozhi-esp32-server/bin/python scripts/dev start server
```

如果 Conda 环境不存在，`scripts/dev doctor` 和 `scripts/dev start server` 会失败并提示初始化环境。不要为了绕过失败而退回系统 Python；那类错误通常只是依赖环境不一致，不能代表项目真实运行状态。

### Conda 测试环境异常排查

如果 `conda run -n xiaozhi-esp32-server python -m pytest ...` 在导入阶段报 `ValueError: bad marshal data`，或 `pytest` 入口脚本指向旧路径，例如 `/root/miniconda3/...`，先按环境问题处理，不要改测试代码或退回系统 Python。

推荐检查顺序：

```bash
cd main/xiaozhi-server
conda run -n xiaozhi-esp32-server python -c "import sys; print(sys.executable); print(sys.version)"
sed -n '1,4p' ~/miniconda3/envs/xiaozhi-esp32-server/bin/pytest
conda run -n xiaozhi-esp32-server python -m pytest --version
conda run -n xiaozhi-esp32-server pytest --version
```

如果确认是 Conda 环境内 `.pyc` 损坏，可以删除损坏的字节码缓存，让解释器按需重建。不要删除源码文件或仓库测试文件。

```bash
conda run -n xiaozhi-esp32-server python -c "from pathlib import Path; import marshal; base=Path.home()/'miniconda3/envs/xiaozhi-esp32-server/lib/python3.10/site-packages'; bad=[]
for path in base.rglob('*.pyc'):
    try:
        f=path.open('rb'); f.read(16); marshal.load(f); f.close()
    except Exception as exc:
        bad.append(path)
for path in bad:
    print(path); path.unlink()
print(f'removed={len(bad)}')"
```

如果清理 `.pyc` 后仍在 `import ssl`、`import _hashlib`、`python -m pip show pytest` 或 `python -m pytest` 时段错误，优先检查 Conda 环境内 OpenSSL 是否损坏或版本不一致：

```bash
conda run -n xiaozhi-esp32-server env PYTHONFAULTHANDLER=1 python -X faulthandler -c "import ssl, _hashlib; print(ssl.OPENSSL_VERSION)"
conda list -n xiaozhi-esp32-server | rg '^(openssl|python|ca-certificates|certifi)\s'
sha256sum ~/miniconda3/envs/xiaozhi-esp32-server/lib/libssl.so.3 \
  ~/miniconda3/envs/xiaozhi-esp32-server/lib/libcrypto.so.3
```

网络可用时可以用 Conda 重新安装 `openssl`。如果远程源不可用但本机 `~/miniconda3/pkgs/` 里有匹配的 `openssl-*.conda` 缓存包，可以离线重装对应包修复运行库；修复后必须重新验证 `import ssl`、`pytest --version` 和目标测试。

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

## 变更验证

按修改模块执行最小验证：

```bash
cd main/xiaozhi-server
conda run -n xiaozhi-esp32-server python -m compileall .
conda run -n xiaozhi-esp32-server pytest

cd ../manager-api && mvn test
cd ../manager-web && npm run build
cd ../manager-mobile && pnpm build:h5
```

数据库结构变更必须新增 Liquibase 迁移并登记到 `db.changelog-master.yaml`。不要默认调用外部模型、启动长期服务或执行生产部署作为本地验证。

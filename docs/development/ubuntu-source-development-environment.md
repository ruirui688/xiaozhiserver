# Ubuntu 源码开发环境安装说明

本文档用于在 Ubuntu 本地开发机上准备本项目的源码编译和调试环境。它只面向 `local` 开发环境，不作为生产服务器部署手册使用。

如果要部署到服务器，请继续使用 `docs/deployment/` 下的部署文档；如果要修改代码、编译前后端、调试服务端，则按本文档准备环境。

## 1. 适用范围

推荐系统：

```text
Ubuntu 22.04 LTS / Ubuntu 24.04 LTS
x86_64 架构
```

本项目本地源码开发需要以下组件：

```text
Git                  拉取和管理源码
JDK 21               编译和运行 manager-api
Maven                构建 manager-api
Node.js 22 LTS       编译和运行 manager-web、manager-mobile
npm                  manager-web 依赖管理
pnpm 10.10.x         manager-mobile 依赖管理
Miniconda / Conda    管理 xiaozhi-server Python 3.10 环境
MySQL 8              本地开发数据库
Redis                本地缓存服务
FFmpeg / libopus     xiaozhi-server 音频处理依赖
```

## 2. 安装系统基础依赖

```bash
sudo apt update
sudo apt install -y \
  ca-certificates \
  curl \
  wget \
  gnupg \
  lsb-release \
  software-properties-common \
  build-essential \
  pkg-config \
  git \
  git-lfs \
  unzip \
  vim \
  net-tools \
  iproute2 \
  ffmpeg \
  libopus0 \
  libopus-dev \
  libsndfile1 \
  portaudio19-dev \
  libasound2-plugins

git lfs install
```

说明：

- `ffmpeg` 和 `libopus` 是 `xiaozhi-server` 音频处理必需依赖。
- `build-essential`、`pkg-config` 用于编译部分 Python 或 Node 原生依赖。
- `git-lfs` 用于后续需要拉取大模型或大文件的场景。

## 3. 安装 JDK 21 和 Maven

`manager-api` 是 Spring Boot 项目，`pom.xml` 中要求 Java 21。

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk maven

java -version
mvn -version
```

如果 `openjdk-21-jdk` 在当前 Ubuntu 软件源中不可用，先确认系统源是否完整启用了 `universe`：

```bash
sudo add-apt-repository universe
sudo apt update
sudo apt install -y openjdk-21-jdk maven
```

## 4. 安装 Node.js、npm 和 pnpm

推荐使用 Node.js 22 LTS。`manager-web` 使用 npm，`manager-mobile` 的 `package.json` 指定使用 pnpm。

```bash
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt install -y nodejs

node -v
npm -v

sudo corepack enable
corepack prepare pnpm@10.10.0 --activate
pnpm -v
```

说明：

- `manager-web` 使用 `npm install` 和 `npm run serve`。
- `manager-mobile` 必须使用 `pnpm install`，不要改用 npm 或 yarn。
- 如果 `corepack` 不可用，可以使用 `sudo npm install -g pnpm@10.10.0`。

## 5. 安装 MySQL 和 Redis

本地源码开发推荐直接使用 Ubuntu 系统服务，便于 IDE 调试和端口检查。

```bash
sudo apt update
sudo apt install -y mysql-server redis-server

sudo systemctl enable --now mysql
sudo systemctl enable --now redis-server

sudo systemctl status mysql --no-pager
sudo systemctl status redis-server --no-pager
```

如果是在 WSL2 中使用 Ubuntu，且 `systemctl` 不可用，可以临时启动服务：

```bash
sudo service mysql start
sudo service redis-server start
```

创建本地开发数据库：

```bash
sudo mysql
```

进入 MySQL 后执行：

```sql
CREATE DATABASE IF NOT EXISTS xiaozhi_esp32_server
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'xiaozhi_dev'@'localhost' IDENTIFIED BY 'xiaozhi_dev_password';
GRANT ALL PRIVILEGES ON xiaozhi_esp32_server.* TO 'xiaozhi_dev'@'localhost';
FLUSH PRIVILEGES;
```

说明：

- 上面的账号和密码只用于本地开发。
- 不要把真实数据库密码写入 Git。
- `manager-api` 启动后会通过 Liquibase 自动应用 `db/changelog` 下的数据库迁移。

## 6. 安装 Conda 和 Python 3.10 环境

`xiaozhi-server/requirements.txt` 推荐 Python 3.10。Ubuntu 22.04 自带 Python 3.10，但本项目建议使用 Conda 隔离依赖。

安装 Miniconda：

```bash
mkdir -p ~/downloads
cd ~/downloads
wget https://repo.anaconda.com/miniconda/Miniconda3-latest-Linux-x86_64.sh
bash Miniconda3-latest-Linux-x86_64.sh
```

安装完成后重新打开终端，或执行：

```bash
source ~/.bashrc
```

创建项目环境：

```bash
conda create -n xiaozhi-esp32-server python=3.10 -y
conda activate xiaozhi-esp32-server

conda install -y -c conda-forge ffmpeg libopus libiconv
python -V
```

安装 Python 依赖：

```bash
cd main/xiaozhi-server
pip config set global.index-url https://mirrors.aliyun.com/pypi/simple/
pip install -r requirements.txt
```

如果本地开发机不使用 NVIDIA GPU，推荐先安装 PyTorch CPU wheel，再安装其余依赖，避免从 PyPI 下载体积更大的默认 `torch` 包：

```bash
cd main/xiaozhi-server
pip config set global.index-url https://mirrors.aliyun.com/pypi/simple/

pip install --trusted-host download.pytorch.org \
  --index-url https://download.pytorch.org/whl/cpu \
  "torch==2.2.2+cpu" \
  "torchaudio==2.2.2+cpu"

grep -Ev '^(torch|torchaudio)==' requirements.txt > /tmp/xiaozhi-requirements-no-torch.txt
pip install -r /tmp/xiaozhi-requirements-no-torch.txt
```

说明：

- `requirements.txt` 中的 `torch==2.2.2` 默认 wheel 体积较大，本地 CPU 开发不需要下载 GPU 相关包。
- 如果需要 GPU/CUDA 环境，不要使用上述 CPU wheel 命令，应按显卡驱动和 CUDA 版本选择 PyTorch 官方安装命令。

## 7. 安装前端依赖

安装 `manager-web` 依赖：

```bash
cd main/manager-web
npm install
```

安装 `manager-mobile` 依赖：

```bash
cd main/manager-mobile
pnpm install
```

## 8. 准备本地配置

### 8.1 manager-api 配置

编辑本地开发配置：

```text
main/manager-api/src/main/resources/application-dev.yml
```

本地数据库建议配置为：

```yaml
spring:
  datasource:
    username: xiaozhi_dev
    password: xiaozhi_dev_password
  data:
    redis:
      host: localhost
      port: 6379
      password:
      database: 0
```

### 8.2 xiaozhi-server 配置

私有配置文件必须放在：

```text
main/xiaozhi-server/data/.config.yaml
```

如果文件不存在，可以从模板复制：

```bash
cd main/xiaozhi-server
mkdir -p data
cp config_from_api.yaml data/.config.yaml
```

本地开发时需要确认：

```yaml
manager-api:
  url: http://127.0.0.1:8002/xiaozhi
  secret: 本地智控台参数管理中的 server.secret
```

注意：

- `data/.config.yaml` 是本地私有配置，禁止提交到 Git。
- 生产环境配置不得复制到本地开发机。

## 9. 下载语音识别模型

默认 ASR 模型目录：

```text
main/xiaozhi-server/models/SenseVoiceSmall/model.pt
```

创建目录：

```bash
mkdir -p main/xiaozhi-server/models/SenseVoiceSmall
```

模型文件下载方式参考：

- 阿里魔搭：`https://modelscope.cn/models/iic/SenseVoiceSmall/resolve/master/model.pt`
- 百度网盘：见 `docs/deployment/Deployment_all.md` 中的 SenseVoiceSmall 下载说明

模型文件较大，不允许提交到 Git。

## 10. 启动顺序

开发时建议按以下顺序启动：

```text
1. MySQL
2. Redis
3. manager-api
4. manager-web
5. xiaozhi-server
6. manager-mobile，需要时再启动
```

启动 `manager-api`：

```bash
scripts/dev start api
```

默认会使用快速启动路径。需要强制重新编译时使用 `DEV_API_FORCE_COMPILE=1 scripts/dev start api`；需要走 Maven 插件兼容路径时使用 `DEV_API_MAVEN_RUN=1 scripts/dev start api`。

启动 `manager-web`：

```bash
cd main/manager-web
npm run serve
```

启动 `xiaozhi-server`：

```bash
scripts/dev start server
```

如果希望一次性后台启动本地开发服务，推荐使用项目脚本：

```bash
scripts/dev start all
```

说明：

- `main/xiaozhi-server` 必须使用 Conda 环境 `xiaozhi-esp32-server`，禁止直接用系统 `python`、`python3`、`pip` 或 `pytest` 验证该模块。
- `scripts/dev start server` 会优先使用项目 Conda 解释器；如果 Conda 安装路径特殊，可以显式设置 `XIAOZHI_SERVER_PYTHON=/path/to/env/bin/python`。
- `scripts/dev` 会把 xiaozhi-server 日志写入 `runtime/logs/dev/xiaozhi-server.log`，PID 写入 `runtime/run/xiaozhi-server.pid`。
- xiaozhi-server 后台启动时必须将标准输入指向 `/dev/null`，并脱离调用者进程组；否则 `app.py` 的标准输入监听在非交互环境下可能导致服务启动后立即退出。
- 本项目的 `app.py` 只在交互式终端中启用标准输入监听；开发脚本优先使用 Conda 环境中的真实 Python，并用 `setsid` 启动后台进程。

启动 `manager-mobile` H5：

```bash
cd main/manager-mobile
pnpm dev:h5
```

## 11. 验证命令

安装完成后执行：

```bash
java -version
mvn -version
node -v
npm -v
pnpm -v
conda --version
python -V
mysql --version
redis-server --version
ffmpeg -version
```

端口检查：

```bash
ss -lntp | grep -E ':(3306|6379|8000|8001|8002|8003)\b' || true
```

默认访问地址：

```text
manager-web:      http://127.0.0.1:8001
manager-api:      http://127.0.0.1:8002/xiaozhi/doc.html
xiaozhi-server:   ws://127.0.0.1:8000/xiaozhi/v1/
HTTP / OTA:       http://127.0.0.1:8003
```

## 12. 本地开发规则

必须遵守：

- 本文档只用于 `local` 开发环境。
- 本地开发配置、数据库密码、API Key、模型文件、日志和运行数据不得提交。
- 修改数据库结构必须新增 Liquibase 迁移脚本，并登记到 `db.changelog-master.yaml`。
- 本地端口冲突应通过开发配置、启动参数或代理处理，不要随意修改源码默认端口。
- 生产发布和服务器升级仍按 `docs/standards/development-specification.md` 和 `docs/deployment/` 执行。

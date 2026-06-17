# Git-only Docker 生产部署

本文档是本项目生产安装主入口。

生产服务器只需要能访问私有 Git 仓库，不需要访问私有 Docker 镜像仓库，也不需要执行 Docker 镜像仓库登录。

部署流程：

```text
git pull
-> 本机 docker build
-> docker compose up -d
```

安装过程中不需要下载或放置任何语音模型文件。ASR、VAD、LLM、TTS、VLLM、Memory、Intent 都可以先为空，服务启动后再到智控台配置。

## 快速命令

首次部署：

```bash
sudo mkdir -p /opt/xiaozhi-esp32-server-src
sudo chown -R "$USER":"$USER" /opt/xiaozhi-esp32-server-src
git clone ssh://git@10.144.92.32:222/gaoruirui688/xiaozhi-esp32-server.git /opt/xiaozhi-esp32-server-src
cd /opt/xiaozhi-esp32-server-src
sudo scripts/install-private-docker.sh
```

后续升级当前分支：

```bash
cd /opt/xiaozhi-esp32-server-src
scripts/upgrade-git-docker.sh
```

升级到指定 tag：

```bash
cd /opt/xiaozhi-esp32-server-src
REF=v版本号 scripts/upgrade-git-docker.sh
```

## 服务器准备

服务器需要：

```text
Docker
Docker Compose 插件
私有 Git 仓库访问权限
能拉取公共 Docker 基础镜像
```

服务器不需要单独安装 Maven、Node.js、Conda、Python 依赖。它们会在 Docker 构建过程中使用容器完成。

当前会用到的公共基础镜像：

```text
python:3.10-slim
node:18
maven:3.9.4-eclipse-temurin-21
bellsoft/liberica-runtime-container:jre-21-glibc
mysql:latest
redis:8.0
```

## 首次部署

准备源码目录：

```bash
sudo mkdir -p /opt/xiaozhi-esp32-server-src
sudo chown -R "$USER":"$USER" /opt/xiaozhi-esp32-server-src
git clone ssh://git@10.144.92.32:222/gaoruirui688/xiaozhi-esp32-server.git /opt/xiaozhi-esp32-server-src
cd /opt/xiaozhi-esp32-server-src
```

切到要部署的分支或 tag：

```bash
git checkout main
git pull --ff-only origin main
```

如果部署的是发布 tag：

```bash
git fetch --tags origin
git checkout v版本号
```

一键安装并启动：

```bash
sudo scripts/install-private-docker.sh
```

脚本会做这些事：

```text
构建本地镜像 xiaozhi-esp32-server-local:server-base
构建本地镜像 xiaozhi-esp32-server-local:server_latest
构建本地镜像 xiaozhi-esp32-server-local:web_latest
创建 /opt/xiaozhi-server 运行目录
生成 /opt/xiaozhi-server/.env
启动 Docker Compose 服务
```

生产运行目录：

```text
/opt/xiaozhi-server/docker-compose_all.yml
/opt/xiaozhi-server/.env
/opt/xiaozhi-server/data/.config.yaml
/opt/xiaozhi-server/mysql/data
/opt/xiaozhi-server/uploadfile
```

这些是服务器私有配置和运行数据，不要提交到 Git。

## 访问后台

查看容器：

```bash
docker compose -f /opt/xiaozhi-server/docker-compose_all.yml \
  --env-file /opt/xiaozhi-server/.env ps
```

浏览器打开：

```text
http://服务器IP:8002
```

首次进入智控台后，注册管理员账号。

## 配置 server.secret

进入智控台参数管理，找到：

```text
server.secret
```

复制它的值，写入服务器文件：

```text
/opt/xiaozhi-server/data/.config.yaml
```

示例：

```yaml
manager-api:
  url: http://xiaozhi-esp32-server-web:8002/xiaozhi
  secret: 粘贴 server.secret
```

保存后重启核心服务：

```bash
docker restart xiaozhi-esp32-server
```

## 配置模型

安装阶段可以不配置模型。

需要语音对话时，再到智控台按实际需要配置：

```text
ASR   语音识别
VAD   语音活动检测
LLM   大语言模型
TTS   语音合成
VLLM  视觉语言模型
Memory / Intent 等可选能力
```

配置完成后，在智能体配置中选择对应模型，然后重启核心服务：

```bash
docker restart xiaozhi-esp32-server
```

## 一键升级

进入代码目录：

```bash
cd /opt/xiaozhi-esp32-server-src
```

升级当前分支：

```bash
scripts/upgrade-git-docker.sh
```

升级到指定 tag：

```bash
REF=v版本号 scripts/upgrade-git-docker.sh
```

升级脚本会先拉取代码，再重新构建本地业务镜像并执行 `docker compose up -d`。数据库、配置和上传文件继续使用 `/opt/xiaozhi-server/` 下的持久化目录，不会因为重建镜像丢失。

## 常用维护

查看服务：

```bash
docker compose -f /opt/xiaozhi-server/docker-compose_all.yml --env-file /opt/xiaozhi-server/.env ps
```

查看日志：

```bash
docker logs -f xiaozhi-esp32-server
docker logs -f xiaozhi-esp32-server-web
```

停止服务：

```bash
docker compose -f /opt/xiaozhi-server/docker-compose_all.yml --env-file /opt/xiaozhi-server/.env down
```

不要删除：

```text
/opt/xiaozhi-server/mysql/data
/opt/xiaozhi-server/data
/opt/xiaozhi-server/uploadfile
```

它们保存数据库、私有配置和上传文件。

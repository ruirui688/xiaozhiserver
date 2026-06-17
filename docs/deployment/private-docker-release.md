# Git-only Docker 生产部署

本文档是生产服务器安装和升级入口。

生产服务器只需要能访问私有 Git 仓库，不需要登录私有 Docker 镜像仓库。

目录约定：

```text
~/work/xiaozhi-esp32-server  项目源码目录
/opt/xiaozhi-data            生产数据目录
```

`/opt/xiaozhi-data` 保存数据库、私有配置和上传文件。升级会重建业务镜像和重启容器，但不会删除这个目录。

安装阶段不需要下载或放置任何语音模型文件。ASR、VAD、LLM、TTS、VLLM、Memory、Intent 都可以先为空，服务启动后再到智控台配置。

## 快速命令

首次部署：

```bash
mkdir -p ~/work
git clone ssh://git@10.144.92.32:222/gaoruirui688/xiaozhi-esp32-server.git ~/work/xiaozhi-esp32-server
cd ~/work/xiaozhi-esp32-server
sudo scripts/deploy
```

升级当前分支：

```bash
cd ~/work/xiaozhi-esp32-server
scripts/upgrade
```

升级到指定 tag：

```bash
cd ~/work/xiaozhi-esp32-server
REF=v版本号 scripts/upgrade
```

## 服务器要求

需要安装：

```text
Docker
Docker Compose 插件
Git
```

服务器还需要：

```text
私有 Git 仓库访问权限
公共 Docker 基础镜像拉取能力
```

不需要单独安装：

```text
Maven
Node.js
Conda
Python 依赖
```

这些构建依赖会在 Docker 构建过程中处理。

## 首次部署

准备目录并拉取代码：

```bash
mkdir -p ~/work
git clone ssh://git@10.144.92.32:222/gaoruirui688/xiaozhi-esp32-server.git ~/work/xiaozhi-esp32-server
cd ~/work/xiaozhi-esp32-server
```

部署 `main`：

```bash
git checkout main
git pull --ff-only origin main
sudo scripts/deploy
```

部署指定 tag：

```bash
git fetch --tags origin
git checkout v版本号
sudo scripts/deploy
```

`scripts/deploy` 会完成：

```text
构建本地业务镜像
创建 /opt/xiaozhi-data
生成 /opt/xiaozhi-data/.env
初始化 /opt/xiaozhi-data/data/.config.yaml
启动 Docker Compose 服务
```

运行目录：

```text
/opt/xiaozhi-data/docker-compose_all.yml
/opt/xiaozhi-data/.env
/opt/xiaozhi-data/data/.config.yaml
/opt/xiaozhi-data/mysql/data
/opt/xiaozhi-data/uploadfile
```

## 访问后台

查看服务：

```bash
docker compose -f /opt/xiaozhi-data/docker-compose_all.yml \
  --env-file /opt/xiaozhi-data/.env ps
```

浏览器访问：

```text
http://服务器IP:8002
```

首次进入智控台后，注册管理员账号。

## 配置 server.secret

进入智控台参数管理，找到：

```text
server.secret
```

复制它的值，写入：

```text
/opt/xiaozhi-data/data/.config.yaml
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

升级当前分支：

```bash
cd ~/work/xiaozhi-esp32-server
scripts/upgrade
```

升级到指定 tag：

```bash
cd ~/work/xiaozhi-esp32-server
REF=v版本号 scripts/upgrade
```

升级脚本会先拉取代码，再重新构建本地业务镜像，并执行 `docker compose up -d`。

这些数据会继续保留：

```text
管理员账号
模型配置
智能体配置
设备记录
server.secret
上传文件
MySQL 数据
```

## 常用维护

查看服务：

```bash
docker compose -f /opt/xiaozhi-data/docker-compose_all.yml --env-file /opt/xiaozhi-data/.env ps
```

查看日志：

```bash
docker logs -f xiaozhi-esp32-server
docker logs -f xiaozhi-esp32-server-web
```

停止服务：

```bash
docker compose -f /opt/xiaozhi-data/docker-compose_all.yml --env-file /opt/xiaozhi-data/.env down
```

不要删除：

```text
/opt/xiaozhi-data/mysql/data
/opt/xiaozhi-data/data
/opt/xiaozhi-data/uploadfile
```

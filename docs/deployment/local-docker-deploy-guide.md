# 本地 Docker 部署指南

> 编写日期：2026-06-26 | 适用环境：本地开发/测试 | 区别于：生产环境 ACR 镜像拉取部署

---

## 目录

- [一、整体架构概览](#一整体架构概览)
- [二、前置条件](#二前置条件)
- [三、本地构建 Docker 镜像](#三本地构建-docker-镜像)
- [四、本地 Docker Compose 部署](#四本地-docker-compose-部署)
- [五、首次启动与初始化配置](#五首次启动与初始化配置)
- [六、日常操作](#六日常操作)
- [七、本地 Docker 开发 vs 纯本地开发](#七本地-docker-开发-vs-纯本地开发)
- [八、常见问题排查](#八常见问题排查)
- [附录：关键文件速查](#附录关键文件速查)

---

## 一、整体架构概览

### 1.1 本地 Docker 部署拓扑

```
                    Docker Network (default)
                    =========================

  宿主机                         容器
  ──────                        ──────

  localhost:8000 ──────────→ xiaozhi-esp32-server (Python)
                              ├── WebSocket 服务 (8000)
                              ├── HTTP OTA/Vision (8003)
                              └── 配置文件: data/.config.yaml

  localhost:8002 ──────────→ xiaozhi-esp32-server-web (Nginx + Java)
                              ├── Nginx :8002
                              │     ├── /          → Vue.js 前端静态文件
                              │     └── /xiaozhi/* → 反向代理到 Java
                              └── Java :8003 (内部)
                                    ├── Spring Boot 3.4.3
                                    ├── Liquibase 自动迁移
                                    └── MyBatis-Plus + Redis

                              xiaozhi-esp32-server-db (MySQL 8.4)
                              ├── 端口 3306 (容器内)
                              ├── 数据库: xiaozhi_esp32_server
                              └── 数据卷: ./mysql/data

                              xiaozhi-esp32-server-redis (Redis 8.0)
                              ├── 端口 6379 (容器内)
                              ├── AOF 持久化
                              └── 数据卷: ./redis/data
```

### 1.2 镜像关系

```
Dockerfile-server-base (基础镜像，含 Python + 系统依赖 + pip 包)
        │
        └──→ Dockerfile-server (应用镜像，只加源代码)
               ├── 构建产物: xiaozhi-server-local:latest
               └── 启动命令: python app.py

Dockerfile-web (多阶段构建)
    ├── Stage 1: node:18 构建 Vue.js 前端
    ├── Stage 2: maven:21 编译 Java JAR
    └── Stage 3: JRE 21 + Nginx 运行
         └── 构建产物: xiaozhi-web-local:latest
```

### 1.3 本地 vs 生产的关键区别

| 维度 | 本地 Docker | 生产环境 |
|---|---|---|
| **镜像来源** | 本地 `docker build` 构建 | ACR `docker pull` 拉取 |
| **镜像标签** | `*-local:latest` | `crpi-...aliyuncs.com/yihuixiaozhi/*:latest` |
| **MySQL 镜像** | `mysql:latest`（官方） | ACR 镜像（`.../mysql:8.4`） |
| **Redis 镜像** | `redis:8.0`（官方） | ACR 镜像（`.../redis:8.0`） |
| **配置文件** | `data/.config.yaml`（手动创建） | `data/.config.yaml`（install 脚本自动生成） |
| **代码更新** | 重新 `docker build` | `docker compose pull` |
| **compose 文件** | `main/xiaozhi-server/docker-compose_all.yml` | `deploy/production/docker-compose.yml` |

---

## 二、前置条件

### 2.1 软件要求

| 软件 | 最低版本 | 检查命令 |
|---|---|---|
| Docker | 24.0+ | `docker --version` |
| Docker Compose | v2（`docker compose` 子命令） | `docker compose version` |
| Git | 2.30+ | `git --version` |
| Bash | 4.0+ | `bash --version` |

> **注意**：本地构建不需要安装 JDK、Maven、Node.js、Conda——全部在 Docker 构建阶段完成。

### 2.2 系统资源建议

| 资源 | 最低 | 推荐 |
|---|---|---|
| CPU | 4 核 | 8 核 |
| 内存 | 8 GB | 16 GB |
| 磁盘 | 20 GB 空闲 | 50 GB SSD |

### 2.3 确认 Docker 环境

```bash
# 确认 Docker 正在运行
docker info

# 确认 compose 插件可用
docker compose version
```

---

## 三、本地构建 Docker 镜像

### 3.1 镜像构建流程

本地需要构建 **两个** 业务镜像（MySQL 和 Redis 使用官方镜像，无需构建）：

```
第一步: 构建 server-base 基础镜像（只构建一次，依赖不变时无需重复）
   ↓
第二步: 构建 server 应用镜像（代码变更后需要重新构建）
   ↓
第三步: 构建 web 镜像（前端或 Java 代码变更后需重新构建）
```

### 3.2 使用构建脚本（推荐）

项目提供了 `scripts/docker-build-private.sh`，一键完成全部构建：

```bash
# 在项目根目录执行

# 构建全部（server-base + server + web）
bash scripts/docker-build-private.sh

# 仅构建 server 相关（跳过后端 web 镜像）
BUILD_WEB=false bash scripts/docker-build-private.sh

# 仅构建 web 镜像（跳过 server）
BUILD_SERVER=false bash scripts/docker-build-private.sh

# 强制重新构建基础镜像（默认只有依赖变化时才重建）
BUILD_BASE=true bash scripts/docker-build-private.sh
```

**构建时间参考**（首次，无缓存）：
- `Dockerfile-server-base`：约 5-10 分钟（下载 Python 基础镜像 + pip 安装依赖）
- `Dockerfile-server`：约 30 秒（仅复制源代码）
- `Dockerfile-web`：约 10-20 分钟（npm install + npm build + mvn package）

### 3.3 手动分步构建

如果需要更精细的控制，可以手动执行：

```bash
# 步骤 1：构建基础镜像
docker build \
  -t xiaozhi-server-base-local:latest \
  -f Dockerfile-server-base .

# 步骤 2：构建 server 应用镜像
docker build \
  --build-arg SERVER_BASE_IMAGE=xiaozhi-server-base-local:latest \
  -t xiaozhi-server-local:latest \
  -f Dockerfile-server .

# 步骤 3：构建 web 镜像（前端 + Java 后端）
docker build \
  -t xiaozhi-web-local:latest \
  -f Dockerfile-web .
```

### 3.4 验证镜像构建成功

```bash
docker images | grep -E "xiaozhi.*local"
```

预期输出：
```
xiaozhi-web-local            latest    <IMAGE_ID>    <SIZE>
xiaozhi-server-local         latest    <IMAGE_ID>    <SIZE>
xiaozhi-server-base-local    latest    <IMAGE_ID>    <SIZE>
```

---

## 四、本地 Docker Compose 部署

### 4.1 准备部署目录

```bash
# 选择一个持久化数据目录（建议在项目外，避免被 git 追踪）
mkdir -p ~/xiaozhi-docker-data/mysql/data
mkdir -p ~/xiaozhi-docker-data/redis/data
mkdir -p ~/xiaozhi-docker-data/server/data
mkdir -p ~/xiaozhi-docker-data/uploadfile
mkdir -p ~/xiaozhi-docker-data/music

cd ~/xiaozhi-docker-data
```

### 4.2 编写 docker-compose.yml

在 `~/xiaozhi-docker-data/docker-compose.yml` 创建：

```yaml
services:
  # ==================== MySQL 数据库 ====================
  xiaozhi-esp32-server-db:
    image: mysql:8.4
    container_name: xiaozhi-esp32-server-db
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: "123456"
      MYSQL_DATABASE: "xiaozhi_esp32_server"
      MYSQL_INITDB_ARGS: "--character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci"
    volumes:
      - ./mysql/data:/var/lib/mysql
    expose:
      - "3306"
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      timeout: 45s
      interval: 10s
      retries: 10
    networks:
      - xiaozhi-net

  # ==================== Redis 缓存 ====================
  xiaozhi-esp32-server-redis:
    image: redis:8.0
    container_name: xiaozhi-esp32-server-redis
    restart: unless-stopped
    command: redis-server --appendonly yes
    volumes:
      - ./redis/data:/data
    expose:
      - "6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 3
    networks:
      - xiaozhi-net

  # ==================== Web 服务（Java 后端 + Vue 前端） ====================
  xiaozhi-esp32-server-web:
    image: xiaozhi-web-local:latest
    container_name: xiaozhi-esp32-server-web
    restart: unless-stopped
    ports:
      - "8002:8002"
    environment:
      # 数据库连接（Docker 内部 DNS）
      SPRING_DATASOURCE_DRUID_URL: "jdbc:mysql://xiaozhi-esp32-server-db:3306/xiaozhi_esp32_server?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&nullCatalogMeansCurrent=true&useSSL=false&allowPublicKeyRetrieval=true"
      SPRING_DATASOURCE_DRUID_USERNAME: "root"
      SPRING_DATASOURCE_DRUID_PASSWORD: "123456"
      # Redis 连接
      SPRING_DATA_REDIS_HOST: "xiaozhi-esp32-server-redis"
      SPRING_DATA_REDIS_PORT: "6379"
      SPRING_DATA_REDIS_PASSWORD: ""
      # Python server 地址（供 Java 回调）
      XIAOZHI_SERVER_HTTP_BASE_URL: "http://xiaozhi-esp32-server:8003"
      # 音乐文件目录
      XIAOZHI_MUSIC_DIR: "/music"
    volumes:
      - ./uploadfile:/uploadfile
      - ./music:/music
    depends_on:
      xiaozhi-esp32-server-db:
        condition: service_healthy
      xiaozhi-esp32-server-redis:
        condition: service_healthy
    networks:
      - xiaozhi-net

  # ==================== Python 语音服务 ====================
  xiaozhi-esp32-server:
    image: xiaozhi-server-local:latest
    container_name: xiaozhi-esp32-server
    restart: unless-stopped
    ports:
      - "8000:8000"   # WebSocket（ESP32 设备连接）
      - "8003:8003"   # HTTP（OTA 升级 / Vision API）
    volumes:
      - ./server/data:/opt/xiaozhi-esp32-server/data
      - ./music:/opt/xiaozhi-esp32-server/music
    security_opt:
      - seccomp:unconfined
    depends_on:
      - xiaozhi-esp32-server-web
    networks:
      - xiaozhi-net

networks:
  xiaozhi-net:
    driver: bridge
```

### 4.3 配置文件准备

**创建 `server/data/.config.yaml`**（Python 服务私有配置）：

```bash
mkdir -p ~/xiaozhi-docker-data/server/data

cat > ~/xiaozhi-docker-data/server/data/.config.yaml << 'EOF'
# 本地 Docker 部署配置
# 注意：此文件在 .gitignore 中，不会被提交

server:
  ip: 0.0.0.0
  port: 8000
  http_port: 8003
  # 视觉识别接口地址（本地 Docker 部署时使用宿主机 IP）
  vision_explain: http://localhost:8003/mcp/vision/explain

manager-api:
  # Java 管理后台地址（Docker 内部 DNS）
  url: http://xiaozhi-esp32-server-web:8002/xiaozhi
  # ⚠️ 必须填写！首次启动后从管理后台获取
  # 登录管理后台 → 参数管理 → 找到 server.secret → 复制到这里
  secret: "你的server.secret值"

# 默认系统提示词模板
prompt_template: agent-base-prompt.txt
EOF
```

> ⚠️ **重要**：`manager-api.secret` 不能留空。首次启动后，登录管理后台 `http://localhost:8002`，进入「参数管理」找到 `server.secret`，将其值填入此文件，然后重启 Python 服务。

---

## 五、首次启动与初始化配置

### 5.1 启动全部服务

```bash
cd ~/xiaozhi-docker-data

# 启动（首次会自动拉取 MySQL/Redis 镜像）
docker compose up -d

# 观察启动日志
docker compose logs -f
```

### 5.2 等待服务就绪

```bash
# 检查所有容器运行状态
docker compose ps

# 预期输出：4 个容器全部 healthy 或 running
# NAME                           STATUS
# xiaozhi-esp32-server-db        healthy
# xiaozhi-esp32-server-redis     healthy
# xiaozhi-esp32-server-web       running
# xiaozhi-esp32-server           running
```

启动顺序：
1. MySQL 容器启动 → 健康检查通过（约 30-60 秒）
2. Redis 容器启动 → 健康检查通过（约 5-10 秒）
3. Web 容器启动 → Liquibase 自动执行数据库迁移 → Java 应用就绪（约 30-90 秒）
4. Python 容器启动 → 连接 Java API → 就绪（约 10 秒）

### 5.3 初始化管理员账号

```bash
# 检查 Web 服务是否就绪
curl -s http://localhost:8002/xiaozhi/actuator/health

# 访问管理后台
# 浏览器打开: http://localhost:8002
```

**首次访问流程**：
1. 打开 `http://localhost:8002`
2. **第一个注册的账号自动成为管理员**
3. 注册完成后进入「参数管理」页面
4. 找到 `server.secret` 参数，复制其值
5. 编辑 `~/xiaozhi-docker-data/server/data/.config.yaml`，将 `secret` 字段更新为复制的值
6. 重启 Python 服务使配置生效：

```bash
docker compose restart xiaozhi-esp32-server
```

### 5.4 验证部署成功

```bash
# 验证 Web 前端
curl -s http://localhost:8002 | head -20

# 验证 Java API
curl -s http://localhost:8002/xiaozhi/actuator/health

# 验证 Python 服务（WebSocket 端口）
# （需要通过 ESP32 设备或 WebSocket 客户端测试）

# 验证 Python HTTP 服务
curl -s http://localhost:8003/

# 检查 Python 日志中是否有 "获取配置成功" 字样
docker compose logs xiaozhi-esp32-server | grep -i "配置"
```

---

## 六、日常操作

### 6.1 代码变更后更新

```bash
# 在项目根目录

# 1. 拉取最新代码
git pull

# 2. 重新构建变更的镜像
#    仅 Python 代码变更 → 重建 server 镜像
docker build \
  --build-arg SERVER_BASE_IMAGE=xiaozhi-server-base-local:latest \
  -t xiaozhi-server-local:latest \
  -f Dockerfile-server .

#    前端或 Java 代码变更 → 重建 web 镜像
docker build -t xiaozhi-web-local:latest -f Dockerfile-web .

# 3. 重启对应服务
cd ~/xiaozhi-docker-data
docker compose up -d    # 自动检测镜像变更并重建容器
```

### 6.2 查看日志

```bash
cd ~/xiaozhi-docker-data

# 查看所有服务日志
docker compose logs -f

# 查看特定服务日志
docker compose logs -f xiaozhi-esp32-server      # Python 服务
docker compose logs -f xiaozhi-esp32-server-web   # Java + Web
docker compose logs -f xiaozhi-esp32-server-db    # MySQL
docker compose logs -f xiaozhi-esp32-server-redis # Redis

# 查看最近 100 行
docker compose logs --tail=100 xiaozhi-esp32-server
```

### 6.3 启停控制

```bash
cd ~/xiaozhi-docker-data

# 停止所有服务（保留数据）
docker compose stop

# 启动已停止的服务
docker compose start

# 重启特定服务
docker compose restart xiaozhi-esp32-server
docker compose restart xiaozhi-esp32-server-web

# 完全停止并删除容器（保留数据卷）
docker compose down

# 完全停止并删除容器 + 数据卷（⚠️ 数据全部丢失）
docker compose down -v
```

### 6.4 进入容器调试

```bash
# 进入 Python 服务容器
docker exec -it xiaozhi-esp32-server bash

# 进入 Web 容器
docker exec -it xiaozhi-esp32-server-web bash

# 进入 MySQL 容器
docker exec -it xiaozhi-esp32-server-db mysql -uroot -p123456 xiaozhi_esp32_server

# 进入 Redis 容器
docker exec -it xiaozhi-esp32-server-redis redis-cli
```

### 6.5 数据备份

```bash
cd ~/xiaozhi-docker-data

# 备份 MySQL 数据
docker exec xiaozhi-esp32-server-db \
  mysqldump -uroot -p123456 xiaozhi_esp32_server \
  > backup_$(date +%Y%m%d).sql

# 备份整个数据目录
tar -czf xiaozhi-data-backup-$(date +%Y%m%d).tar.gz \
  mysql/data/ redis/data/ server/data/ uploadfile/ music/
```

### 6.6 镜像清理

```bash
# 清理构建缓存（释放磁盘空间）
docker builder prune

# 清理未使用的镜像
docker image prune

# 清理所有未使用的 Docker 资源（谨慎）
docker system prune -a
```

---

## 七、本地 Docker 开发 vs 纯本地开发

项目提供了两种本地运行方式，各有适用场景：

| 维度 | Docker 部署（本文档） | 纯本地开发（`scripts/dev`） |
|---|---|---|
| **适用场景** | 集成测试、部署验证、环境一致性 | 代码开发、热重载、快速迭代 |
| **Python 运行** | Docker 容器内 | Conda 环境 `xiaozhi-esp32-server` |
| **Java 运行** | Docker 容器内（JAR） | 本机 JDK 21（可热加载） |
| **前端开发** | 构建产物（不可热更新） | `npm run serve`（HMR 热更新） |
| **MySQL/Redis** | Docker 容器 | 本机 systemd 服务或 Docker |
| **代码修改后** | 需重新 build 镜像 | 重启对应进程即可 |
| **配置文件** | `data/.config.yaml`（挂载卷） | `data/.config.yaml`（本地文件） |
| **管理工具** | `docker compose` | `scripts/dev start/stop/logs` |
| **ESP32 连接** | `ws://宿主机IP:8000` | `ws://localhost:8000` |

**建议**：
- **日常开发**：使用 `scripts/dev`，修改代码秒级生效
- **部署前验证**：使用本文档的 Docker 部署，验证镜像和 compose 配置
- **给别人试用**：分享 Docker 部署方式，不需要对方配置开发环境

---

## 八、常见问题排查

### 8.1 端口冲突

```bash
# 检查端口是否被占用
ss -tlnp | grep -E "8000|8002|8003|3306|6379"

# 如果冲突，编辑 docker-compose.yml 修改宿主机端口映射
# 例如将 "8002:8002" 改为 "18002:8002"
```

### 8.2 Python 服务无法连接 Java API

**现象**：Python 日志中出现 "获取配置失败" 或连接超时

**排查**：
```bash
# 1. 确认 Web 容器已就绪
docker compose ps xiaozhi-esp32-server-web

# 2. 从 Python 容器内测试连接
docker exec xiaozhi-esp32-server \
  curl -s http://xiaozhi-esp32-server-web:8002/xiaozhi/actuator/health

# 3. 检查 manager-api.secret 是否正确配置
docker exec xiaozhi-esp32-server \
  cat /opt/xiaozhi-esp32-server/data/.config.yaml
```

### 8.3 数据库连接失败

**现象**：Web 容器日志中出现 "Communications link failure" 或 "Access denied"

**排查**：
```bash
# 1. 确认 MySQL 健康状态
docker compose ps xiaozhi-esp32-server-db

# 2. 从 Web 容器内测试 MySQL 连接
docker exec xiaozhi-esp32-server-web \
  sh -c "echo > /dev/tcp/xiaozhi-esp32-server-db/3306 && echo 'OK'"

# 3. 检查 MySQL root 密码是否匹配
# docker-compose.yml 中 MYSQL_ROOT_PASSWORD 和
# SPRING_DATASOURCE_DRUID_PASSWORD 必须一致
```

### 8.4 前端页面空白

**现象**：浏览器打开 `http://localhost:8002` 显示空白或 404

**排查**：
```bash
# 1. 确认 Nginx 正在运行
docker exec xiaozhi-esp32-server-web nginx -t

# 2. 检查静态文件是否存在
docker exec xiaozhi-esp32-server-web \
  ls /usr/share/nginx/html/index.html

# 3. 查看 Nginx 日志
docker compose logs xiaozhi-esp32-server-web | grep nginx
```

### 8.5 镜像构建失败

**现象**：`docker build` 失败，网络超时或包下载失败

**解决方案**：
```bash
# 配置 Docker 镜像加速（中国内地用户）
# 编辑 /etc/docker/daemon.json
sudo tee /etc/docker/daemon.json << 'EOF'
{
  "registry-mirrors": [
    "https://docker.1ms.run",
    "https://docker.xuanyuan.me"
  ]
}
EOF

sudo systemctl restart docker

# 或者使用项目内置的镜像配置脚本
bash scripts/docker-config-mirror
```

### 8.6 ESP32 设备无法连接

**前提**：ESP32 设备和 Docker 宿主机在同一局域网

```bash
# 1. 确认宿主机 IP
hostname -I

# 2. 确认端口从外部可达
# 在 ESP32 同网段的另一台机器上执行：
curl -s http://<宿主机IP>:8003/

# 3. 如使用 WSL2（Windows），需要端口转发
# 在 Windows PowerShell（管理员）中执行：
netsh interface portproxy add v4tov4 \
  listenport=8000 listenaddress=0.0.0.0 \
  connectport=8000 connectaddress=<WSL2_IP>

# 4. 检查防火墙
sudo ufw status
# 如启用，放行端口：
sudo ufw allow 8000/tcp
sudo ufw allow 8002/tcp
sudo ufw allow 8003/tcp
```

---

## 附录：关键文件速查

### 项目文件

| 文件 | 路径 | 说明 |
|---|---|---|
| Dockerfile-server-base | 项目根目录 | Python 服务基础镜像 |
| Dockerfile-server | 项目根目录 | Python 服务应用镜像 |
| Dockerfile-web | 项目根目录 | Web 服务多阶段构建镜像 |
| docker-compose_all.yml | `main/xiaozhi-server/` | 全栈 compose 模板（参考用） |
| docker-compose.yml | `deploy/production/` | 生产 compose 模板（参考用） |
| config.yaml | `main/xiaozhi-server/` | Python 默认配置（不要修改） |
| config_from_api.yaml | 项目根目录 | API 管理模式配置模板 |
| requirements.txt | `main/xiaozhi-server/` | 本地开发 Python 依赖（含 torch） |
| requirements-docker.txt | `main/xiaozhi-server/` | Docker Python 依赖（不含 torch） |
| start.sh | `docs/docker/` | Web 容器启动脚本 |
| nginx.conf | `docs/docker/` | Web 容器 Nginx 配置 |
| scripts/docker-build-private.sh | `scripts/` | 镜像构建脚本 |

### 数据文件（运行时，不提交 Git）

| 路径 | 说明 |
|---|---|
| `~/xiaozhi-docker-data/docker-compose.yml` | 你的本地 compose 配置 |
| `~/xiaozhi-docker-data/server/data/.config.yaml` | Python 服务私有配置 |
| `~/xiaozhi-docker-data/mysql/data/` | MySQL 持久化数据 |
| `~/xiaozhi-docker-data/redis/data/` | Redis AOF 持久化数据 |
| `~/xiaozhi-docker-data/uploadfile/` | 上传文件存储 |
| `~/xiaozhi-docker-data/music/` | 音乐文件目录 |

### 端口映射速查

| 宿主机端口 | 容器 | 用途 |
|---|---|---|
| 8000 | xiaozhi-esp32-server | WebSocket（ESP32 设备语音流） |
| 8002 | xiaozhi-esp32-server-web | HTTP（管理后台前端 + API） |
| 8003 | xiaozhi-esp32-server | HTTP（OTA 升级 / Vision API） |

---

> 📄 关联文档：[[model-management-architecture-analysis]] | [[semantic-interruption-and-e2e-voice-model-analysis]]

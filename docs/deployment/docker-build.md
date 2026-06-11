# 本地编译 Docker 镜像方法

现在本项目已经使用github自动编译docker功能，本文档是提供给有本地编译docker镜像需求的朋友准备的。

1、安装docker
```
sudo apt-get install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```
2、编译docker镜像
```
#进入项目根目录
# 编译server-base
docker build -t xiaozhi-esp32-server:server-base -f ./Dockerfile-server-base .

# 编译server
docker build \
  --build-arg SERVER_BASE_IMAGE=xiaozhi-esp32-server:server-base \
  -t xiaozhi-esp32-server:server_latest \
  -f ./Dockerfile-server .

# 编译web
docker build -t xiaozhi-esp32-server:web_latest -f ./Dockerfile-web .

# 编译完成后，可以使用docker-compose启动项目
# docker-compose.yml你需要修改成自己编译的镜像版本
cd main/xiaozhi-server
docker compose up -d
```

如果要发布到本项目私有镜像仓库，优先使用：

```bash
scripts/docker-build-private.sh
```

详细发布和生产部署流程见：

```text
docs/deployment/private-docker-release.md
```

# Production Deployment Assets

This directory is the minimal production deployment package.

Production servers should use only these files. Full source code still belongs in
the private Git repository and is used by local machines or build machines to
build and push Docker images.

Runtime data is stored outside the Git checkout:

```text
/opt/xiaozhi-data
```

Important persistent subdirectories:

```text
/opt/xiaozhi-data/data        xiaozhi-server private config
/opt/xiaozhi-data/mysql/data  MySQL data
/opt/xiaozhi-data/uploadfile  manager-api uploads
/opt/xiaozhi-data/music       uploaded server music library
```

Standard production commands:

```bash
sudo docker login crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com
./install
./upgrade
```

`xiaozhi-esp32-server-web` calls the Python benchmark HTTP API through the
Docker service name by default:

```text
XIAOZHI_SERVER_HTTP_BASE_URL=http://xiaozhi-esp32-server:8003
```

Do not set this to `127.0.0.1` inside Docker. In the manager-api container,
`127.0.0.1` means the Java/Web container itself, not the Python server
container.

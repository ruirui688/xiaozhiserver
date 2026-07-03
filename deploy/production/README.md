# Production Deployment Assets

This directory is the minimal production deployment package.

Production servers should use only these files. Full source code still belongs in
the private Git repository and is used by local machines or build machines to
build and push Docker images.

## Release workflow (critical)

A production upgrade pulls from **two independent sources** that must stay in
sync:

```
./upgrade
  ├─ git pull origin main          → deploy scripts (docker-compose.yml, install)
  └─ ./install
       └─ docker pull ... :latest  → Docker images (from Aliyun ACR)
```

**Both sources must originate from the `main` branch.** If you build and push
images from a feature branch while production's deploy scripts come from
`main`, the two will be out of sync — new compose file features (container
dependencies, volume mounts) won't be present, and the image code may not
match the deploy configuration.

### Correct release procedure

```bash
# 1. Merge your changes to main
git checkout main
git merge feature/your-branch
git push origin main

# 2. Build and push images FROM main (images now contain main's code)
scripts/acr-build-push

# 3. On the production server — both sources are now from main
cd ~/work/xiaozhi-deploy/deploy/production
VERSION=<git-short-sha> ./upgrade
```

**Never run `acr-build-push` from a feature branch and then upgrade production
before merging.** The `:latest` tag would carry feature-branch code to
production while deploy scripts remain on `main`.

If the production server does not have a Git checkout (deploy files were
copied manually), update the deploy files first before pulling new images:

```bash
# Manual deploy file update (non-git production server)
scp deploy/production/docker-compose.yml production:/opt/xiaozhi-data/
scp deploy/production/install production:/opt/xiaozhi-data/
```

## Runtime data

Runtime data is stored outside the Git checkout:

```text
/opt/xiaozhi-data
```

Important persistent subdirectories:

```text
/opt/xiaozhi-data/data        xiaozhi-server private config
/opt/xiaozhi-data/mysql/data  MySQL data (InnoDB)
/opt/xiaozhi-data/redis/data  Redis AOF persistence data
/opt/xiaozhi-data/uploadfile  manager-api uploads
/opt/xiaozhi-data/music       uploaded server music library
```

Standard production commands:

```bash
sudo docker login crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com
./install
VERSION=<git-short-sha> ./upgrade
```

`./upgrade` still supports `VERSION=latest`, but `latest` is a mutable tag. Use
the short Git hash printed by `scripts/acr-build-push` when you need to know the
exact image version installed on the server.

Upgrade output is intentionally compact:

```text
Production deploy
  version   image tag requested for this deployment
  current   version stored in /opt/xiaozhi-data/.env from the previous run
  assets    Git branch and deploy script commit before/after pull
  images    business image tags to pull and run

=== Deploy complete ===
Version    final deployed version
Images     final business images attached to running containers
Runtime    final MySQL/Redis images attached to running containers
```

## Container startup order

Compose enforces the following dependency chain:

```
MySQL (healthy) ──┐
                   ├──→ Web/Java (started) ──→ Python server (started)
Redis (healthy) ──┘
```

- `xiaozhi-esp32-server-web` waits for MySQL and Redis health checks, then
  runs Liquibase migrations during Spring Boot startup.
- `xiaozhi-esp32-server` waits for the web container to start before
  launching, avoiding crash-restart loops while the Java API initializes.
- `./install` verifies the web container logs for `Started AdminApplication
  in` (timeout 180s) and exits non-zero if startup fails.

## Redis persistence

Redis runs with AOF (`--appendonly yes`) and stores its data at
`/opt/xiaozhi-data/redis/data`. User sessions survive container restarts and
`./upgrade` runs.

## Resource limits

The production compose file includes runtime limits for a 2 CPU / 4 GB server
with CPU FunASR enabled:

```text
xiaozhi-esp32-server       1.00 CPU / 2048m
xiaozhi-esp32-server-web   0.60 CPU / 1024m
xiaozhi-esp32-server-db    0.30 CPU / 768m
xiaozhi-esp32-server-redis 0.10 CPU / 192m
```

The limits are applied at Docker Compose runtime, not baked into the images.
`memswap_limit` defaults to the same value as `mem_limit` to avoid swap storms
on small hosts. FunASR/SenseVoiceSmall runs in the Python container and needs
about 2 GB of memory for reliable startup on CPU-only hosts.

Override the `XIAOZHI_*_CPUS`, `XIAOZHI_*_MEM_LIMIT`,
`XIAOZHI_FUNASR_THREADS`, `XIAOZHI_MYSQL_INNODB_BUFFER_POOL_SIZE`,
`XIAOZHI_REDIS_MAXMEMORY`, or `XIAOZHI_WEB_JAVA_TOOL_OPTIONS` values in
`/opt/xiaozhi-data/.env` when the server size changes. Existing `.env` values
are preserved during upgrade; if an older deployment still has
`XIAOZHI_SERVER_MEM_LIMIT=768m`, raise it to at least `2048m` before enabling
FunASR.

## Internal service URLs

`xiaozhi-esp32-server-web` calls the Python benchmark HTTP API through the
Docker service name by default:

```text
XIAOZHI_SERVER_HTTP_BASE_URL=http://xiaozhi-esp32-server:8003
```

Do not set this to `127.0.0.1` inside Docker. In the manager-api container,
`127.0.0.1` means the Java/Web container itself, not the Python server
container.

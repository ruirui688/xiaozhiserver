# Production Deployment Assets

This directory is the minimal production deployment package.

Production servers should use only these files. Full source code still belongs in
the private Git repository and is used by local machines or build machines to
build and push Docker images.

## Release workflow (critical)

Each release is now published as one immutable ACR bundle:

```text
xiaozhi release <version>
  ├─ xiaozhi-server:<version>
  ├─ xiaozhi-manager-api:<version>
  ├─ xiaozhi-manager-web:<version>
  └─ xiaozhi-deploy-assets:<version>
       ├─ docker-compose.yml
       ├─ install / upgrade
       ├─ config_from_api.yaml
       ├─ release-manifest.env
       └─ release-contract.env
```

`VERSION=<version> ~/xiaozhi-upgrade` first pulls the matching deploy-assets image,
validates its manifest and environment contract, then runs the bundled install
logic and pulls the three business images recorded by that manifest. The Git
checkout on the production server is only a bootstrap entry; its current branch
does not select the runtime Compose or installation logic.

### Correct release procedure

```bash
# 1. Build and publish one complete versioned release
scripts/acr-build-push

# 2. On the production server, deploy the immutable release
VERSION=<git-short-sha> ~/xiaozhi-upgrade
```

Publishing from a feature branch is technically supported because deploy assets
and images share the same immutable version. Production policy should still
prefer reviewed commits. A dirty worktree is rejected when `PUSH=true` unless
the build is changed to `PUSH=false` for local validation.

The ACR namespace must contain, or permit creation of, the
`xiaozhi-deploy-assets` repository before the first complete release is pushed.

Existing servers need one bootstrap deployment that installs `~/xiaozhi-upgrade`.
Every successful deployment atomically refreshes that command and its resolver
under the persistent data directory. Later pinned upgrades no longer depend on
the server Git branch being updated for each release.

## Runtime data

Runtime data is stored outside the Git checkout, under the deploying user's
home directory by default:

```text
~/xiaozhi-data
```

Important persistent subdirectories:

```text
~/xiaozhi-data/data        xiaozhi-server private config
~/xiaozhi-data/mysql/data  MySQL data (InnoDB)
~/xiaozhi-data/redis/data  Redis AOF persistence data
~/xiaozhi-data/uploadfile  manager-api OTA、人物图片、知识库原件和本地检索索引
~/xiaozhi-data/music       uploaded server music library
```

The install script fixes bind-mount ownership for the non-root containers:

```text
data       999:999  xiaozhi-server writes .auth_key and reads .config.yaml
uploadfile 100:101  manager-api writes OTA firmware and `/uploadfile/person-faces` images
music      100:101  manager-api writes uploaded music; xiaozhi-server reads it
```

First bootstrap command:

```bash
sudo docker login crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com
./install
```

The bootstrap installs the only day-to-day production command:

```bash
VERSION=<git-short-sha> ~/xiaozhi-upgrade
```

`install` no longer runs the whole script as root. It keeps deploy files,
generated compose files, `.env`, database files, uploads, and private config
under `~/xiaozhi-data` by default. The bind-mount ownership fix is performed
through one-shot Docker containers, so elevated host access is limited to Docker
when the current user cannot access Docker directly.

If the current user can access the Docker daemon, `install` uses plain
`docker`. Otherwise it runs Docker as `sudo docker`, which means ACR login must
exist in root's Docker config:

```bash
sudo docker login crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com
```

For legacy deployments that intentionally keep data under `/opt/xiaozhi-data`,
run with an explicit data directory:

```bash
DATA_DIR=/opt/xiaozhi-data ./install
```

The generated `~/xiaozhi-upgrade` remembers that data directory for later runs.

The fixed upgrade command defaults to `VERSION=latest`. The resolver reads the
immutable version from `xiaozhi-deploy-assets:latest` and deploys the matching
version tags rather than independently pulling mutable business-image tags.
Use `VERSION=<git-short-sha> ~/xiaozhi-upgrade` for an explicit release or rollback.

`./install` waits for manager-api startup, reads the generated `server.secret`
from the MySQL container, writes it into
`~/xiaozhi-data/data/.config.yaml`, fixes bind-mount permissions for the
non-root Python container user, and restarts `xiaozhi-server`. First install no
longer requires manually copying `server.secret` from the web console.

Upgrade output is intentionally compact:

```text
Production deploy
  version   image tag requested for this deployment
  current   version stored in DATA_DIR/.env from the previous run
  assets    versioned deploy-assets image, contract version and source commit
  images    business image tags to pull and run

=== Deploy complete ===
Version    final deployed version
Images     final business images attached to running containers
Runtime    final MySQL/Redis images attached to running containers
```

## HTTPS/WSS public entry

WeChat mini programs and production ESP32 devices should use a public HTTPS/WSS
entry. Keep Docker internal URLs unchanged, and terminate TLS in Nginx, SLB, or
another reverse proxy on the host.

For `yh.wejoy.link`, the public addresses are:

```text
manager-web          https://yh.wejoy.link/
manager-api          https://yh.wejoy.link/xiaozhi/
OTA                  https://yh.wejoy.link/xiaozhi/ota/
vision explain       https://yh.wejoy.link/mcp/vision/explain
vision images        https://yh.wejoy.link/mcp/vision/images/<filename>
WebSocket            wss://yh.wejoy.link/xiaozhi/v1/
mini program API     https://yh.wejoy.link/xiaozhi
```

Use `nginx-https.conf.example` as the host Nginx template. The template assumes
Certbot/Let's Encrypt certificate paths under
`/etc/letsencrypt/live/yh.wejoy.link/`; replace the domain and paths only when
your public domain or certificate provider is different. The template proxies:

```text
https://domain/                  -> 127.0.0.1:8002
https://domain/xiaozhi/          -> 127.0.0.1:8002/xiaozhi/
https://domain/xiaozhi/ota/      -> 127.0.0.1:8002/xiaozhi/ota/
https://domain/mcp/vision/explain -> 127.0.0.1:8003/mcp/vision/explain
https://domain/mcp/vision/images/ -> 127.0.0.1:8003/mcp/vision/images/
wss://domain/xiaozhi/v1/         -> 127.0.0.1:8000/xiaozhi/v1/
wss://domain/xiaozhi/audio-preview/ -> 127.0.0.1:8000/xiaozhi/audio-preview/
```

Set these values in `~/xiaozhi-data/.env` after HTTPS is available:

```bash
XIAOZHI_PUBLIC_DOMAIN=yh.wejoy.link
XIAOZHI_PUBLIC_BASE_URL=https://yh.wejoy.link
XIAOZHI_CORS_ORIGINS=https://yh.wejoy.link
```

Then run `~/xiaozhi-upgrade`. The install output will include both local test URLs and
public HTTPS/WSS URLs when the public variables are set.

For the WeChat mini program, add `https://yh.wejoy.link` to the mini program
request legal domain in WeChat Official Accounts Platform. The mini program
production build already uses `https://yh.wejoy.link/xiaozhi` as the API base
URL.

For devices, update the manager console parameter dictionary to:

```text
server.websocket      wss://yh.wejoy.link/xiaozhi/v1/
server.ota            https://yh.wejoy.link/xiaozhi/ota/
server.vision_explain https://yh.wejoy.link/mcp/vision/explain
```

Only ports 80 and 443 need to be public after the reverse proxy is ready. Ports
8000, 8002, 8003, MySQL, and Redis should be restricted by the host firewall or
security group.

## Container startup order

Compose enforces the following dependency chain:

```
MySQL (healthy) ──┐
                   ├──→ manager-api (port ready) ──→ manager-web ──→ Python server
Redis (healthy) ──┘
```

- `xiaozhi-esp32-manager-api` waits for MySQL and Redis health checks, then
  runs Liquibase migrations during Spring Boot startup. Liquibase uses a
  dedicated JDBC connection with no socket read timeout; normal API queries
  retain the 30-second timeout.
- The manager API is healthy only after port 8003 accepts connections. A Java
  process that is still waiting for Liquibase is not considered ready.
- `xiaozhi-esp32-server-web` waits for the manager API health check, and the
  Python server waits for the web container, avoiding crash-restart loops while
  migrations are still running.
- `./install` waits for the manager API health check for 900 seconds by default.
  Override this with `XIAOZHI_MANAGER_API_STARTUP_TIMEOUT_SECONDS` for unusually
  large databases.
- After manager-api is ready, `./install` syncs `server.secret` into the Python
  private config and waits for `xiaozhi-esp32-server` to stay running.

## Redis persistence

Redis runs with AOF (`--appendonly yes`) and stores its data at
`~/xiaozhi-data/redis/data`. User sessions survive container restarts and
`~/xiaozhi-upgrade` runs.

## Server image profiles

The Python server image can be published in two profiles:

```text
lite     scripts/acr-build-push-lite    no local FunASR, no torch/funasr wheels
funasr   scripts/acr-build-push-funasr  local FunASR/SenseVoiceSmall enabled
```

Use the lite profile when ASR is provided by remote vendors or a separate
FunASR server. Use the FunASR profile only when the selected ASR adapter is
`fun_local`. Switching profiles means publishing a new `xiaozhi-server` image
and running `~/xiaozhi-upgrade`; Docker Compose itself does not need a different file.

## Resource limits

The production compose file includes runtime limits for a 2 CPU / 4 GB server
with CPU FunASR enabled:

```text
xiaozhi-esp32-server       1.00 CPU / 2048m
xiaozhi-esp32-manager-api  0.50 CPU / 896m
xiaozhi-esp32-server-web   0.20 CPU / 192m
xiaozhi-esp32-server-db    0.30 CPU / 768m
xiaozhi-esp32-server-redis 0.10 CPU / 192m
```

The limits are applied at Docker Compose runtime, not baked into the images.
`memswap_limit` defaults to the same value as `mem_limit` to avoid swap storms
on small hosts. FunASR/SenseVoiceSmall runs in the Python container only when
the FunASR profile image is published and the selected ASR adapter is
`fun_local`; it needs about 2 GB of memory for reliable startup on CPU-only
hosts. Lite profile deployments can lower the Python memory limit after
testing their selected remote ASR path.

These five limits add up to 4 GB. A 4 GB host therefore has no worst-case
headroom for the kernel and Docker; use a larger host for sustained FunASR
load, or lower the limits after measuring a lite-profile deployment. The
manager API explicitly runs with the `prod` Spring profile so its connection
pools and Tomcat thread limit stay within this budget.

Override the `XIAOZHI_*_CPUS`, `XIAOZHI_*_MEM_LIMIT`,
`XIAOZHI_FUNASR_THREADS`, `XIAOZHI_MYSQL_INNODB_BUFFER_POOL_SIZE`,
`XIAOZHI_REDIS_MAXMEMORY`, or `XIAOZHI_MANAGER_API_JAVA_TOOL_OPTIONS` values in
`~/xiaozhi-data/.env` when the server size changes. Existing `.env` values
are preserved during upgrade; if an older deployment still has
`XIAOZHI_SERVER_MEM_LIMIT=768m`, raise it to at least `2048m` before enabling
FunASR.

`install` also generates `XIAOZHI_CREDENTIAL_ENCRYPTION_KEY` for encrypted
agent-scoped third-party credentials. Upgrades preserve this value. Do not
rotate it until stored credentials have been re-encrypted with the new key.

## Pull cache behavior

Production hosts do not build images and do not install Python, Java, Node.js,
or FunASR dependencies. `install` and `upgrade` only pull already published ACR
images and restart Docker Compose services.

Docker reuses local image layers automatically. Lite and FunASR builds use
different dependency hashes, so switching profiles pulls the matching base
layers once. If the selected profile dependencies and SenseVoiceSmall model
layer are unchanged, a later upgrade usually pulls only the new application
layers for the selected Git version tag.

Build-side cache controls stay on the build machine:

```text
BUILD_BASE=auto   reuse common/lite/funasr server-base layers when dependency inputs match
BUILD_BASE=false  skip server-base checks for Java/Web-only releases
BUILD_BASE=true   rebuild server-base after Python dependency changes
```

The release script publishes separate content-cache tags for the management
stack: `xiaozhi-manager-api:api-<content-hash>`,
`xiaozhi-manager-web:web-<content-hash>`. A Vue-only change no longer
rebuilds or re-uploads the Java API image; a Java-only change no longer
rebuilds the Vue image.

## Internal service URLs

`xiaozhi-esp32-server-web` calls the Python benchmark HTTP API through the
Docker service name by default:

```text
XIAOZHI_SERVER_HTTP_BASE_URL=http://xiaozhi-esp32-server:8003
```

Do not set this to `127.0.0.1` inside Docker. In the manager-api container,
`127.0.0.1` means the Java/Web container itself, not the Python server
container.

The public HTTPS domain is configured separately with `XIAOZHI_PUBLIC_DOMAIN`,
`XIAOZHI_PUBLIC_BASE_URL`, and `XIAOZHI_CORS_ORIGINS`. Do not replace
`XIAOZHI_SERVER_HTTP_BASE_URL` with `https://yh.wejoy.link`; that value is for
manager-api calling the Python container inside Docker.

#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_SERVER_IMAGE_REPOSITORY="crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-server"
DEFAULT_WEB_IMAGE_REPOSITORY="crpi-jxcli7cptlgilqfx.cn-hangzhou.personal.cr.aliyuncs.com/yihuixiaozhi/xiaozhi-web"
if [[ -z "${INSTALL_DIR:-}" ]]; then
  if [[ -d /opt/xiaozhi-server && ! -d /opt/xiaozhi-data ]]; then
    INSTALL_DIR="/opt/xiaozhi-server"
  else
    INSTALL_DIR="/opt/xiaozhi-data"
  fi
fi

read_existing_env() {
  local key="$1"
  local env_file="$INSTALL_DIR/.env"
  [[ -f "$env_file" ]] || return 0
  awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$env_file"
}

SERVER_IMAGE_REPOSITORY="${SERVER_IMAGE_REPOSITORY:-$(read_existing_env XIAOZHI_SERVER_IMAGE_REPOSITORY)}"
SERVER_IMAGE_REPOSITORY="${SERVER_IMAGE_REPOSITORY:-$DEFAULT_SERVER_IMAGE_REPOSITORY}"
WEB_IMAGE_REPOSITORY="${WEB_IMAGE_REPOSITORY:-$(read_existing_env XIAOZHI_WEB_IMAGE_REPOSITORY)}"
WEB_IMAGE_REPOSITORY="${WEB_IMAGE_REPOSITORY:-$DEFAULT_WEB_IMAGE_REPOSITORY}"
DEPLOY_MODE="${DEPLOY_MODE:-$(read_existing_env XIAOZHI_DEPLOY_MODE)}"
DEPLOY_MODE="${DEPLOY_MODE:-pull}"
MYSQL_IMAGE="${MYSQL_IMAGE:-$(read_existing_env XIAOZHI_MYSQL_IMAGE)}"
MYSQL_IMAGE="${MYSQL_IMAGE:-mysql:latest}"
REDIS_IMAGE="${REDIS_IMAGE:-$(read_existing_env XIAOZHI_REDIS_IMAGE)}"
REDIS_IMAGE="${REDIS_IMAGE:-redis:8.0}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-$(read_existing_env XIAOZHI_MYSQL_ROOT_PASSWORD)}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-123456}"

if [[ "$DEPLOY_MODE" != "pull" ]]; then
  echo "Only DEPLOY_MODE=pull is supported for production deployment." >&2
  exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is not installed. Install Docker first, then rerun this script." >&2
  exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
  echo "Docker Compose plugin is not available. Install docker-compose-plugin first." >&2
  exit 1
fi

mkdir -p \
  "$INSTALL_DIR/data" \
  "$INSTALL_DIR/mysql/data" \
  "$INSTALL_DIR/uploadfile"

echo "Pulling Docker images from configured registries"
docker pull "$SERVER_IMAGE_REPOSITORY:latest"
docker pull "$WEB_IMAGE_REPOSITORY:latest"
docker pull "$MYSQL_IMAGE"
docker pull "$REDIS_IMAGE"

cp "$ROOT_DIR/main/xiaozhi-server/docker-compose_all.yml" "$INSTALL_DIR/docker-compose_all.yml"

if [[ ! -f "$INSTALL_DIR/data/.config.yaml" ]]; then
  cp "$ROOT_DIR/main/xiaozhi-server/config_from_api.yaml" "$INSTALL_DIR/data/.config.yaml"
  sed -i 's#url: http://127.0.0.1:8002/xiaozhi#url: http://xiaozhi-esp32-server-web:8002/xiaozhi#' "$INSTALL_DIR/data/.config.yaml"
fi

cat > "$INSTALL_DIR/.env" <<EOF
XIAOZHI_DEPLOY_MODE=$DEPLOY_MODE
XIAOZHI_SERVER_IMAGE_REPOSITORY=$SERVER_IMAGE_REPOSITORY
XIAOZHI_WEB_IMAGE_REPOSITORY=$WEB_IMAGE_REPOSITORY
XIAOZHI_MYSQL_IMAGE=$MYSQL_IMAGE
XIAOZHI_REDIS_IMAGE=$REDIS_IMAGE
XIAOZHI_MYSQL_ROOT_PASSWORD=$MYSQL_ROOT_PASSWORD
EOF

echo "Starting Docker services from $INSTALL_DIR"
docker compose -f "$INSTALL_DIR/docker-compose_all.yml" --env-file "$INSTALL_DIR/.env" up -d

LOCAL_IP="$(hostname -I | awk '{print $1}')"
cat <<EOF
Docker services submitted.

manager-web/api:  http://$LOCAL_IP:8002
websocket:        ws://$LOCAL_IP:8000/xiaozhi/v1/
vision API:       http://$LOCAL_IP:8003/mcp/vision/explain

This deployment uses Docker images:

  $SERVER_IMAGE_REPOSITORY:latest
  $WEB_IMAGE_REPOSITORY:latest
  $MYSQL_IMAGE
  $REDIS_IMAGE

After the first administrator account is registered, open the console parameter page,
copy server.secret, write it to:

  $INSTALL_DIR/data/.config.yaml

Then configure the required models in the console and restart the core service:

  docker restart xiaozhi-esp32-server
EOF

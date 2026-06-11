#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INSTALL_DIR="${INSTALL_DIR:-/opt/xiaozhi-server}"
IMAGE_REPOSITORY="${IMAGE_REPOSITORY:-10.144.92.32:3001/gaoruirui688/xiaozhi-esp32-server}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-123456}"
DOWNLOAD_MODEL="${DOWNLOAD_MODEL:-true}"

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
  "$INSTALL_DIR/models/SenseVoiceSmall" \
  "$INSTALL_DIR/mysql/data" \
  "$INSTALL_DIR/uploadfile"

cp "$ROOT_DIR/main/xiaozhi-server/docker-compose_all.yml" "$INSTALL_DIR/docker-compose_all.yml"

if [[ ! -f "$INSTALL_DIR/data/.config.yaml" ]]; then
  cp "$ROOT_DIR/main/xiaozhi-server/config_from_api.yaml" "$INSTALL_DIR/data/.config.yaml"
fi

cat > "$INSTALL_DIR/.env" <<EOF
XIAOZHI_IMAGE_REPOSITORY=$IMAGE_REPOSITORY
XIAOZHI_MYSQL_ROOT_PASSWORD=$MYSQL_ROOT_PASSWORD
EOF

MODEL_PATH="$INSTALL_DIR/models/SenseVoiceSmall/model.pt"
if [[ "$DOWNLOAD_MODEL" == "true" && ! -f "$MODEL_PATH" ]]; then
  echo "Downloading SenseVoiceSmall model"
  curl -fL https://modelscope.cn/models/iic/SenseVoiceSmall/resolve/master/model.pt -o "$MODEL_PATH"
fi

echo "Starting Docker services from $INSTALL_DIR"
docker compose -f "$INSTALL_DIR/docker-compose_all.yml" --env-file "$INSTALL_DIR/.env" up -d

LOCAL_IP="$(hostname -I | awk '{print $1}')"
cat <<EOF
Docker services submitted.

manager-web/api:  http://$LOCAL_IP:8002
websocket:        ws://$LOCAL_IP:8000/xiaozhi/v1/
vision API:       http://$LOCAL_IP:8003/mcp/vision/explain

After the first administrator account is registered, open the console parameter page,
copy server.secret, write it to:

  $INSTALL_DIR/data/.config.yaml

Then restart the core service:

  docker restart xiaozhi-esp32-server
EOF

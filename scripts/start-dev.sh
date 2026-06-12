#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

usage() {
  cat <<'USAGE'
Usage: scripts/start-dev.sh [options]

Deprecated compatibility wrapper. Use scripts/dev for new workflows.

Options:
  --with-xiaozhi   Also start main/xiaozhi-server
  --with-mobile    Also start main/manager-mobile
  --skip-infra     Do not try to start MySQL and Redis
  -h, --help       Show this help

Equivalent new commands:
  scripts/dev start api
  scripts/dev start web
  scripts/dev start server
  scripts/dev start mobile
  scripts/dev start all
USAGE
}

START_SERVER=false
START_MOBILE=false
SKIP_INFRA=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-xiaozhi)
      START_SERVER=true
      ;;
    --with-mobile)
      START_MOBILE=true
      ;;
    --skip-infra)
      SKIP_INFRA=true
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
  shift
done

targets=(api web)
if [[ "$START_SERVER" == true ]]; then
  targets+=(server)
fi
if [[ "$START_MOBILE" == true ]]; then
  targets+=(mobile)
fi

echo "scripts/start-dev.sh is deprecated; forwarding to scripts/dev." >&2
echo "Use scripts/dev directly for new local development workflows." >&2

if [[ "$SKIP_INFRA" == true ]]; then
  export DEV_SKIP_INFRA=1
fi

for target in "${targets[@]}"; do
  "$ROOT_DIR/scripts/dev" start "$target"
done

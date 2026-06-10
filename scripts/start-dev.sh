#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT_DIR/logs/dev"
RUN_DIR="$ROOT_DIR/.run"

START_XIAOZHI=false
START_MOBILE=false
SKIP_INFRA=false

usage() {
  cat <<'USAGE'
Usage: scripts/start-dev.sh [options]

Options:
  --with-xiaozhi   Also start main/xiaozhi-server with python app.py
  --with-mobile    Also start main/manager-mobile
  --skip-infra     Do not try to start MySQL and Redis
  -h, --help       Show this help

Default starts:
  MySQL, Redis, manager-api, manager-web
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-xiaozhi)
      START_XIAOZHI=true
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
      usage
      exit 1
      ;;
  esac
  shift
done

mkdir -p "$LOG_DIR" "$RUN_DIR"

log() {
  printf '[%s] %s\n' "$(date '+%F %T')" "$*"
}

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

port_is_listening() {
  local port="$1"
  ss -ltn 2>/dev/null | awk '{print $4}' | grep -Eq "[:.]${port}$"
}

start_system_service() {
  local service="$1"
  if command_exists systemctl; then
    if systemctl list-unit-files "$service.service" >/dev/null 2>&1; then
      sudo systemctl start "$service" || true
      return
    fi
  fi

  if command_exists service; then
    sudo service "$service" start || true
  fi
}

start_infra() {
  log "Starting infrastructure services"
  start_system_service mysql
  start_system_service redis-server

  if port_is_listening 3306; then
    log "MySQL is listening on 3306"
  else
    log "MySQL is not listening on 3306; check local database installation"
  fi

  if port_is_listening 6379; then
    log "Redis is listening on 6379"
  else
    log "Redis is not listening on 6379; check local Redis installation"
  fi
}

start_process() {
  local name="$1"
  local port="$2"
  local workdir="$3"
  shift 3
  local pid_file="$RUN_DIR/${name}.pid"
  local log_file="$LOG_DIR/${name}.log"

  if [[ -f "$pid_file" ]]; then
    local old_pid
    old_pid="$(cat "$pid_file" 2>/dev/null || true)"
    if [[ -n "$old_pid" ]] && kill -0 "$old_pid" >/dev/null 2>&1; then
      log "$name is already running with pid $old_pid"
      return
    fi
  fi

  if [[ -n "$port" ]] && port_is_listening "$port"; then
    log "$name port $port is already listening; skip starting a duplicate"
    return
  fi

  log "Starting $name"
  (
    cd "$workdir"
    nohup "$@" >"$log_file" 2>&1 &
    echo $! >"$pid_file"
  )
  log "$name started, pid $(cat "$pid_file"), log $log_file"
}

ensure_manager_web_deps() {
  if [[ ! -d "$ROOT_DIR/main/manager-web/node_modules" ]]; then
    log "Installing manager-web dependencies"
    (cd "$ROOT_DIR/main/manager-web" && npm install)
  fi
}

start_manager_api() {
  start_process \
    "manager-api" \
    "8002" \
    "$ROOT_DIR/main/manager-api" \
    mvn spring-boot:run -Dspring-boot.run.profiles=dev
}

start_manager_web() {
  ensure_manager_web_deps
  start_process \
    "manager-web" \
    "8001" \
    "$ROOT_DIR/main/manager-web" \
    npm run serve
}

start_xiaozhi_server() {
  local command_prefix=()
  if command_exists conda; then
    command_prefix=(conda run -n xiaozhi-esp32-server)
  fi

  start_process \
    "xiaozhi-server" \
    "8000" \
    "$ROOT_DIR/main/xiaozhi-server" \
    "${command_prefix[@]}" python app.py
}

start_manager_mobile() {
  if [[ ! -d "$ROOT_DIR/main/manager-mobile/node_modules" ]]; then
    log "Installing manager-mobile dependencies"
    (cd "$ROOT_DIR/main/manager-mobile" && npm install)
  fi

  start_process \
    "manager-mobile" \
    "" \
    "$ROOT_DIR/main/manager-mobile" \
    npm run dev
}

main() {
  log "Project root: $ROOT_DIR"

  if [[ "$SKIP_INFRA" == false ]]; then
    start_infra
  fi

  start_manager_api
  start_manager_web

  if [[ "$START_XIAOZHI" == true ]]; then
    start_xiaozhi_server
  fi

  if [[ "$START_MOBILE" == true ]]; then
    start_manager_mobile
  fi

  log "Startup commands submitted"
  log "manager-web:    http://127.0.0.1:8001"
  log "manager-api:    http://127.0.0.1:8002/xiaozhi/doc.html"
  log "xiaozhi-server: ws://127.0.0.1:8000/xiaozhi/v1/  (only when --with-xiaozhi is used)"
}

main "$@"

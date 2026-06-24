#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_SERVER_IMAGE_REPOSITORY="xiaozhi-server-local"
DEFAULT_WEB_IMAGE_REPOSITORY="xiaozhi-web-local"
DEFAULT_SERVER_BASE_IMAGE_REPOSITORY="xiaozhi-server-base-local"

SERVER_IMAGE_REPOSITORY="${SERVER_IMAGE_REPOSITORY:-$DEFAULT_SERVER_IMAGE_REPOSITORY}"
WEB_IMAGE_REPOSITORY="${WEB_IMAGE_REPOSITORY:-$DEFAULT_WEB_IMAGE_REPOSITORY}"
SERVER_BASE_IMAGE_REPOSITORY="${SERVER_BASE_IMAGE_REPOSITORY:-$DEFAULT_SERVER_BASE_IMAGE_REPOSITORY}"
case "$(uname -m)" in
  x86_64) DEFAULT_PLATFORM="linux/amd64" ;;
  aarch64 | arm64) DEFAULT_PLATFORM="linux/arm64" ;;
  *) DEFAULT_PLATFORM="linux/amd64" ;;
esac
PLATFORMS="${PLATFORMS:-$DEFAULT_PLATFORM}"
PUSH="${PUSH:-false}"
BUILD_BASE="${BUILD_BASE:-auto}"
BUILD_ENGINE="${BUILD_ENGINE:-buildx}"
BUILDER_NAME="${BUILDER_NAME:-xiaozhi-builder}"
PYTHON_BASE_IMAGE="${PYTHON_BASE_IMAGE:-python:3.10-slim}"
NODE_BASE_IMAGE="${NODE_BASE_IMAGE:-node:18}"
MAVEN_BASE_IMAGE="${MAVEN_BASE_IMAGE:-maven:3.9.4-eclipse-temurin-21}"
WEB_RUNTIME_BASE_IMAGE="${WEB_RUNTIME_BASE_IMAGE:-bellsoft/liberica-runtime-container:jre-21-glibc}"
DEBIAN_MIRROR="${DEBIAN_MIRROR:-https://mirrors.aliyun.com/debian}"
DEBIAN_SECURITY_MIRROR="${DEBIAN_SECURITY_MIRROR:-https://mirrors.aliyun.com/debian-security}"
SERVER_REQUIREMENTS="${SERVER_REQUIREMENTS:-main/xiaozhi-server/requirements-docker.txt}"
BUILDKIT_REGISTRY_MIRROR="${BUILDKIT_REGISTRY_MIRROR:-}"
BUILDKIT_HTTP_PROXY="${BUILDKIT_HTTP_PROXY:-${HTTP_PROXY:-${http_proxy:-}}}"
BUILDKIT_HTTPS_PROXY="${BUILDKIT_HTTPS_PROXY:-${HTTPS_PROXY:-${https_proxy:-}}}"
BUILDKIT_NO_PROXY="${BUILDKIT_NO_PROXY:-${NO_PROXY:-${no_proxy:-}}}"
VERBOSE="${VERBOSE:-false}"
XIAOZHI_DOCKER_QUIET="${XIAOZHI_DOCKER_QUIET:-true}"
XIAOZHI_PROGRESS="${XIAOZHI_PROGRESS:-true}"
XIAOZHI_PROGRESS_INTERVAL="${XIAOZHI_PROGRESS_INTERVAL:-30}"
XIAOZHI_PROGRESS_ANIMATION_INTERVAL="${XIAOZHI_PROGRESS_ANIMATION_INTERVAL:-0.12}"
PARALLEL_IMAGE_BUILDS="${PARALLEL_IMAGE_BUILDS:-true}"
PRINT_BUILD_CONFIG="${PRINT_BUILD_CONFIG:-true}"
LOG_DIR="${LOG_DIR:-$(mktemp -d -t xiaozhi-docker-build.XXXXXX)}"
RECREATE_BUILDER=false

if [[ "$BUILDKIT_REGISTRY_MIRROR" == "none" || "$BUILDKIT_REGISTRY_MIRROR" == "false" ]]; then
  BUILDKIT_REGISTRY_MIRROR=""
  RECREATE_BUILDER=true
elif [[ -z "$BUILDKIT_REGISTRY_MIRROR" ]]; then
  if command -v docker >/dev/null 2>&1; then
    BUILDKIT_REGISTRY_MIRROR="$(docker info --format '{{range .RegistryConfig.Mirrors}}{{println .}}{{end}}' 2>/dev/null | sed 's#/*$##' || true)"
  fi
fi

if command -v docker >/dev/null 2>&1; then
  if [[ -z "$BUILDKIT_HTTP_PROXY" ]]; then
    BUILDKIT_HTTP_PROXY="$(docker info --format '{{.HTTPProxy}}' 2>/dev/null || true)"
  fi
  if [[ -z "$BUILDKIT_HTTPS_PROXY" ]]; then
    BUILDKIT_HTTPS_PROXY="$(docker info --format '{{.HTTPSProxy}}' 2>/dev/null || true)"
  fi
  if [[ -z "$BUILDKIT_NO_PROXY" ]]; then
    BUILDKIT_NO_PROXY="$(docker info --format '{{.NoProxy}}' 2>/dev/null || true)"
  fi
fi

BUILDX_DRIVER_OPTS=()
if [[ -n "$BUILDKIT_HTTP_PROXY" ]]; then
  BUILDX_DRIVER_OPTS+=(--driver-opt "env.HTTP_PROXY=$BUILDKIT_HTTP_PROXY")
  BUILDX_DRIVER_OPTS+=(--driver-opt "env.http_proxy=$BUILDKIT_HTTP_PROXY")
  RECREATE_BUILDER=true
fi
if [[ -n "$BUILDKIT_HTTPS_PROXY" ]]; then
  BUILDX_DRIVER_OPTS+=(--driver-opt "env.HTTPS_PROXY=$BUILDKIT_HTTPS_PROXY")
  BUILDX_DRIVER_OPTS+=(--driver-opt "env.https_proxy=$BUILDKIT_HTTPS_PROXY")
  RECREATE_BUILDER=true
fi

if [[ -z "${VERSION:-}" ]]; then
  if VERSION_TAG="$(git -C "$ROOT_DIR" describe --tags --exact-match 2>/dev/null)"; then
    VERSION="${VERSION_TAG#v}"
  elif VERSION_SHA="$(git -C "$ROOT_DIR" rev-parse --short=8 HEAD 2>/dev/null)"; then
    VERSION="$VERSION_SHA"
  else
    VERSION="local"
  fi
fi

if [[ "$PUSH" != "true" && "$PUSH" != "false" ]]; then
  echo "PUSH must be true or false" >&2
  exit 1
fi

if [[ "$BUILD_BASE" != "true" && "$BUILD_BASE" != "false" && "$BUILD_BASE" != "auto" ]]; then
  echo "BUILD_BASE must be true, false, or auto" >&2
  exit 1
fi

if [[ "$PUSH" == "false" && "$PLATFORMS" == *,* ]]; then
  echo "PUSH=false can only build one platform because Docker cannot load multi-platform images locally." >&2
  exit 1
fi

if [[ "$BUILD_ENGINE" != "buildx" && "$BUILD_ENGINE" != "docker" ]]; then
  echo "BUILD_ENGINE must be buildx or docker" >&2
  exit 1
fi

if [[ "$BUILD_ENGINE" == "docker" && "$PLATFORMS" == *,* ]]; then
  echo "BUILD_ENGINE=docker only supports one platform. Use BUILD_ENGINE=buildx for multi-platform builds." >&2
  exit 1
fi

if [[ "$PARALLEL_IMAGE_BUILDS" != "true" && "$PARALLEL_IMAGE_BUILDS" != "false" ]]; then
  echo "PARALLEL_IMAGE_BUILDS must be true or false" >&2
  exit 1
fi

if [[ "$XIAOZHI_PROGRESS" != "true" && "$XIAOZHI_PROGRESS" != "false" ]]; then
  echo "XIAOZHI_PROGRESS must be true or false" >&2
  exit 1
fi

if [[ ! "$XIAOZHI_PROGRESS_INTERVAL" =~ ^[0-9]+$ || "$XIAOZHI_PROGRESS_INTERVAL" -le 0 ]]; then
  echo "XIAOZHI_PROGRESS_INTERVAL must be a positive integer" >&2
  exit 1
fi

if [[ ! "$XIAOZHI_PROGRESS_ANIMATION_INTERVAL" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
  echo "XIAOZHI_PROGRESS_ANIMATION_INTERVAL must be a positive number" >&2
  exit 1
fi

create_builder() {
  if [[ -n "$BUILDKIT_REGISTRY_MIRROR" ]]; then
    BUILDKITD_CONFIG="$(mktemp)"
    trap 'rm -f "$BUILDKITD_CONFIG"' EXIT
    MIRRORS=""
    while IFS= read -r mirror; do
      [[ -n "$mirror" ]] || continue
      if [[ -n "$MIRRORS" ]]; then
        MIRRORS+=", "
      fi
      MIRRORS+="\"$mirror\""
    done <<<"$BUILDKIT_REGISTRY_MIRROR"
    {
      echo 'debug = false'
      echo
      echo '[registry."docker.io"]'
      echo "  mirrors = [$MIRRORS]"
    } >"$BUILDKITD_CONFIG"
    docker buildx create --name "$BUILDER_NAME" --use --bootstrap --driver docker-container "${BUILDX_DRIVER_OPTS[@]}" --buildkitd-config "$BUILDKITD_CONFIG" >/dev/null
  else
    docker buildx create --name "$BUILDER_NAME" --use --driver docker-container "${BUILDX_DRIVER_OPTS[@]}" >/dev/null
  fi
}

if [[ "$BUILD_ENGINE" == "buildx" ]]; then
  if ! docker buildx inspect "$BUILDER_NAME" >/dev/null 2>&1; then
    create_builder
  else
    if [[ "$RECREATE_BUILDER" == "true" || -n "$BUILDKIT_REGISTRY_MIRROR" ]]; then
      docker buildx rm "$BUILDER_NAME" >/dev/null
      create_builder
    else
      docker buildx use "$BUILDER_NAME" >/dev/null
    fi
  fi
fi

OUTPUT_FLAG=(--push)
if [[ "$PUSH" == "false" ]]; then
  OUTPUT_FLAG=(--load)
fi

is_quiet() {
  [[ "$VERBOSE" != "true" && "$XIAOZHI_DOCKER_QUIET" == "true" ]]
}

progress_enabled() {
  [[ "$XIAOZHI_PROGRESS" == "true" && "$XIAOZHI_PROGRESS_INTERVAL" =~ ^[0-9]+$ && "$XIAOZHI_PROGRESS_INTERVAL" -gt 0 ]]
}

format_duration() {
  local seconds="$1"
  printf '%02d:%02d' "$((seconds / 60))" "$((seconds % 60))"
}

supports_unicode() {
  local charset="${LC_ALL:-${LC_CTYPE:-${LANG:-}}}"
  [[ "$charset" == *UTF-8* || "$charset" == *utf8* || "$charset" == *utf-8* ]]
}

short_image_name() {
  local image="$1"
  printf '%s' "${image##*/}"
}

progress_label() {
  local label="$1"
  if [[ "${#label}" -gt 30 ]]; then
    printf '%s...' "${label:0:27}"
  else
    printf '%s' "$label"
  fi
}

progress_frames_unicode() {
  printf '%s\n' \
    '╺━━━━━━━━━━━━━━' '━╺━━━━━━━━━━━━━' '━━╺━━━━━━━━━━━━' '━━━╺━━━━━━━━━━━' \
    '━━━━╺━━━━━━━━━━' '━━━━━╺━━━━━━━━━' '━━━━━━╺━━━━━━━━' '━━━━━━━╺━━━━━━━' \
    '━━━━━━━━╺━━━━━━' '━━━━━━━━━╺━━━━━' '━━━━━━━━━━╺━━━━' '━━━━━━━━━━━╺━━━' \
    '━━━━━━━━━━━━╺━━' '━━━━━━━━━━━━━╺━' '━━━━━━━━━━━━━━╸' '━━━━━━━━━━━━━╸━' \
    '━━━━━━━━━━━━╸━━' '━━━━━━━━━━━╸━━━' '━━━━━━━━━━╸━━━━' '━━━━━━━━━╸━━━━━' \
    '━━━━━━━━╸━━━━━━' '━━━━━━━╸━━━━━━━' '━━━━━━╸━━━━━━━━' '━━━━━╸━━━━━━━━━' \
    '━━━━╸━━━━━━━━━━' '━━━╸━━━━━━━━━━━' '━━╸━━━━━━━━━━━━' '━╸━━━━━━━━━━━━━'
}

progress_frames_ascii() {
  printf '%s\n' \
    '>..............' '.>.............' '..>............' '...>...........' \
    '....>..........' '.....>.........' '......>........' '.......>.......' \
    '........>......' '.........>.....' '..........>....' '...........>...' \
    '............>..' '.............>.' '..............>' '.............<.' \
    '............<..' '...........<...' '..........<....' '.........<.....' \
    '........<......' '.......<.......' '......<........' '.....<.........' \
    '....<..........' '...<...........' '..<............' '.<.............'
}

restore_progress_line() {
  printf '\r\033[2K\033[?25h'
}

watch_background_task() {
  local pid="$1"
  local label="$2"
  local log_file="$3"
  local display_label
  local start_time
  local now
  local elapsed
  local elapsed_text
  local exit_code
  start_time="$(date +%s)"
  display_label="$(progress_label "$label")"

  if ! progress_enabled; then
    set +e
    wait "$pid"
    exit_code=$?
    set -e
    elapsed_text="$(format_duration "$(($(date +%s) - start_time))")"
    if [[ "$exit_code" -eq 0 ]]; then
      echo "  OK ($elapsed_text)"
    else
      echo "  FAILED after $elapsed_text. Full log: $log_file" >&2
    fi
    return "$exit_code"
  fi

  local done_icon="OK"
  local fail_icon="FAIL"
  local green=$'\033[32m'
  local red=$'\033[31m'
  local cyan=$'\033[36m'
  local dim=$'\033[2m'
  local reset=$'\033[0m'

  if [[ ! -t 1 ]]; then
    printf '• %s started  log: %s\n' "$display_label" "$log_file"
    trap 'kill "$pid" 2>/dev/null || true; exit 130' INT
    trap 'kill "$pid" 2>/dev/null || true; exit 143' TERM

    while kill -0 "$pid" 2>/dev/null; do
      sleep "$XIAOZHI_PROGRESS_INTERVAL"
      if kill -0 "$pid" 2>/dev/null; then
        now="$(date +%s)"
        elapsed=$((now - start_time))
        elapsed_text="$(format_duration "$elapsed")"
        printf '• %s still running  elapsed: %s  log: %s\n' "$display_label" "$elapsed_text" "$log_file"
      fi
    done

    set +e
    wait "$pid"
    exit_code=$?
    set -e
    elapsed_text="$(format_duration "$(($(date +%s) - start_time))")"

    if supports_unicode; then
      done_icon="✓"
      fail_icon="✗"
    fi

    if [[ "$exit_code" -eq 0 ]]; then
      printf '%s %s completed  elapsed: %s\n' "$done_icon" "$display_label" "$elapsed_text"
    else
      printf '%s %s failed  elapsed: %s  log: %s\n' "$fail_icon" "$display_label" "$elapsed_text" "$log_file" >&2
    fi

    trap - INT TERM
    return "$exit_code"
  fi

  local -a spinner
  local -a progress_frames
  if supports_unicode; then
    spinner=( '⠋' '⠙' '⠹' '⠸' '⠼' '⠴' '⠦' '⠧' '⠇' '⠏' )
    mapfile -t progress_frames < <(progress_frames_unicode)
    done_icon="✔"
    fail_icon="✖"
  else
    spinner=( '|' '/' '-' '\\' )
    mapfile -t progress_frames < <(progress_frames_ascii)
  fi

  local spinner_index=0
  local progress_index=0

  printf '\033[?25l'
  trap 'restore_progress_line; kill "$pid" 2>/dev/null || true; exit 130' INT
  trap 'restore_progress_line; kill "$pid" 2>/dev/null || true; exit 143' TERM
  trap 'restore_progress_line' EXIT

  while kill -0 "$pid" 2>/dev/null; do
    now="$(date +%s)"
    elapsed=$((now - start_time))
    elapsed_text="$(format_duration "$elapsed")"

    printf '\r\033[2K%s%s%s  %-30s %s%s%s   %s' \
      "$cyan" "${spinner[$spinner_index]}" "$reset" \
      "$display_label" \
      "$dim" "${progress_frames[$progress_index]}" "$reset" \
      "$elapsed_text"

    spinner_index=$(((spinner_index + 1) % ${#spinner[@]}))
    progress_index=$(((progress_index + 1) % ${#progress_frames[@]}))
    sleep "$XIAOZHI_PROGRESS_ANIMATION_INTERVAL"
  done

  set +e
  wait "$pid"
  exit_code=$?
  set -e
  elapsed_text="$(format_duration "$(($(date +%s) - start_time))")"

  printf '\r\033[2K'
  if [[ "$exit_code" -eq 0 ]]; then
    printf '%s%s%s  %-30s %scompleted%s   %s\n' \
      "$green" "$done_icon" "$reset" "$display_label" "$green" "$reset" "$elapsed_text"
  else
    printf '%s%s%s  %-30s %sfailed%s      %s\n' \
      "$red" "$fail_icon" "$reset" "$display_label" "$red" "$reset" "$elapsed_text" >&2
    printf '   %sLog:%s %s\n' "$dim" "$reset" "$log_file" >&2
  fi

  printf '\033[?25h'
  trap - EXIT INT TERM
  return "$exit_code"
}

run_docker_step() {
  local label="$1"
  shift

  if ! is_quiet; then
    echo "$label"
    "$@"
    return
  fi

  local log_file="$LOG_DIR/$(printf '%s' "$label" | tr -cs '[:alnum:]_.-' '_' | sed 's/^_*//; s/_*$//').log"
  local cmd_pid

  if ! progress_enabled; then
    echo "$label"
  fi

  "$@" >"$log_file" 2>&1 &
  cmd_pid=$!
  watch_background_task "$cmd_pid" "$label" "$log_file"
}

build_image() {
  local label="$1"
  local dockerfile="$2"
  shift 2
  if [[ "$BUILD_ENGINE" == "buildx" ]]; then
    run_docker_step "$label" docker buildx build \
      --platform "$PLATFORMS" \
      --file "$ROOT_DIR/$dockerfile" \
      "${OUTPUT_FLAG[@]}" \
      "$@" \
      "$ROOT_DIR"
  else
    run_docker_step "$label" docker build \
      --platform "$PLATFORMS" \
      --file "$ROOT_DIR/$dockerfile" \
      "$@" \
      "$ROOT_DIR"
  fi
}

push_image() {
  [[ "$BUILD_ENGINE" == "docker" && "$PUSH" == "true" ]] || return 0
  local image="$1"
  local label="Push $(short_image_name "$image")"
  if is_quiet; then
    run_docker_step "$label" docker push --quiet "$image"
  else
    run_docker_step "$label" docker push "$image"
  fi
}

hash_server_base_inputs() {
  {
    printf 'PYTHON_BASE_IMAGE=%s\n' "$PYTHON_BASE_IMAGE"
    printf 'DEBIAN_MIRROR=%s\n' "$DEBIAN_MIRROR"
    printf 'DEBIAN_SECURITY_MIRROR=%s\n' "$DEBIAN_SECURITY_MIRROR"
    printf 'SERVER_REQUIREMENTS=%s\n' "$SERVER_REQUIREMENTS"
    sha256sum "$ROOT_DIR/Dockerfile-server-base" "$ROOT_DIR/$SERVER_REQUIREMENTS"
  } | sha256sum | cut -c1-12
}

SERVER_BASE_CACHE_TAG="${SERVER_BASE_CACHE_TAG:-base-$(hash_server_base_inputs)}"
SERVER_BASE_CACHE_IMAGE="$SERVER_BASE_IMAGE_REPOSITORY:$SERVER_BASE_CACHE_TAG"

image_available_locally_or_remote() {
  local image="$1"
  docker image inspect "$image" >/dev/null 2>&1 && return 0
  docker pull --quiet "$image" >/dev/null 2>&1
}

if [[ "$PRINT_BUILD_CONFIG" != "false" ]]; then
  echo "Image repositories:"
  echo "  server:      $SERVER_IMAGE_REPOSITORY"
  echo "  web:         $WEB_IMAGE_REPOSITORY"
  echo "  server-base: $SERVER_BASE_IMAGE_REPOSITORY"
  echo "Version: $VERSION"
  echo "Platforms: $PLATFORMS"
  echo "Push: $PUSH"
  echo "Build engine: $BUILD_ENGINE"
  echo "Python base image: $PYTHON_BASE_IMAGE"
  echo "Node base image: $NODE_BASE_IMAGE"
  echo "Maven base image: $MAVEN_BASE_IMAGE"
  echo "Web runtime base image: $WEB_RUNTIME_BASE_IMAGE"
  echo "Debian mirror: $DEBIAN_MIRROR"
  echo "Debian security mirror: $DEBIAN_SECURITY_MIRROR"
  echo "Server requirements: $SERVER_REQUIREMENTS"
  echo "Server base cache image: $SERVER_BASE_CACHE_IMAGE"
  echo "Parallel image builds: $PARALLEL_IMAGE_BUILDS"
  if [[ -n "$BUILDKIT_REGISTRY_MIRROR" ]]; then
    echo "BuildKit registry mirror:"
    while IFS= read -r mirror; do
      [[ -n "$mirror" ]] || continue
      echo "  $mirror"
    done <<<"$BUILDKIT_REGISTRY_MIRROR"
  else
    echo "BuildKit registry mirror: disabled"
  fi
  if [[ -n "$BUILDKIT_HTTP_PROXY" || -n "$BUILDKIT_HTTPS_PROXY" ]]; then
    echo "BuildKit proxy: enabled"
  else
    echo "BuildKit proxy: disabled"
  fi
fi
if is_quiet; then
  echo "Docker detail logs: $LOG_DIR"
  echo "Set VERBOSE=true to show raw Docker build and push output."
fi

build_server_pipeline() {
  local server_base_image="${SERVER_BASE_IMAGE:-}"

  if [[ -n "$server_base_image" ]]; then
    echo "Use explicit server base image: $server_base_image"
  elif [[ "$BUILD_BASE" == "false" ]]; then
    server_base_image="$SERVER_BASE_IMAGE_REPOSITORY:latest"
    echo "Use latest server base image: $server_base_image"
  elif [[ "$BUILD_BASE" == "auto" ]] && image_available_locally_or_remote "$SERVER_BASE_CACHE_IMAGE"; then
    server_base_image="$SERVER_BASE_CACHE_IMAGE"
    echo "Use cached server base image: $server_base_image"
  elif [[ "$BUILD_BASE" == "auto" ]] && image_available_locally_or_remote "$SERVER_BASE_IMAGE_REPOSITORY:latest"; then
    echo "Use latest server base image as cache seed: $SERVER_BASE_IMAGE_REPOSITORY:latest"
    if [[ "$BUILD_ENGINE" == "docker" ]]; then
      docker tag "$SERVER_BASE_IMAGE_REPOSITORY:latest" "$SERVER_BASE_CACHE_IMAGE"
      push_image "$SERVER_BASE_CACHE_IMAGE"
      server_base_image="$SERVER_BASE_CACHE_IMAGE"
    else
      server_base_image="$SERVER_BASE_IMAGE_REPOSITORY:latest"
    fi
  else
    build_image "Build server base image" Dockerfile-server-base \
      --build-arg "PYTHON_BASE_IMAGE=$PYTHON_BASE_IMAGE" \
      --build-arg "DEBIAN_MIRROR=$DEBIAN_MIRROR" \
      --build-arg "DEBIAN_SECURITY_MIRROR=$DEBIAN_SECURITY_MIRROR" \
      --build-arg "SERVER_REQUIREMENTS=$SERVER_REQUIREMENTS" \
      --tag "$SERVER_BASE_IMAGE_REPOSITORY:latest" \
      --tag "$SERVER_BASE_IMAGE_REPOSITORY:$VERSION" \
      --tag "$SERVER_BASE_CACHE_IMAGE"
    push_image "$SERVER_BASE_IMAGE_REPOSITORY:latest"
    push_image "$SERVER_BASE_IMAGE_REPOSITORY:$VERSION"
    push_image "$SERVER_BASE_CACHE_IMAGE"
    server_base_image="$SERVER_BASE_CACHE_IMAGE"
  fi

  build_image "Build xiaozhi-server image" Dockerfile-server \
    --build-arg "SERVER_BASE_IMAGE=$server_base_image" \
    --tag "$SERVER_IMAGE_REPOSITORY:latest" \
    --tag "$SERVER_IMAGE_REPOSITORY:$VERSION"
  push_image "$SERVER_IMAGE_REPOSITORY:latest"
  push_image "$SERVER_IMAGE_REPOSITORY:$VERSION"
}

build_web_pipeline() {
  build_image "Build manager web/api image" Dockerfile-web \
    --build-arg "NODE_BASE_IMAGE=$NODE_BASE_IMAGE" \
    --build-arg "MAVEN_BASE_IMAGE=$MAVEN_BASE_IMAGE" \
    --build-arg "WEB_RUNTIME_BASE_IMAGE=$WEB_RUNTIME_BASE_IMAGE" \
    --tag "$WEB_IMAGE_REPOSITORY:latest" \
    --tag "$WEB_IMAGE_REPOSITORY:$VERSION"
  push_image "$WEB_IMAGE_REPOSITORY:latest"
  push_image "$WEB_IMAGE_REPOSITORY:$VERSION"
}

wait_for_pipeline() {
  local label="$1"
  local pid="$2"

  if wait "$pid"; then
    echo "$label pipeline OK"
    return 0
  fi
  echo "$label pipeline FAILED" >&2
  return 1
}

if [[ "$PARALLEL_IMAGE_BUILDS" == "true" ]]; then
  build_server_pipeline &
  SERVER_PIPELINE_PID=$!
  build_web_pipeline &
  WEB_PIPELINE_PID=$!

  PIPELINE_STATUS=0
  wait_for_pipeline "Server" "$SERVER_PIPELINE_PID" || PIPELINE_STATUS=1
  wait_for_pipeline "Web/API" "$WEB_PIPELINE_PID" || PIPELINE_STATUS=1
  if [[ "$PIPELINE_STATUS" -ne 0 ]]; then
    exit "$PIPELINE_STATUS"
  fi
else
  build_server_pipeline
  build_web_pipeline
fi

echo "Done."

#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_SERVER_IMAGE_REPOSITORY="xiaozhi-server-local"
DEFAULT_MANAGER_API_IMAGE_REPOSITORY="xiaozhi-manager-api-local"
DEFAULT_MANAGER_WEB_IMAGE_REPOSITORY="xiaozhi-manager-web-local"
DEFAULT_MANAGER_ASSETS_IMAGE_REPOSITORY="xiaozhi-manager-assets-local"
DEFAULT_SERVER_COMMON_BASE_IMAGE_REPOSITORY="xiaozhi-server-base-common-local"

SERVER_IMAGE_REPOSITORY="${SERVER_IMAGE_REPOSITORY:-$DEFAULT_SERVER_IMAGE_REPOSITORY}"
MANAGER_API_IMAGE_REPOSITORY="${MANAGER_API_IMAGE_REPOSITORY:-$DEFAULT_MANAGER_API_IMAGE_REPOSITORY}"
MANAGER_WEB_IMAGE_REPOSITORY="${MANAGER_WEB_IMAGE_REPOSITORY:-$DEFAULT_MANAGER_WEB_IMAGE_REPOSITORY}"
MANAGER_ASSETS_IMAGE_REPOSITORY="${MANAGER_ASSETS_IMAGE_REPOSITORY:-$DEFAULT_MANAGER_ASSETS_IMAGE_REPOSITORY}"
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
IMAGE_TAG_SUFFIX="${IMAGE_TAG_SUFFIX:-}"
CHANNEL_TAG="${CHANNEL_TAG:-latest}"
PYTHON_BASE_IMAGE="${PYTHON_BASE_IMAGE:-docker.m.daocloud.io/library/python:3.10-slim}"
NODE_BASE_IMAGE="${NODE_BASE_IMAGE:-docker.m.daocloud.io/library/node:20}"
MAVEN_BASE_IMAGE="${MAVEN_BASE_IMAGE:-docker.m.daocloud.io/library/maven:3.9.4-eclipse-temurin-21}"
WEB_RUNTIME_BASE_IMAGE="${WEB_RUNTIME_BASE_IMAGE:-docker.m.daocloud.io/bellsoft/liberica-runtime-container:jre-21-glibc}"
API_RUNTIME_BASE_IMAGE="${API_RUNTIME_BASE_IMAGE:-$WEB_RUNTIME_BASE_IMAGE}"
MANAGER_WEB_RUNTIME_BASE_IMAGE="${MANAGER_WEB_RUNTIME_BASE_IMAGE:-docker.m.daocloud.io/library/alpine:3.20}"
ASSETS_RUNTIME_BASE_IMAGE="${ASSETS_RUNTIME_BASE_IMAGE:-docker.m.daocloud.io/library/alpine:3.20}"
DEBIAN_MIRROR="${DEBIAN_MIRROR:-https://mirrors.aliyun.com/debian}"
DEBIAN_SECURITY_MIRROR="${DEBIAN_SECURITY_MIRROR:-https://mirrors.aliyun.com/debian-security}"
SERVER_INSTALL_PROFILE="${SERVER_INSTALL_PROFILE:-lite}"
case "$SERVER_INSTALL_PROFILE" in
  standard | lite)
    SERVER_INSTALL_PROFILE="lite"
    SERVER_REQUIREMENTS="${SERVER_REQUIREMENTS:-main/xiaozhi-server/requirements-docker.txt}"
    INSTALL_FUNASR_MODEL="${INSTALL_FUNASR_MODEL:-false}"
    ;;
  funasr)
    SERVER_REQUIREMENTS="${SERVER_REQUIREMENTS:-main/xiaozhi-server/requirements-docker.txt}"
    SERVER_REQUIREMENTS_FUNASR="${SERVER_REQUIREMENTS_FUNASR:-main/xiaozhi-server/requirements-docker-funasr-extra.txt}"
    INSTALL_FUNASR_MODEL="${INSTALL_FUNASR_MODEL:-true}"
    ;;
  *)
    echo "SERVER_INSTALL_PROFILE must be lite, standard, or funasr" >&2
    exit 1
    ;;
esac
SERVER_COMMON_BASE_IMAGE_REPOSITORY="${SERVER_COMMON_BASE_IMAGE_REPOSITORY:-$DEFAULT_SERVER_COMMON_BASE_IMAGE_REPOSITORY}"
SERVER_LITE_BASE_IMAGE_REPOSITORY="${SERVER_LITE_BASE_IMAGE_REPOSITORY:-xiaozhi-server-base-lite-local}"
SERVER_FUNASR_BASE_IMAGE_REPOSITORY="${SERVER_FUNASR_BASE_IMAGE_REPOSITORY:-xiaozhi-server-base-funasr-local}"
if [[ -z "${SERVER_BASE_IMAGE_REPOSITORY:-}" ]]; then
  if [[ "$SERVER_INSTALL_PROFILE" == "funasr" ]]; then
    SERVER_BASE_IMAGE_REPOSITORY="$SERVER_FUNASR_BASE_IMAGE_REPOSITORY"
  else
    SERVER_BASE_IMAGE_REPOSITORY="$SERVER_LITE_BASE_IMAGE_REPOSITORY"
  fi
fi
PIP_INDEX_URL="${PIP_INDEX_URL:-https://mirrors.aliyun.com/pypi/simple/}"
PIP_TRUSTED_HOST="${PIP_TRUSTED_HOST:-mirrors.aliyun.com}"
PYTORCH_INDEX_URL="${PYTORCH_INDEX_URL:-https://download.pytorch.org/whl/cpu}"
FUNASR_MODEL_URL="${FUNASR_MODEL_URL:-https://modelscope.cn/models/iic/SenseVoiceSmall/resolve/master/model.pt}"
FUNASR_MODEL_LOCAL_PATH="${FUNASR_MODEL_LOCAL_PATH:-$ROOT_DIR/main/xiaozhi-server/models/SenseVoiceSmall/model.pt}"
FUNASR_MODEL_CONTEXT="${FUNASR_MODEL_CONTEXT:-}"
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
SERVER_BASE_CACHE_VERSION="${SERVER_BASE_CACHE_VERSION:-v2}"
SERVER_BASE_SEED_LATEST="${SERVER_BASE_SEED_LATEST:-false}"
MANAGER_API_CACHE_VERSION="${MANAGER_API_CACHE_VERSION:-v1}"
MANAGER_WEB_CACHE_VERSION="${MANAGER_WEB_CACHE_VERSION:-v1}"
MANAGER_ASSETS_CACHE_VERSION="${MANAGER_ASSETS_CACHE_VERSION:-v1}"
RECREATE_BUILDER=false

if [[ -z "$FUNASR_MODEL_CONTEXT" ]]; then
  if [[ -s "$FUNASR_MODEL_LOCAL_PATH" ]]; then
    FUNASR_MODEL_CONTEXT="$(dirname "$FUNASR_MODEL_LOCAL_PATH")"
  else
    FUNASR_MODEL_CONTEXT="$LOG_DIR/empty-funasr-model"
    mkdir -p "$FUNASR_MODEL_CONTEXT"
  fi
fi

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
IMAGE_VERSION_TAG="${VERSION}${IMAGE_TAG_SUFFIX}"

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

if [[ "$INSTALL_FUNASR_MODEL" != "true" && "$INSTALL_FUNASR_MODEL" != "false" ]]; then
  echo "INSTALL_FUNASR_MODEL must be true or false" >&2
  exit 1
fi

if [[ "$SERVER_INSTALL_PROFILE" == "lite" && "$INSTALL_FUNASR_MODEL" == "true" ]]; then
  echo "SERVER_INSTALL_PROFILE=lite does not include local FunASR; use SERVER_INSTALL_PROFILE=funasr to install the model." >&2
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

if [[ "$SERVER_BASE_SEED_LATEST" != "true" && "$SERVER_BASE_SEED_LATEST" != "false" ]]; then
  echo "SERVER_BASE_SEED_LATEST must be true or false" >&2
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

push_local_image() {
  [[ "$PUSH" == "true" ]] || return 0
  local image="$1"
  local label="Push $(short_image_name "$image")"
  if is_quiet; then
    run_docker_step "$label" docker push --quiet "$image"
  else
    run_docker_step "$label" docker push "$image"
  fi
}

image_exists_remote() {
  local image="$1"
  docker manifest inspect "$image" >/dev/null 2>&1
}

remote_retag_image() {
  [[ "$PUSH" == "true" ]] || return 1
  local source="$1"
  local target="$2"
  local label="Retag $(short_image_name "$target")"

  image_exists_remote "$source" || return 1
  docker buildx version >/dev/null 2>&1 || return 1
  run_docker_step "$label" docker buildx imagetools create --tag "$target" "$source"
}

hash_common_base_inputs() {
  {
    printf 'SERVER_BASE_CACHE_VERSION=%s\n' "$SERVER_BASE_CACHE_VERSION"
    printf 'PYTHON_BASE_IMAGE=%s\n' "$PYTHON_BASE_IMAGE"
    printf 'DEBIAN_MIRROR=%s\n' "$DEBIAN_MIRROR"
    printf 'DEBIAN_SECURITY_MIRROR=%s\n' "$DEBIAN_SECURITY_MIRROR"
    sha256sum "$ROOT_DIR/Dockerfile-server-base"
  } | sha256sum | cut -c1-12
}

hash_lite_base_inputs() {
  {
    printf 'SERVER_BASE_CACHE_VERSION=%s\n' "$SERVER_BASE_CACHE_VERSION"
    printf 'SERVER_COMMON_BASE_CACHE_TAG=%s\n' "$SERVER_COMMON_BASE_CACHE_TAG"
    printf 'SERVER_REQUIREMENTS=%s\n' "$SERVER_REQUIREMENTS"
    printf 'PIP_INDEX_URL=%s\n' "$PIP_INDEX_URL"
    printf 'PIP_TRUSTED_HOST=%s\n' "$PIP_TRUSTED_HOST"
    sha256sum "$ROOT_DIR/Dockerfile-server-base-lite" "$ROOT_DIR/$SERVER_REQUIREMENTS"
  } | sha256sum | cut -c1-12
}

hash_funasr_base_inputs() {
  {
    printf 'SERVER_BASE_CACHE_VERSION=%s\n' "$SERVER_BASE_CACHE_VERSION"
    printf 'SERVER_LITE_BASE_CACHE_TAG=%s\n' "$SERVER_LITE_BASE_CACHE_TAG"
    printf 'SERVER_REQUIREMENTS_FUNASR=%s\n' "$SERVER_REQUIREMENTS_FUNASR"
    printf 'PYTORCH_INDEX_URL=%s\n' "$PYTORCH_INDEX_URL"
    sha256sum "$ROOT_DIR/Dockerfile-server-base-funasr" "$ROOT_DIR/$SERVER_REQUIREMENTS_FUNASR"
  } | sha256sum | cut -c1-12
}

hash_manager_api_inputs() {
  {
    printf 'MANAGER_API_CACHE_VERSION=%s\n' "$MANAGER_API_CACHE_VERSION"
    printf 'MAVEN_BASE_IMAGE=%s\n' "$MAVEN_BASE_IMAGE"
    printf 'API_RUNTIME_BASE_IMAGE=%s\n' "$API_RUNTIME_BASE_IMAGE"
    git -C "$ROOT_DIR" ls-files -z --cached --others --exclude-standard -- \
      Dockerfile-manager-api \
      Dockerfile-manager-api.dockerignore \
      docs/docker/start-api.sh \
      main/manager-api/pom.xml \
      main/manager-api/src \
      | sort -z \
      | (cd "$ROOT_DIR" && xargs -0 -r sha256sum)
  } | sha256sum | cut -c1-12
}

hash_manager_web_inputs() {
  {
    printf 'MANAGER_WEB_CACHE_VERSION=%s\n' "$MANAGER_WEB_CACHE_VERSION"
    printf 'NODE_BASE_IMAGE=%s\n' "$NODE_BASE_IMAGE"
    printf 'MANAGER_WEB_RUNTIME_BASE_IMAGE=%s\n' "$MANAGER_WEB_RUNTIME_BASE_IMAGE"
    git -C "$ROOT_DIR" ls-files -z --cached --others --exclude-standard -- \
      Dockerfile-manager-web \
      Dockerfile-manager-web.dockerignore \
      docs/docker/nginx-manager-web.conf \
      main/manager-web/.npmrc \
      main/manager-web/babel.config.js \
      main/manager-web/jsconfig.json \
      main/manager-web/package-lock.json \
      main/manager-web/package.json \
      main/manager-web/vue.config.js \
      main/manager-web/src \
      main/manager-web/public/favicon.ico \
      main/manager-web/public/index.html \
      main/manager-web/public/offline.html \
      main/manager-web/public/privacy-policy.html \
      main/manager-web/public/user-agreement.html \
      main/manager-web/public/generator/README.md \
      main/manager-web/public/generator/favicon.ico \
      main/manager-web/public/generator/index.html \
      main/manager-web/public/generator/assets \
      main/manager-web/public/generator/workers \
      | sort -z \
      | (cd "$ROOT_DIR" && xargs -0 -r sha256sum)
  } | sha256sum | cut -c1-12
}

hash_manager_assets_inputs() {
  {
    printf 'MANAGER_ASSETS_CACHE_VERSION=%s\n' "$MANAGER_ASSETS_CACHE_VERSION"
    printf 'ASSETS_RUNTIME_BASE_IMAGE=%s\n' "$ASSETS_RUNTIME_BASE_IMAGE"
    git -C "$ROOT_DIR" ls-files -z --cached --others --exclude-standard -- \
      Dockerfile-manager-assets \
      Dockerfile-manager-assets.dockerignore \
      docs/docker/nginx-manager-assets.conf \
      main/manager-web/public/generator/static \
      | sort -z \
      | (cd "$ROOT_DIR" && xargs -0 -r sha256sum)
  } | sha256sum | cut -c1-12
}

funasr_model_cache_key() {
  if [[ -s "$FUNASR_MODEL_CONTEXT/model.pt" ]]; then
    sha256sum "$FUNASR_MODEL_CONTEXT/model.pt" | cut -c1-12
    return
  fi
  printf '%s' "$FUNASR_MODEL_URL" | sha256sum | cut -c1-12
}

SERVER_COMMON_BASE_CACHE_TAG="${SERVER_COMMON_BASE_CACHE_TAG:-base-$(hash_common_base_inputs)}"
SERVER_COMMON_BASE_CACHE_IMAGE="$SERVER_COMMON_BASE_IMAGE_REPOSITORY:$SERVER_COMMON_BASE_CACHE_TAG"
SERVER_LITE_BASE_CACHE_TAG="${SERVER_LITE_BASE_CACHE_TAG:-base-$(hash_lite_base_inputs)}"
SERVER_LITE_BASE_CACHE_IMAGE="$SERVER_LITE_BASE_IMAGE_REPOSITORY:$SERVER_LITE_BASE_CACHE_TAG"
if [[ "$SERVER_INSTALL_PROFILE" == "funasr" ]]; then
  SERVER_BASE_CACHE_TAG="${SERVER_BASE_CACHE_TAG:-base-$(hash_funasr_base_inputs)}"
else
  SERVER_BASE_CACHE_TAG="${SERVER_BASE_CACHE_TAG:-$SERVER_LITE_BASE_CACHE_TAG}"
fi
SERVER_BASE_CACHE_IMAGE="$SERVER_BASE_IMAGE_REPOSITORY:$SERVER_BASE_CACHE_TAG"
FUNASR_MODEL_DIGEST="${FUNASR_MODEL_DIGEST:-$(funasr_model_cache_key)}"
MANAGER_API_CACHE_TAG="${MANAGER_API_CACHE_TAG:-api-$(hash_manager_api_inputs)}"
MANAGER_API_CACHE_IMAGE="$MANAGER_API_IMAGE_REPOSITORY:$MANAGER_API_CACHE_TAG"
MANAGER_WEB_CACHE_TAG="${MANAGER_WEB_CACHE_TAG:-web-$(hash_manager_web_inputs)}"
MANAGER_WEB_CACHE_IMAGE="$MANAGER_WEB_IMAGE_REPOSITORY:$MANAGER_WEB_CACHE_TAG"
MANAGER_ASSETS_CACHE_TAG="${MANAGER_ASSETS_CACHE_TAG:-assets-$(hash_manager_assets_inputs)}"
MANAGER_ASSETS_CACHE_IMAGE="$MANAGER_ASSETS_IMAGE_REPOSITORY:$MANAGER_ASSETS_CACHE_TAG"

image_available_locally_or_remote() {
  local image="$1"
  docker image inspect "$image" >/dev/null 2>&1 && return 0
  docker pull --quiet "$image" >/dev/null 2>&1
}

if [[ "$PRINT_BUILD_CONFIG" != "false" ]]; then
  echo "Image repositories:"
  echo "  server:      $SERVER_IMAGE_REPOSITORY"
  echo "  manager-api: $MANAGER_API_IMAGE_REPOSITORY"
  echo "  manager-web: $MANAGER_WEB_IMAGE_REPOSITORY"
  echo "  assets:      $MANAGER_ASSETS_IMAGE_REPOSITORY"
  echo "  base-common: $SERVER_COMMON_BASE_IMAGE_REPOSITORY"
  echo "  base-lite:   $SERVER_LITE_BASE_IMAGE_REPOSITORY"
  echo "  base-funasr: $SERVER_FUNASR_BASE_IMAGE_REPOSITORY"
  echo "Version: $VERSION"
  echo "Image version tag: $IMAGE_VERSION_TAG"
  echo "Channel tag: $CHANNEL_TAG"
  echo "Platforms: $PLATFORMS"
  echo "Push: $PUSH"
  echo "Build engine: $BUILD_ENGINE"
  echo "Python base image: $PYTHON_BASE_IMAGE"
  echo "Node base image: $NODE_BASE_IMAGE"
  echo "Maven base image: $MAVEN_BASE_IMAGE"
  echo "Web runtime base image: $WEB_RUNTIME_BASE_IMAGE"
  echo "API runtime base image: $API_RUNTIME_BASE_IMAGE"
  echo "Manager web runtime base image: $MANAGER_WEB_RUNTIME_BASE_IMAGE"
  echo "Assets runtime base image: $ASSETS_RUNTIME_BASE_IMAGE"
  echo "Debian mirror: $DEBIAN_MIRROR"
  echo "Debian security mirror: $DEBIAN_SECURITY_MIRROR"
  echo "Server install profile: $SERVER_INSTALL_PROFILE"
  echo "Server requirements: $SERVER_REQUIREMENTS"
  if [[ "$SERVER_INSTALL_PROFILE" == "funasr" ]]; then
    echo "Server FunASR requirements: $SERVER_REQUIREMENTS_FUNASR"
  fi
  echo "Server base cache version: $SERVER_BASE_CACHE_VERSION"
  echo "Server base seed latest: $SERVER_BASE_SEED_LATEST"
  echo "Manager API cache version: $MANAGER_API_CACHE_VERSION"
  echo "Manager Web cache version: $MANAGER_WEB_CACHE_VERSION"
  echo "Manager assets cache version: $MANAGER_ASSETS_CACHE_VERSION"
  echo "Install FunASR model: $INSTALL_FUNASR_MODEL"
  echo "FunASR model context: $FUNASR_MODEL_CONTEXT"
  echo "FunASR model URL: $FUNASR_MODEL_URL"
  echo "Common base cache image: $SERVER_COMMON_BASE_CACHE_IMAGE"
  echo "Lite base cache image: $SERVER_LITE_BASE_CACHE_IMAGE"
  echo "Selected base cache image: $SERVER_BASE_CACHE_IMAGE"
  echo "Manager API cache image: $MANAGER_API_CACHE_IMAGE"
  echo "Manager Web cache image: $MANAGER_WEB_CACHE_IMAGE"
  echo "Manager assets cache image: $MANAGER_ASSETS_CACHE_IMAGE"
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
  echo "Logs: $LOG_DIR  (VERBOSE=true for raw Docker output)"
fi

print_base_image_status() {
  local message="$1"
  local image="${2:-}"

  if is_quiet && [[ -n "$image" ]]; then
    echo "$message: $(short_image_name "$image")"
  elif [[ -n "$image" ]]; then
    echo "$message: $image"
  else
    echo "$message"
  fi
}

build_common_base() {
  if [[ "$BUILD_BASE" == "auto" ]] && image_available_locally_or_remote "$SERVER_COMMON_BASE_CACHE_IMAGE"; then
    print_base_image_status "Common base cached" "$SERVER_COMMON_BASE_CACHE_IMAGE"
    return
  fi

  build_image "Build common server base" Dockerfile-server-base \
    --build-arg "PYTHON_BASE_IMAGE=$PYTHON_BASE_IMAGE" \
    --build-arg "DEBIAN_MIRROR=$DEBIAN_MIRROR" \
    --build-arg "DEBIAN_SECURITY_MIRROR=$DEBIAN_SECURITY_MIRROR" \
    --build-arg "PIP_INDEX_URL=$PIP_INDEX_URL" \
    --build-arg "PIP_TRUSTED_HOST=$PIP_TRUSTED_HOST" \
    --tag "$SERVER_COMMON_BASE_IMAGE_REPOSITORY:latest" \
    --tag "$SERVER_COMMON_BASE_IMAGE_REPOSITORY:$VERSION" \
    --tag "$SERVER_COMMON_BASE_CACHE_IMAGE"
  push_image "$SERVER_COMMON_BASE_IMAGE_REPOSITORY:latest"
  push_image "$SERVER_COMMON_BASE_IMAGE_REPOSITORY:$VERSION"
  push_image "$SERVER_COMMON_BASE_CACHE_IMAGE"
}

build_lite_base() {
  build_common_base
  if [[ "$BUILD_BASE" == "auto" ]] && image_available_locally_or_remote "$SERVER_LITE_BASE_CACHE_IMAGE"; then
    print_base_image_status "Lite base cached" "$SERVER_LITE_BASE_CACHE_IMAGE"
    return
  fi

  build_image "Build lite server base" Dockerfile-server-base-lite \
    --build-arg "SERVER_COMMON_BASE_IMAGE=$SERVER_COMMON_BASE_CACHE_IMAGE" \
    --build-arg "SERVER_REQUIREMENTS=$SERVER_REQUIREMENTS" \
    --build-arg "PYTORCH_INDEX_URL=$PYTORCH_INDEX_URL" \
    --tag "$SERVER_LITE_BASE_IMAGE_REPOSITORY:latest" \
    --tag "$SERVER_LITE_BASE_IMAGE_REPOSITORY:$VERSION" \
    --tag "$SERVER_LITE_BASE_CACHE_IMAGE"
  push_image "$SERVER_LITE_BASE_IMAGE_REPOSITORY:latest"
  push_image "$SERVER_LITE_BASE_IMAGE_REPOSITORY:$VERSION"
  push_image "$SERVER_LITE_BASE_CACHE_IMAGE"
}

build_funasr_base() {
  build_lite_base
  if [[ "$BUILD_BASE" == "auto" ]] && image_available_locally_or_remote "$SERVER_BASE_CACHE_IMAGE"; then
    print_base_image_status "FunASR base cached" "$SERVER_BASE_CACHE_IMAGE"
    return
  fi

  build_image "Build FunASR server base" Dockerfile-server-base-funasr \
    --build-arg "SERVER_LITE_BASE_IMAGE=$SERVER_LITE_BASE_CACHE_IMAGE" \
    --build-arg "SERVER_REQUIREMENTS_FUNASR=$SERVER_REQUIREMENTS_FUNASR" \
    --build-arg "PYTORCH_INDEX_URL=$PYTORCH_INDEX_URL" \
    --tag "$SERVER_BASE_IMAGE_REPOSITORY:latest" \
    --tag "$SERVER_BASE_IMAGE_REPOSITORY:$VERSION" \
    --tag "$SERVER_BASE_CACHE_IMAGE"
  push_image "$SERVER_BASE_IMAGE_REPOSITORY:latest"
  push_image "$SERVER_BASE_IMAGE_REPOSITORY:$VERSION"
  push_image "$SERVER_BASE_CACHE_IMAGE"
}

build_server_pipeline() {
  local server_base_image="${SERVER_BASE_IMAGE:-}"

  if [[ -n "$server_base_image" ]]; then
    print_base_image_status "Base image explicit" "$server_base_image"
  elif [[ "$BUILD_BASE" == "false" ]]; then
    server_base_image="$SERVER_BASE_IMAGE_REPOSITORY:latest"
    print_base_image_status "Base image latest" "$server_base_image"
  elif [[ "$BUILD_BASE" == "auto" ]] && image_available_locally_or_remote "$SERVER_BASE_CACHE_IMAGE"; then
    server_base_image="$SERVER_BASE_CACHE_IMAGE"
    print_base_image_status "Base image cached" "$server_base_image"
  elif [[ "$BUILD_BASE" == "auto" && "$SERVER_BASE_SEED_LATEST" == "true" ]] && image_available_locally_or_remote "$SERVER_BASE_IMAGE_REPOSITORY:latest"; then
    print_base_image_status "Base image cache seed" "$SERVER_BASE_IMAGE_REPOSITORY:latest"
    if [[ "$BUILD_ENGINE" == "docker" ]]; then
      docker tag "$SERVER_BASE_IMAGE_REPOSITORY:latest" "$SERVER_BASE_CACHE_IMAGE"
      push_image "$SERVER_BASE_CACHE_IMAGE"
      server_base_image="$SERVER_BASE_CACHE_IMAGE"
    else
      server_base_image="$SERVER_BASE_IMAGE_REPOSITORY:latest"
    fi
  else
    if [[ "$SERVER_INSTALL_PROFILE" == "funasr" ]]; then
      build_funasr_base
    else
      build_lite_base
    fi
    server_base_image="$SERVER_BASE_CACHE_IMAGE"
  fi

  build_image "Build xiaozhi-server image" Dockerfile-server \
    --build-context "funasr_model=$FUNASR_MODEL_CONTEXT" \
    --build-arg "SERVER_BASE_IMAGE=$server_base_image" \
    --build-arg "INSTALL_FUNASR_MODEL=$INSTALL_FUNASR_MODEL" \
    --build-arg "FUNASR_MODEL_URL=$FUNASR_MODEL_URL" \
    --build-arg "FUNASR_MODEL_DIGEST=$FUNASR_MODEL_DIGEST" \
    --tag "$SERVER_IMAGE_REPOSITORY:$CHANNEL_TAG" \
    --tag "$SERVER_IMAGE_REPOSITORY:$IMAGE_VERSION_TAG"
  push_image "$SERVER_IMAGE_REPOSITORY:$CHANNEL_TAG"
  push_image "$SERVER_IMAGE_REPOSITORY:$IMAGE_VERSION_TAG"
}

retag_or_push_component() {
  local cache_image="$1"
  local repository="$2"

  if remote_retag_image "$cache_image" "$repository:$CHANNEL_TAG"; then
    remote_retag_image "$cache_image" "$repository:$IMAGE_VERSION_TAG"
    return
  fi

  docker tag "$cache_image" "$repository:$CHANNEL_TAG"
  docker tag "$cache_image" "$repository:$IMAGE_VERSION_TAG"
  push_local_image "$repository:$CHANNEL_TAG"
  push_local_image "$repository:$IMAGE_VERSION_TAG"
}

build_manager_api_pipeline() {
  if image_available_locally_or_remote "$MANAGER_API_CACHE_IMAGE"; then
    print_base_image_status "Manager API cached" "$MANAGER_API_CACHE_IMAGE"
    if [[ "$PUSH" == "true" ]] && ! image_exists_remote "$MANAGER_API_CACHE_IMAGE"; then
      push_local_image "$MANAGER_API_CACHE_IMAGE"
    fi
    retag_or_push_component "$MANAGER_API_CACHE_IMAGE" "$MANAGER_API_IMAGE_REPOSITORY"
    return
  fi

  build_image "Build manager API image" Dockerfile-manager-api \
    --build-arg "MAVEN_BASE_IMAGE=$MAVEN_BASE_IMAGE" \
    --build-arg "API_RUNTIME_BASE_IMAGE=$API_RUNTIME_BASE_IMAGE" \
    --tag "$MANAGER_API_IMAGE_REPOSITORY:$CHANNEL_TAG" \
    --tag "$MANAGER_API_IMAGE_REPOSITORY:$IMAGE_VERSION_TAG" \
    --tag "$MANAGER_API_CACHE_IMAGE"
  push_image "$MANAGER_API_CACHE_IMAGE"
  if ! remote_retag_image "$MANAGER_API_CACHE_IMAGE" "$MANAGER_API_IMAGE_REPOSITORY:$CHANNEL_TAG"; then
    push_image "$MANAGER_API_IMAGE_REPOSITORY:$CHANNEL_TAG"
  fi
  if ! remote_retag_image "$MANAGER_API_CACHE_IMAGE" "$MANAGER_API_IMAGE_REPOSITORY:$IMAGE_VERSION_TAG"; then
    push_image "$MANAGER_API_IMAGE_REPOSITORY:$IMAGE_VERSION_TAG"
  fi
}

build_manager_web_pipeline() {
  if image_available_locally_or_remote "$MANAGER_WEB_CACHE_IMAGE"; then
    print_base_image_status "Manager Web cached" "$MANAGER_WEB_CACHE_IMAGE"
    if [[ "$PUSH" == "true" ]] && ! image_exists_remote "$MANAGER_WEB_CACHE_IMAGE"; then
      push_local_image "$MANAGER_WEB_CACHE_IMAGE"
    fi
    retag_or_push_component "$MANAGER_WEB_CACHE_IMAGE" "$MANAGER_WEB_IMAGE_REPOSITORY"
    return
  fi

  build_image "Build manager Web image" Dockerfile-manager-web \
    --build-arg "NODE_BASE_IMAGE=$NODE_BASE_IMAGE" \
    --build-arg "WEB_RUNTIME_BASE_IMAGE=$MANAGER_WEB_RUNTIME_BASE_IMAGE" \
    --tag "$MANAGER_WEB_IMAGE_REPOSITORY:$CHANNEL_TAG" \
    --tag "$MANAGER_WEB_IMAGE_REPOSITORY:$IMAGE_VERSION_TAG" \
    --tag "$MANAGER_WEB_CACHE_IMAGE"
  push_image "$MANAGER_WEB_CACHE_IMAGE"
  if ! remote_retag_image "$MANAGER_WEB_CACHE_IMAGE" "$MANAGER_WEB_IMAGE_REPOSITORY:$CHANNEL_TAG"; then
    push_image "$MANAGER_WEB_IMAGE_REPOSITORY:$CHANNEL_TAG"
  fi
  if ! remote_retag_image "$MANAGER_WEB_CACHE_IMAGE" "$MANAGER_WEB_IMAGE_REPOSITORY:$IMAGE_VERSION_TAG"; then
    push_image "$MANAGER_WEB_IMAGE_REPOSITORY:$IMAGE_VERSION_TAG"
  fi
}

build_manager_assets_pipeline() {
  if image_available_locally_or_remote "$MANAGER_ASSETS_CACHE_IMAGE"; then
    print_base_image_status "Manager assets cached" "$MANAGER_ASSETS_CACHE_IMAGE"
    if [[ "$PUSH" == "true" ]] && ! image_exists_remote "$MANAGER_ASSETS_CACHE_IMAGE"; then
      push_local_image "$MANAGER_ASSETS_CACHE_IMAGE"
    fi
    retag_or_push_component "$MANAGER_ASSETS_CACHE_IMAGE" "$MANAGER_ASSETS_IMAGE_REPOSITORY"
    return
  fi

  build_image "Build manager assets image" Dockerfile-manager-assets \
    --build-arg "ASSETS_RUNTIME_BASE_IMAGE=$ASSETS_RUNTIME_BASE_IMAGE" \
    --tag "$MANAGER_ASSETS_IMAGE_REPOSITORY:$CHANNEL_TAG" \
    --tag "$MANAGER_ASSETS_IMAGE_REPOSITORY:$IMAGE_VERSION_TAG" \
    --tag "$MANAGER_ASSETS_CACHE_IMAGE"
  push_image "$MANAGER_ASSETS_CACHE_IMAGE"
  if ! remote_retag_image "$MANAGER_ASSETS_CACHE_IMAGE" "$MANAGER_ASSETS_IMAGE_REPOSITORY:$CHANNEL_TAG"; then
    push_image "$MANAGER_ASSETS_IMAGE_REPOSITORY:$CHANNEL_TAG"
  fi
  if ! remote_retag_image "$MANAGER_ASSETS_CACHE_IMAGE" "$MANAGER_ASSETS_IMAGE_REPOSITORY:$IMAGE_VERSION_TAG"; then
    push_image "$MANAGER_ASSETS_IMAGE_REPOSITORY:$IMAGE_VERSION_TAG"
  fi
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
  build_manager_api_pipeline &
  MANAGER_API_PIPELINE_PID=$!
  build_manager_web_pipeline &
  MANAGER_WEB_PIPELINE_PID=$!
  build_manager_assets_pipeline &
  MANAGER_ASSETS_PIPELINE_PID=$!

  PIPELINE_STATUS=0
  wait_for_pipeline "Server" "$SERVER_PIPELINE_PID" || PIPELINE_STATUS=1
  wait_for_pipeline "Manager API" "$MANAGER_API_PIPELINE_PID" || PIPELINE_STATUS=1
  wait_for_pipeline "Manager Web" "$MANAGER_WEB_PIPELINE_PID" || PIPELINE_STATUS=1
  wait_for_pipeline "Manager assets" "$MANAGER_ASSETS_PIPELINE_PID" || PIPELINE_STATUS=1
  if [[ "$PIPELINE_STATUS" -ne 0 ]]; then
    exit "$PIPELINE_STATUS"
  fi
else
  build_server_pipeline
  build_manager_api_pipeline
  build_manager_web_pipeline
  build_manager_assets_pipeline
fi

echo "Done."

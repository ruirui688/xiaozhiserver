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
BUILD_BASE="${BUILD_BASE:-true}"
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

run_docker_step() {
  local label="$1"
  shift

  echo "$label"
  if ! is_quiet; then
    "$@"
    return
  fi

  local log_file="$LOG_DIR/$(printf '%s' "$label" | tr -cs '[:alnum:]_.-' '_' | sed 's/^_*//; s/_*$//').log"
  if "$@" >"$log_file" 2>&1; then
    echo "  OK"
  else
    echo "  FAILED. Full log: $log_file" >&2
    tail -n 120 "$log_file" >&2 || true
    return 1
  fi
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
  if is_quiet; then
    run_docker_step "Push $image" docker push --quiet "$image"
  else
    run_docker_step "Push $image" docker push "$image"
  fi
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

if [[ "$BUILD_BASE" == "true" ]]; then
  build_image "Build server base image" Dockerfile-server-base \
    --build-arg "PYTHON_BASE_IMAGE=$PYTHON_BASE_IMAGE" \
    --build-arg "DEBIAN_MIRROR=$DEBIAN_MIRROR" \
    --build-arg "DEBIAN_SECURITY_MIRROR=$DEBIAN_SECURITY_MIRROR" \
    --build-arg "SERVER_REQUIREMENTS=$SERVER_REQUIREMENTS" \
    --tag "$SERVER_BASE_IMAGE_REPOSITORY:latest" \
    --tag "$SERVER_BASE_IMAGE_REPOSITORY:$VERSION"
  push_image "$SERVER_BASE_IMAGE_REPOSITORY:latest"
  push_image "$SERVER_BASE_IMAGE_REPOSITORY:$VERSION"
fi

if [[ -z "${SERVER_BASE_IMAGE:-}" ]]; then
  if [[ "$BUILD_BASE" == "true" ]]; then
    SERVER_BASE_IMAGE="$SERVER_BASE_IMAGE_REPOSITORY:$VERSION"
  else
    SERVER_BASE_IMAGE="$SERVER_BASE_IMAGE_REPOSITORY:latest"
  fi
fi

build_image "Build xiaozhi-server image" Dockerfile-server \
  --build-arg "SERVER_BASE_IMAGE=$SERVER_BASE_IMAGE" \
  --tag "$SERVER_IMAGE_REPOSITORY:latest" \
  --tag "$SERVER_IMAGE_REPOSITORY:$VERSION"
push_image "$SERVER_IMAGE_REPOSITORY:latest"
push_image "$SERVER_IMAGE_REPOSITORY:$VERSION"

build_image "Build manager web/api image" Dockerfile-web \
  --build-arg "NODE_BASE_IMAGE=$NODE_BASE_IMAGE" \
  --build-arg "MAVEN_BASE_IMAGE=$MAVEN_BASE_IMAGE" \
  --build-arg "WEB_RUNTIME_BASE_IMAGE=$WEB_RUNTIME_BASE_IMAGE" \
  --tag "$WEB_IMAGE_REPOSITORY:latest" \
  --tag "$WEB_IMAGE_REPOSITORY:$VERSION"
push_image "$WEB_IMAGE_REPOSITORY:latest"
push_image "$WEB_IMAGE_REPOSITORY:$VERSION"

echo "Done."

#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_IMAGE_REPOSITORY="xiaozhi-esp32-server-local"

IMAGE_REPOSITORY="${IMAGE_REPOSITORY:-$DEFAULT_IMAGE_REPOSITORY}"
case "$(uname -m)" in
  x86_64) DEFAULT_PLATFORM="linux/amd64" ;;
  aarch64 | arm64) DEFAULT_PLATFORM="linux/arm64" ;;
  *) DEFAULT_PLATFORM="linux/amd64" ;;
esac
PLATFORMS="${PLATFORMS:-$DEFAULT_PLATFORM}"
PUSH="${PUSH:-false}"
BUILD_BASE="${BUILD_BASE:-true}"
BUILDER_NAME="${BUILDER_NAME:-xiaozhi-builder}"

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

if ! docker buildx inspect "$BUILDER_NAME" >/dev/null 2>&1; then
  docker buildx create --name "$BUILDER_NAME" --use >/dev/null
else
  docker buildx use "$BUILDER_NAME" >/dev/null
fi

OUTPUT_FLAG=(--push)
if [[ "$PUSH" == "false" ]]; then
  OUTPUT_FLAG=(--load)
fi

build_image() {
  local dockerfile="$1"
  shift
  docker buildx build \
    --platform "$PLATFORMS" \
    --file "$ROOT_DIR/$dockerfile" \
    "$ROOT_DIR" \
    "${OUTPUT_FLAG[@]}" \
    "$@"
}

echo "Image repository: $IMAGE_REPOSITORY"
echo "Version: $VERSION"
echo "Platforms: $PLATFORMS"
echo "Push: $PUSH"

if [[ "$BUILD_BASE" == "true" ]]; then
  echo "Building server base image"
  build_image Dockerfile-server-base \
    --tag "$IMAGE_REPOSITORY:server-base"
fi

echo "Building xiaozhi-server image"
build_image Dockerfile-server \
  --build-arg "SERVER_BASE_IMAGE=$IMAGE_REPOSITORY:server-base" \
  --tag "$IMAGE_REPOSITORY:server_latest" \
  --tag "$IMAGE_REPOSITORY:server_$VERSION"

echo "Building manager web/api image"
build_image Dockerfile-web \
  --tag "$IMAGE_REPOSITORY:web_latest" \
  --tag "$IMAGE_REPOSITORY:web_$VERSION"

echo "Done."

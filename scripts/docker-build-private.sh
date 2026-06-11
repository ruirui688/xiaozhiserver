#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_IMAGE_REPOSITORY="10.144.92.32:3001/gaoruirui688/xiaozhi-esp32-server"

IMAGE_REPOSITORY="${IMAGE_REPOSITORY:-$DEFAULT_IMAGE_REPOSITORY}"
PLATFORMS="${PLATFORMS:-linux/amd64}"
PUSH="${PUSH:-true}"
BUILD_BASE="${BUILD_BASE:-true}"
BUILDER_NAME="${BUILDER_NAME:-xiaozhi-builder}"

if [[ -z "${VERSION:-}" ]]; then
  if VERSION_TAG="$(git -C "$ROOT_DIR" describe --tags --exact-match 2>/dev/null)"; then
    VERSION="${VERSION_TAG#v}"
  else
    VERSION="$(git -C "$ROOT_DIR" rev-parse --short=8 HEAD)"
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

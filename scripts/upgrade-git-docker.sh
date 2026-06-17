#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REMOTE="${REMOTE:-origin}"
REF="${REF:-}"

if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
  echo "Do not run this script with sudo. It needs your Git SSH permission first and will call sudo only for Docker install." >&2
  exit 1
fi

cd "$ROOT_DIR"

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "$ROOT_DIR is not a Git work tree." >&2
  exit 1
fi

if ! git diff-index --quiet HEAD --; then
  echo "The working tree has local tracked changes. Commit or discard them before production upgrade." >&2
  exit 1
fi

if [[ -n "$REF" ]]; then
  echo "Fetching $REMOTE and checking out $REF"
  git fetch --tags "$REMOTE"
  git checkout "$REF"
else
  BRANCH="$(git symbolic-ref --short HEAD 2>/dev/null || true)"
  if [[ -n "$BRANCH" ]]; then
    echo "Pulling $REMOTE/$BRANCH"
    git pull --ff-only "$REMOTE" "$BRANCH"
  else
    echo "Detached HEAD and REF is empty; rebuilding current checkout."
  fi
fi

echo "Rebuilding local Docker images and restarting services"
sudo -E "$ROOT_DIR/scripts/install-private-docker.sh"

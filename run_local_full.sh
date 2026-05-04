#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCAL_DIR="$ROOT_DIR/infra/local"
INFRA_DIR="$ROOT_DIR/infra"

if docker compose version >/dev/null 2>&1; then
  COMPOSE_CMD=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE_CMD=(docker-compose)
else
  echo "Error: docker compose/docker-compose not found." >&2
  exit 1
fi

if ! command -v tflocal >/dev/null 2>&1; then
  echo "Error: tflocal not found. Install with: pip install terraform-local" >&2
  exit 1
fi

cd "$LOCAL_DIR"
"${COMPOSE_CMD[@]}" -f docker-compose-full.yml up -d

cd "$INFRA_DIR"
tflocal apply -auto-approve

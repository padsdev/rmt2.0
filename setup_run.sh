#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "[setup_run] Building Java modules (clean + skip tests)..."
mvn -DskipTests clean install -f "$ROOT_DIR/config-starter/pom.xml"
mvn -DskipTests clean install -f "$ROOT_DIR/detection-and-refactoring/pom.xml"
mvn -DskipTests clean install -f "$ROOT_DIR/project-sync-bff/pom.xml"
mvn -DskipTests clean install -f "$ROOT_DIR/metrics-calculator/pom.xml"

echo "[setup_run] Building Docker images..."
docker build -t magnus/rmt-ai "$ROOT_DIR/rmt-ai-module/rmt-ai-service"
docker build -t magnus/detection "$ROOT_DIR/detection-and-refactoring"
docker build -t magnus/manager "$ROOT_DIR/project-sync-bff"
docker build -t magnus/metrics "$ROOT_DIR/metrics-calculator"

echo "[setup_run] Starting local stack..."
"$ROOT_DIR/run_local_full.sh"

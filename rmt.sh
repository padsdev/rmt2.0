#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  cat <<EOF
Usage: ./rmt.sh [command]

Commands:
  build     Build all Maven modules
  images    Build Docker images for all services
  infra     Start infrastructure (LocalStack + Redis) and provision AWS resources via Terraform
  dev       build + infra  (run services locally with mvn spring-boot:run)
  all       build + images + infra  (full Docker environment)  [default]

Examples:
  ./rmt.sh           # same as ./rmt.sh all
  ./rmt.sh dev       # build and start infra, then run services manually
  ./rmt.sh build     # only compile and install Maven modules
EOF
}

resolve_ai_python() {
  local service_dir="$SCRIPT_DIR/rmt-ai-module/rmt-ai-service"

  if [[ -x "$service_dir/.venv-py312/bin/python" ]]; then
    printf '%s' "$service_dir/.venv-py312/bin/python"
    return
  fi

  if [[ -x "$service_dir/.venv/bin/python" ]]; then
    printf '%s' "$service_dir/.venv/bin/python"
    return
  fi

  if command -v python3 >/dev/null 2>&1; then
    printf '%s' "python3"
    return
  fi

  printf '%s' "python"
}

print_dev_commands() {
  local ai_python
  local shadow_export_path
  ai_python="$(resolve_ai_python)"
  shadow_export_path="$SCRIPT_DIR/detection-and-refactoring/target/manual-shadow.jsonl"

  echo "Infrastructure is up. Start each service with:"
  echo "  mvn spring-boot:run -pl project-sync-bff"
  echo "  RMT_AI_ENABLED=true RMT_AI_SHADOW_EXPORT_PATH=\"$shadow_export_path\" mvn spring-boot:run -pl detection-and-refactoring"
  echo "  mvn spring-boot:run -pl metrics-calculator"
  echo "  (cd \"$SCRIPT_DIR/rmt-ai-module/rmt-ai-service\" && \"$ai_python\" -m uvicorn app.main:app --host 0.0.0.0 --port 8000)"
  echo ""
  echo "AI service env is taken from your current shell. Set RMT_AI_BACKEND_MODE, RMT_AI_DEVICE_PREFERENCE,"
  echo "RMT_AI_FINETUNED_ARTIFACT_PATH, RMT_AI_EXPERIMENT_PROFILE, and RMT_AI_READ_TIMEOUT before starting"
  echo "the services when you want a non-default backend or slower real-model inference."
}

build() {
  echo "==> Building all modules..."
  mvn clean install -f "$SCRIPT_DIR/pom.xml"
}

images() {
  echo "==> Building Docker images..."
  docker build -t magnus/detection "$SCRIPT_DIR/detection-and-refactoring"
  docker build -t magnus/manager   "$SCRIPT_DIR/project-sync-bff"
  docker build -t magnus/metrics   "$SCRIPT_DIR/metrics-calculator"
  docker build -t magnus/rmt-ai-service "$SCRIPT_DIR/rmt-ai-module/rmt-ai-service"
}

infra() {
  echo "==> Starting infrastructure..."
  docker compose -f "$SCRIPT_DIR/infra/local/docker-compose.yml" up -d

  echo "==> Provisioning AWS resources..."
  tflocal -chdir="$SCRIPT_DIR/infra" apply -auto-approve
}

infra_full() {
  echo "==> Starting full environment..."
  docker compose -f "$SCRIPT_DIR/infra/local/docker-compose-full.yml" up -d

  echo "==> Provisioning AWS resources..."
  tflocal -chdir="$SCRIPT_DIR/infra" apply -auto-approve
}

verify_full_stack() {
  local compose_file="$SCRIPT_DIR/infra/local/docker-compose-full.yml"

  echo "==> Verifying application containers..."
  sleep 5

  local exited_services
  exited_services="$(docker compose -f "$compose_file" ps --status exited --services || true)"

  if [[ -n "$exited_services" ]]; then
    echo "One or more services exited during startup:"
    echo "$exited_services"
    echo ""
    echo "Recent logs:"
    docker compose -f "$compose_file" logs --tail=100 $exited_services || true
    return 1
  fi

  if ! docker compose -f "$compose_file" ps --status running --services | grep -qx "intermediary"; then
    echo "The intermediary service is not running, so the UI is not available on http://localhost:8080"
    docker compose -f "$compose_file" ps
    return 1
  fi
}

case "${1:-all}" in
  build)
    build
    ;;
  images)
    images
    ;;
  infra)
    infra
    ;;
  dev)
    build
    infra
    echo ""
    print_dev_commands
    ;;
  all)
    build
    images
    infra_full
    verify_full_stack
    echo ""
    echo "Done. UI available at http://localhost:8080"
    ;;
  help|--help|-h)
    usage
    ;;
  *)
    echo "Unknown command: ${1}"
    echo ""
    usage
    exit 1
    ;;
esac

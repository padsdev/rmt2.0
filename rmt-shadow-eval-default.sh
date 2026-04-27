#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_INPUT="$SCRIPT_DIR/detection-and-refactoring/target/rmt-ai-shadow-observations.jsonl"

usage() {
  cat <<EOF
Usage: ./rmt-shadow-eval-default.sh [output-dir] [--skip-build]

Defaults:
  input  -> detection-and-refactoring/target/rmt-ai-shadow-observations.jsonl
  output -> ./target/shadow-eval/<timestamp>

Examples:
  ./rmt-shadow-eval-default.sh
  ./rmt-shadow-eval-default.sh /tmp/rmt-shadow-eval
  ./rmt-shadow-eval-default.sh ./target/my-run --skip-build
EOF
}

main() {
  local output_dir=""
  local -a extra_args=()

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --skip-build)
        extra_args+=("--skip-build")
        shift
        ;;
      --help|-h)
        usage
        exit 0
        ;;
      *)
        if [[ -n "$output_dir" ]]; then
          echo "Only one positional output directory is supported."
          echo ""
          usage
          exit 1
        fi
        output_dir="$1"
        shift
        ;;
    esac
  done

  if [[ ! -f "$DEFAULT_INPUT" ]]; then
    echo "Default shadow export not found: $DEFAULT_INPUT"
    echo "Generate the M4 JSONL export first or use ./rmt-shadow-eval.sh with --input."
    exit 1
  fi

  if [[ -z "$output_dir" ]]; then
    output_dir="$SCRIPT_DIR/target/shadow-eval/$(date +%Y%m%d-%H%M%S)"
  fi

  "$SCRIPT_DIR/rmt-shadow-eval.sh" \
    --input "$DEFAULT_INPUT" \
    --output "$output_dir" \
    "${extra_args[@]}"
}

main "$@"

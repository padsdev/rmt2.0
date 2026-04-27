#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$SCRIPT_DIR/detection-and-refactoring"
CLASSPATH_FILE="$MODULE_DIR/target/eval.classpath"
MAIN_CLASS="br.com.magnus.detectionandrefactoring.ai.experimental.evaluation.ShadowExperimentEvaluationCli"

usage() {
  cat <<EOF
Usage: ./rmt-shadow-eval.sh --input <path> [--input <path> ...] --output <dir> [options]

Options:
  --input <path>     JSONL file or directory containing JSONL files. Repeatable.
  --output <dir>     Output directory for the generated reports.
  --skip-build       Reuse existing compiled classes and classpath file.
  --help             Show this help message.

Examples:
  ./rmt-shadow-eval.sh \\
    --input detection-and-refactoring/target/rmt-ai-shadow-observations.jsonl \\
    --output /tmp/rmt-shadow-eval

  ./rmt-shadow-eval.sh \\
    --input /data/shadow-exports \\
    --input /data/extra/run-02.jsonl \\
    --output ./target/shadow-eval
EOF
}

build_if_needed() {
  local skip_build="$1"

  if [[ "$skip_build" == "true" ]]; then
    if [[ ! -f "$CLASSPATH_FILE" ]]; then
      echo "Missing classpath file: $CLASSPATH_FILE"
      echo "Run without --skip-build at least once."
      exit 1
    fi
    return
  fi

  echo "==> Compiling evaluator classes..."
  mvn -pl detection-and-refactoring -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile=target/eval.classpath
}

main() {
  local skip_build="false"
  local output_dir=""
  local -a inputs=()
  local -a cli_args=()

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --input)
        if [[ $# -lt 2 ]]; then
          echo "Missing value for --input"
          exit 1
        fi
        inputs+=("$2")
        cli_args+=("--input" "$2")
        shift 2
        ;;
      --output)
        if [[ $# -lt 2 ]]; then
          echo "Missing value for --output"
          exit 1
        fi
        output_dir="$2"
        cli_args+=("--output" "$2")
        shift 2
        ;;
      --skip-build)
        skip_build="true"
        shift
        ;;
      --help|-h)
        usage
        exit 0
        ;;
      *)
        echo "Unknown argument: $1"
        echo ""
        usage
        exit 1
        ;;
    esac
  done

  if [[ ${#inputs[@]} -eq 0 ]]; then
    echo "At least one --input path is required."
    echo ""
    usage
    exit 1
  fi

  if [[ -z "$output_dir" ]]; then
    echo "--output is required."
    echo ""
    usage
    exit 1
  fi

  build_if_needed "$skip_build"

  if [[ ! -f "$CLASSPATH_FILE" ]]; then
    echo "Missing classpath file after build: $CLASSPATH_FILE"
    exit 1
  fi

  local runtime_classpath
  runtime_classpath="$MODULE_DIR/target/classes:$(cat "$CLASSPATH_FILE")"

  echo "==> Running shadow evaluation..."
  java -cp "$runtime_classpath" "$MAIN_CLASS" "${cli_args[@]}"

  echo "==> Reports generated at: $output_dir"
}

main "$@"

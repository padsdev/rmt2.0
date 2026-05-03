#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$SCRIPT_DIR/detection-and-refactoring"
CLASSPATH_FILE="$MODULE_DIR/target/eval.classpath"
MAIN_CLASS="br.com.magnus.detectionandrefactoring.ai.experimental.evaluation.ShadowExperimentEvaluationCli"

usage() {
  cat <<EOF
Usage: ./rmt-shadow-eval.sh --input <path> [--input <path> ...] --output <dir> [options]

Offline M5 evaluator only.
This script does not start the Python AI service or the RMT stack.

Options:
  --input <path>     JSONL file or directory containing JSONL files. Repeatable.
  --output <dir>     Output directory for the generated reports.
  --m2-repo <dir>    Maven local repository path. Default: /tmp/rmt-m2
  --maven-home <dir> Maven user home path. Default: /tmp/rmt-maven-home
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

ensure_writable_dir() {
  local dir="$1"
  local label="$2"
  mkdir -p "$dir"
  if [[ ! -d "$dir" || ! -w "$dir" ]]; then
    echo "$label is not writable: $dir"
    exit 1
  fi
}

count_input_records() {
  local -a input_paths=("$@")
  local total=0
  local path

  for path in "${input_paths[@]}"; do
    if [[ -d "$path" ]]; then
      while IFS= read -r file; do
        total=$((total + $(wc -l < "$file")))
      done < <(find "$path" -type f -name '*.jsonl' -print)
    elif [[ -f "$path" ]]; then
      total=$((total + $(wc -l < "$path")))
    fi
  done

  echo "$total"
}

preflight_checks() {
  local output_dir="$1"
  local m2_repo="$2"
  local maven_home="$3"
  shift 3
  local -a input_paths=("$@")

  if ! command -v mvn >/dev/null 2>&1; then
    echo "mvn not found in PATH. Install Maven or run in an environment that has mvn."
    exit 1
  fi

  ensure_writable_dir "$m2_repo" "Maven local repository"
  ensure_writable_dir "$maven_home" "Maven user home"
  ensure_writable_dir "$output_dir" "Output directory"

  local path
  for path in "${input_paths[@]}"; do
    if [[ ! -e "$path" ]]; then
      echo "Input path does not exist: $path"
      exit 1
    fi
  done

  local record_count
  record_count="$(count_input_records "${input_paths[@]}")"
  if [[ "$record_count" -le 0 ]]; then
    echo "Input JSONL has zero records across provided --input paths."
    exit 1
  fi

  echo "==> Preflight OK: mvn available, writable repo/home/output, input_records=$record_count"
}

build_if_needed() {
  local skip_build="$1"
  local m2_repo="$2"
  local maven_home="$3"

  if [[ "$skip_build" == "true" ]]; then
    if [[ ! -f "$CLASSPATH_FILE" ]]; then
      echo "Missing classpath file: $CLASSPATH_FILE"
      echo "Run without --skip-build at least once."
      exit 1
    fi
    return
  fi

  echo "==> Compiling evaluator classes..."
  mvn -Dmaven.repo.local="$m2_repo" -Duser.home="$maven_home" \
    -pl detection-and-refactoring -DskipTests test-compile dependency:build-classpath \
    -Dmdep.outputFile=target/eval.classpath
}

main() {
  local skip_build="false"
  local output_dir=""
  local m2_repo="/tmp/rmt-m2"
  local maven_home="/tmp/rmt-maven-home"
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
      --m2-repo)
        if [[ $# -lt 2 ]]; then
          echo "Missing value for --m2-repo"
          exit 1
        fi
        m2_repo="$2"
        shift 2
        ;;
      --maven-home)
        if [[ $# -lt 2 ]]; then
          echo "Missing value for --maven-home"
          exit 1
        fi
        maven_home="$2"
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

  preflight_checks "$output_dir" "$m2_repo" "$maven_home" "${inputs[@]}"
  build_if_needed "$skip_build" "$m2_repo" "$maven_home"

  if [[ ! -f "$CLASSPATH_FILE" ]]; then
    echo "Missing classpath file after build: $CLASSPATH_FILE"
    exit 1
  fi

  local runtime_classpath
  runtime_classpath="$MODULE_DIR/target/classes:$(cat "$CLASSPATH_FILE")"

  echo "==> Running shadow evaluation..."
  java -Dmaven.repo.local="$m2_repo" -Duser.home="$maven_home" \
    -cp "$runtime_classpath" "$MAIN_CLASS" "${cli_args[@]}"

  echo "==> Reports generated at: $output_dir"
}

main "$@"

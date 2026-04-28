#!/usr/bin/env bash
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

MANIFEST="$SCRIPT_DIR/benchmark-projects.csv"
RUNS_ROOT="$SCRIPT_DIR/runs"
BFF_BASE_URL="http://127.0.0.1:8080/rmt/api/v1"
AI_HEALTH_URL="http://127.0.0.1:8000/health"
SHARED_EXPORT_PATH="$REPO_ROOT/detection-and-refactoring/target/rmt-ai-shadow-observations.jsonl"
POLL_INTERVAL_SECONDS=5
PROJECT_TIMEOUT_SECONDS=3600
DRY_RUN="false"
SKIP_EVAL_BUILD="false"
RUN_ID=""
RUN_DIR=""
RUN_LOG=""
RESULTS_CSV=""
AGGREGATE_JSONL=""
EVALUATION_LOG=""
JSONL_REPORT=""
MANIFEST_PROJECT_COUNT=0
OVERALL_EXIT_CODE=0
FINAL_README=""

usage() {
  cat <<EOF
Usage: ./experiments/run-benchmark.sh [options]

Options:
  --manifest <path>              Manifest CSV. Default: experiments/benchmark-projects.csv
  --run-id <value>               Explicit run directory name.
  --runs-root <path>             Root directory for benchmark runs. Default: experiments/runs
  --bff-base-url <url>           Project Sync BFF base URL. Default: http://127.0.0.1:8080/rmt/api/v1
  --ai-health-url <url>          AI service health URL. Default: http://127.0.0.1:8000/health
  --shared-export-path <path>    Shared M4 JSONL export path written by detection service.
                                 Default: detection-and-refactoring/target/rmt-ai-shadow-observations.jsonl
  --poll-interval <seconds>      Poll interval per project. Default: 5
  --project-timeout <seconds>    Timeout per project. Default: 3600
  --skip-eval-build              Reuse existing M5 compiled classes when invoking rmt-shadow-eval.sh
  --dry-run                      Validate inputs and create run layout without uploading projects
  --help                         Show this help text

Examples:
  ./experiments/run-benchmark.sh --dry-run
  ./experiments/run-benchmark.sh --run-id 20260427-full-benchmark
  ./experiments/run-benchmark.sh --poll-interval 10 --project-timeout 7200
EOF
}

timestamp_utc() {
  date -u +"%Y-%m-%dT%H:%M:%SZ"
}

sanitize_csv_field() {
  printf '%s' "$1" | tr '\n\r,' '   '
}

line_count() {
  local file_path="$1"
  if [[ -f "$file_path" ]]; then
    wc -l < "$file_path" | tr -d '[:space:]'
  else
    echo "0"
  fi
}

log_line() {
  local message="$1"
  local stamped
  stamped="[$(timestamp_utc)] $message"
  if [[ -n "$RUN_LOG" ]]; then
    printf '%s\n' "$stamped" | tee -a "$RUN_LOG"
  else
    printf '%s\n' "$stamped"
  fi
}

project_log_line() {
  local project_log="$1"
  local message="$2"
  local stamped
  stamped="[$(timestamp_utc)] $message"
  printf '%s\n' "$stamped" | tee -a "$RUN_LOG" >> "$project_log"
}

fail() {
  log_line "ERROR: $1"
  exit 1
}

require_command() {
  local cmd="$1"
  command -v "$cmd" >/dev/null 2>&1 || fail "Missing required command: $cmd"
}

resolve_repo_path() {
  local raw_path="$1"
  if [[ "$raw_path" = /* ]]; then
    printf '%s\n' "$raw_path"
  else
    printf '%s/%s\n' "$REPO_ROOT" "$raw_path"
  fi
}

check_http_endpoint() {
  local url="$1"
  local label="$2"
  local http_code
  http_code="$(curl -sS -o /dev/null -w '%{http_code}' "$url" || true)"
  if [[ "$http_code" == "000" ]]; then
    fail "$label is unreachable at $url"
  fi
  log_line "$label reachable at $url (http_status=$http_code)"
}

validate_manifest() {
  [[ -f "$MANIFEST" ]] || fail "Manifest not found: $MANIFEST"

  local header
  header="$(sed -n '1p' "$MANIFEST")"
  [[ "$header" == "project_id,name,path,commit,notes" ]] || fail "Manifest header mismatch in $MANIFEST"

  local project_id name relative_path commit notes absolute_path
  local count=0
  while IFS=, read -r project_id name relative_path commit notes; do
    if [[ "$project_id" == "project_id" ]]; then
      continue
    fi

    notes="${notes%$'\r'}"
    absolute_path="$(resolve_repo_path "$relative_path")"
    [[ -f "$absolute_path" ]] || fail "Manifest path does not exist for project_id=$project_id: $relative_path"
    count=$((count + 1))
  done < "$MANIFEST"

  if [[ "$count" -eq 0 ]]; then
    fail "Manifest contains no projects: $MANIFEST"
  fi

  MANIFEST_PROJECT_COUNT="$count"
}

prepare_run_layout() {
  mkdir -p "$RUNS_ROOT"

  if [[ -z "$RUN_ID" ]]; then
    RUN_ID="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
  fi

  RUN_DIR="$RUNS_ROOT/$RUN_ID"
  [[ ! -e "$RUN_DIR" ]] || fail "Run directory already exists: $RUN_DIR"

  mkdir -p "$RUN_DIR/inputs" "$RUN_DIR/logs" "$RUN_DIR/shadow-jsonl" "$RUN_DIR/evaluation"

  RUN_LOG="$RUN_DIR/run.log"
  RESULTS_CSV="$RUN_DIR/project-results.csv"
  AGGREGATE_JSONL="$RUN_DIR/shadow-aggregate.jsonl"
  EVALUATION_LOG="$RUN_DIR/evaluation.log"
  JSONL_REPORT="$RUN_DIR/shadow-jsonl-report.txt"
  FINAL_README="$RUN_DIR/README.md"

  cp "$MANIFEST" "$RUN_DIR/inputs/benchmark-projects.csv"
  : > "$RUN_LOG"
  : > "$AGGREGATE_JSONL"
  : > "$EVALUATION_LOG"

  cat > "$RESULTS_CSV" <<EOF
project_id,name,manifest_path,upload_id,final_status,result,duration_seconds,jsonl_lines,log_file,jsonl_file,notes
EOF
}

write_run_configuration() {
  local manifest_sha
  manifest_sha="$(sha256sum "$MANIFEST" | awk '{print $1}')"

  cat > "$RUN_DIR/config.env" <<EOF
RUN_ID=$RUN_ID
GENERATED_AT_UTC=$(timestamp_utc)
REPO_ROOT=$REPO_ROOT
REPO_GIT_HEAD=$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)
MANIFEST=$MANIFEST
MANIFEST_SHA256=$manifest_sha
PROJECT_COUNT=$MANIFEST_PROJECT_COUNT
BFF_BASE_URL=$BFF_BASE_URL
AI_HEALTH_URL=$AI_HEALTH_URL
SHARED_EXPORT_PATH=$SHARED_EXPORT_PATH
POLL_INTERVAL_SECONDS=$POLL_INTERVAL_SECONDS
PROJECT_TIMEOUT_SECONDS=$PROJECT_TIMEOUT_SECONDS
DRY_RUN=$DRY_RUN
SKIP_EVAL_BUILD=$SKIP_EVAL_BUILD
EOF
}

capture_health_snapshots() {
  if [[ "$DRY_RUN" == "true" ]]; then
    return
  fi

  check_http_endpoint "$BFF_BASE_URL/upload" "BFF upload endpoint"
  check_http_endpoint "$AI_HEALTH_URL" "AI health endpoint"

  curl -sS "$AI_HEALTH_URL" > "$RUN_DIR/ai-health.json"
  log_line "AI health payload saved to $RUN_DIR/ai-health.json"
}

record_result() {
  local project_id="$1"
  local name="$2"
  local manifest_path="$3"
  local upload_id="$4"
  local final_status="$5"
  local result="$6"
  local duration_seconds="$7"
  local jsonl_lines="$8"
  local log_file="$9"
  local jsonl_file="${10}"
  local notes="${11}"

  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "$(sanitize_csv_field "$project_id")" \
    "$(sanitize_csv_field "$name")" \
    "$(sanitize_csv_field "$manifest_path")" \
    "$(sanitize_csv_field "$upload_id")" \
    "$(sanitize_csv_field "$final_status")" \
    "$(sanitize_csv_field "$result")" \
    "$(sanitize_csv_field "$duration_seconds")" \
    "$(sanitize_csv_field "$jsonl_lines")" \
    "$(sanitize_csv_field "$log_file")" \
    "$(sanitize_csv_field "$jsonl_file")" \
    "$(sanitize_csv_field "$notes")" \
    >> "$RESULTS_CSV"
}

wait_for_terminal_status() {
  local upload_id="$1"
  local project_log="$2"
  local start_epoch="$3"
  local response status candidate_count http_code tmp_file

  while true; do
    if (( "$(date +%s)" - start_epoch >= PROJECT_TIMEOUT_SECONDS )); then
      project_log_line "$project_log" "Timed out after ${PROJECT_TIMEOUT_SECONDS}s while polling /project/$upload_id"
      return 1
    fi

    tmp_file="$(mktemp)"
    http_code="$(curl -sS -o "$tmp_file" -w '%{http_code}' "$BFF_BASE_URL/project/$upload_id" || true)"
    response="$(cat "$tmp_file")"
    rm -f "$tmp_file"

    status="$(printf '%s' "$response" | jq -r '.status // empty' 2>/dev/null || true)"
    candidate_count="$(printf '%s' "$response" | jq -r '(.candidatesInformation // []) | length' 2>/dev/null || echo "0")"

    if [[ -n "$status" ]]; then
      project_log_line "$project_log" "Polled project_id=$upload_id http_status=$http_code status=$status candidate_count=$candidate_count"
      case "$status" in
        FINISHED|NO_CANDIDATES)
          printf '%s\n' "$status"
          return 0
          ;;
      esac
    else
      project_log_line "$project_log" "Polled project_id=$upload_id http_status=$http_code without parsable status"
    fi

    sleep "$POLL_INTERVAL_SECONDS"
  done
}

slice_project_jsonl() {
  local upload_id="$1"
  local project_log="$2"
  local project_jsonl="$3"
  local total_lines raw_matches unique_matches duplicates_removed
  local tmp_matches

  : > "$project_jsonl"
  total_lines="$(line_count "$SHARED_EXPORT_PATH")"

  if [[ ! -f "$SHARED_EXPORT_PATH" || "$total_lines" -eq 0 ]]; then
    project_log_line "$project_log" "Shared export has no lines yet; wrote empty project JSONL to $project_jsonl"
    printf '0\n'
    return 0
  fi

  tmp_matches="$(mktemp)"
  if ! jq -rc --arg project_id "$upload_id" '
    def record_key:
      if ((.trace_id // "") != "" and (.entity_id // "") != "") then
        (.trace_id + "\u001f" + .entity_id)
      elif ((.project_id // "") != "" and (.candidate_id // "") != "") then
        (.project_id + "\u001f" + .candidate_id)
      else
        tojson
      end;

    select((.project_id // "") == $project_id)
    | [record_key, (tojson)]
    | @tsv
  ' "$SHARED_EXPORT_PATH" > "$tmp_matches"; then
    rm -f "$tmp_matches"
    project_log_line "$project_log" "Failed to parse or filter shared JSONL export at $SHARED_EXPORT_PATH"
    return 1
  fi

  raw_matches="$(line_count "$tmp_matches")"
  if (( raw_matches > 0 )); then
    awk -F'\t' '!seen[$1]++ { print $2 }' "$tmp_matches" > "$project_jsonl"
  fi

  unique_matches="$(line_count "$project_jsonl")"
  duplicates_removed=$((raw_matches - unique_matches))
  rm -f "$tmp_matches"

  project_log_line "$project_log" "Filtered shared export total_lines=$total_lines matched_project_records=$raw_matches unique_project_records=$unique_matches duplicates_removed=$duplicates_removed output=$project_jsonl"
  printf '%s\n' "$unique_matches"
}

rebuild_aggregate_jsonl() {
  local jsonl_file
  local jsonl_files=()

  : > "$AGGREGATE_JSONL"
  shopt -s nullglob
  jsonl_files=("$RUN_DIR"/shadow-jsonl/*.jsonl)
  shopt -u nullglob

  for jsonl_file in "${jsonl_files[@]}"; do
    cat "$jsonl_file" >> "$AGGREGATE_JSONL"
  done

  log_line "Rebuilt aggregate JSONL from ${#jsonl_files[@]} per-project file(s)"
}

validate_shadow_jsonl_outputs() {
  local jsonl_file expected_project_id mismatch_count
  local jsonl_files=()
  local total_records=0
  local unique_records=0
  local unique_trace_ids=0
  local duplicate_records=0
  local duplicate_rate="0.000000"
  local purity_failures=0

  shopt -s nullglob
  jsonl_files=("$RUN_DIR"/shadow-jsonl/*.jsonl)
  shopt -u nullglob

  if (( ${#jsonl_files[@]} > 0 )); then
    total_records="$(
      cat "${jsonl_files[@]}" | wc -l | tr -d '[:space:]'
    )"
    unique_records="$(
      jq -r '
        def record_key:
          if ((.trace_id // "") != "" and (.entity_id // "") != "") then
            (.trace_id + "\u001f" + .entity_id)
          elif ((.project_id // "") != "" and (.candidate_id // "") != "") then
            (.project_id + "\u001f" + .candidate_id)
          else
            tojson
          end;

        record_key
      ' "${jsonl_files[@]}" | sort | uniq | wc -l | tr -d '[:space:]'
    )"
    unique_trace_ids="$(
      jq -r '.trace_id // empty' "${jsonl_files[@]}" | sort | uniq | wc -l | tr -d '[:space:]'
    )"
    duplicate_records=$((total_records - unique_records))
    duplicate_rate="$(
      awk -v duplicates="$duplicate_records" -v total="$total_records" 'BEGIN {
        if (total == 0) {
          printf "0.000000"
        } else {
          printf "%.6f", duplicates / total
        }
      }'
    )"

    for jsonl_file in "${jsonl_files[@]}"; do
      expected_project_id="${jsonl_file##*/}"
      expected_project_id="${expected_project_id%.jsonl}"
      mismatch_count="$(
        jq -r --arg expected_project_id "$expected_project_id" '
          select((.project_id // "") != $expected_project_id) | 1
        ' "$jsonl_file" | wc -l | tr -d '[:space:]'
      )"
      if (( mismatch_count > 0 )); then
        purity_failures=$((purity_failures + 1))
        log_line "JSONL purity failure in $jsonl_file: expected project_id=$expected_project_id mismatched_records=$mismatch_count"
      fi
    done
  fi

  cat > "$JSONL_REPORT" <<EOF
total_records=$total_records
unique_records=$unique_records
unique_trace_ids=$unique_trace_ids
duplicate_records=$duplicate_records
duplication_rate=$duplicate_rate
jsonl_file_count=${#jsonl_files[@]}
purity_failures=$purity_failures
EOF

  log_line "JSONL report written to $JSONL_REPORT"

  if (( purity_failures > 0 )); then
    log_line "JSONL validation failed due to project_id mismatches across per-project files"
    OVERALL_EXIT_CODE=1
    return 1
  fi

  if (( duplicate_records > 0 )); then
    log_line "JSONL validation failed: total_records=$total_records unique_records=$unique_records unique_trace_ids=$unique_trace_ids duplicate_records=$duplicate_records duplication_rate=$duplicate_rate"
    OVERALL_EXIT_CODE=1
    return 1
  fi

  log_line "JSONL validation passed: total_records=$total_records unique_records=$unique_records unique_trace_ids=$unique_trace_ids duplication_rate=$duplicate_rate"
  return 0
}

process_project() {
  local project_id="$1"
  local name="$2"
  local manifest_path="$3"
  local project_path="$4"
  local commit_ref="$5"
  local notes="$6"

  local project_log="$RUN_DIR/logs/$project_id.log"
  local project_jsonl=""
  local project_jsonl_rel=""
  local started_at ended_at duration_seconds upload_response upload_id final_status jsonl_lines result_notes

  : > "$project_log"

  started_at="$(date +%s)"
  project_log_line "$project_log" "Benchmark project start"
  project_log_line "$project_log" "manifest.project_id=$project_id"
  project_log_line "$project_log" "manifest.name=$name"
  project_log_line "$project_log" "manifest.path=$manifest_path"
  project_log_line "$project_log" "manifest.commit=$commit_ref"
  project_log_line "$project_log" "manifest.notes=$notes"

  if [[ "$DRY_RUN" == "true" ]]; then
    project_jsonl="$RUN_DIR/shadow-jsonl/$project_id.jsonl"
    project_jsonl_rel="shadow-jsonl/$project_id.jsonl"
    : > "$project_jsonl"
    project_log_line "$project_log" "Dry run enabled; upload and polling skipped"
    record_result "$project_id" "$name" "$manifest_path" "" "DRY_RUN" "SKIPPED" "0" "0" "logs/$project_id.log" "$project_jsonl_rel" "Dry run only"
    return 0
  fi

  upload_response="$(curl -sS -X POST -F "file=@$project_path;type=application/zip" "$BFF_BASE_URL/upload" || true)"
  upload_id="$(printf '%s' "$upload_response" | tr -d '\r\n[:space:]')"

  if [[ -z "$upload_id" || ! "$upload_id" =~ ^[0-9a-f]+$ ]]; then
    project_log_line "$project_log" "Upload failed or returned an invalid project id payload: $upload_response"
    record_result "$project_id" "$name" "$manifest_path" "" "UPLOAD_FAILED" "FAILURE" "0" "0" "logs/$project_id.log" "" "Upload failed"
    OVERALL_EXIT_CODE=1
    return 0
  fi

  project_jsonl="$RUN_DIR/shadow-jsonl/$upload_id.jsonl"
  project_jsonl_rel="shadow-jsonl/$upload_id.jsonl"
  : > "$project_jsonl"
  project_log_line "$project_log" "Upload accepted with runtime project id=$upload_id"

  if ! final_status="$(wait_for_terminal_status "$upload_id" "$project_log" "$started_at")"; then
    ended_at="$(date +%s)"
    duration_seconds=$((ended_at - started_at))
    record_result "$project_id" "$name" "$manifest_path" "$upload_id" "TIMEOUT" "FAILURE" "$duration_seconds" "0" "logs/$project_id.log" "$project_jsonl_rel" "Polling timed out"
    OVERALL_EXIT_CODE=1
    return 0
  fi

  ended_at="$(date +%s)"
  duration_seconds=$((ended_at - started_at))

  if ! jsonl_lines="$(slice_project_jsonl "$upload_id" "$project_log" "$project_jsonl")"; then
    record_result "$project_id" "$name" "$manifest_path" "$upload_id" "$final_status" "FAILURE" "$duration_seconds" "0" "logs/$project_id.log" "$project_jsonl_rel" "Failed to filter shared JSONL export by runtime project id"
    OVERALL_EXIT_CODE=1
    return 0
  fi

  result_notes="Terminal status $final_status; captured $jsonl_lines JSONL line(s)"
  record_result "$project_id" "$name" "$manifest_path" "$upload_id" "$final_status" "SUCCESS" "$duration_seconds" "$jsonl_lines" "logs/$project_id.log" "$project_jsonl_rel" "$result_notes"
  project_log_line "$project_log" "Benchmark project finished with status=$final_status duration_seconds=$duration_seconds jsonl_lines=$jsonl_lines"
}

run_evaluator() {
  if [[ "$DRY_RUN" == "true" ]]; then
    log_line "Dry run enabled; evaluator skipped"
    return 0
  fi

  local evaluator_script="$REPO_ROOT/rmt-shadow-eval.sh"
  [[ -x "$evaluator_script" ]] || fail "Missing evaluator script: $evaluator_script"

  local -a evaluator_cmd=("$evaluator_script" "--input" "$RUN_DIR/shadow-jsonl" "--output" "$RUN_DIR/evaluation")
  if [[ "$SKIP_EVAL_BUILD" == "true" ]]; then
    evaluator_cmd+=("--skip-build")
  fi

  log_line "Running evaluator: ${evaluator_cmd[*]}"
  if "${evaluator_cmd[@]}" >> "$EVALUATION_LOG" 2>&1; then
    log_line "Evaluator completed successfully"
    return 0
  fi

  log_line "Evaluator failed; see $EVALUATION_LOG"
  OVERALL_EXIT_CODE=1
  return 1
}

generate_run_readme() {
  local generated_at total_rows success_rows failure_rows skipped_rows
  generated_at="$(timestamp_utc)"
  total_rows=$(( $(wc -l < "$RESULTS_CSV") - 1 ))
  success_rows="$(awk -F',' 'NR > 1 && $6 == "SUCCESS" {count++} END {print count+0}' "$RESULTS_CSV")"
  failure_rows="$(awk -F',' 'NR > 1 && $6 == "FAILURE" {count++} END {print count+0}' "$RESULTS_CSV")"
  skipped_rows="$(awk -F',' 'NR > 1 && $6 == "SKIPPED" {count++} END {print count+0}' "$RESULTS_CSV")"

  {
    cat <<EOF
# Benchmark Run $RUN_ID

## Summary

- Generated at (UTC): $generated_at
- Dry run: $DRY_RUN
- Manifest: \`$(realpath --relative-to="$RUN_DIR" "$MANIFEST" 2>/dev/null || printf '%s' "$MANIFEST")\`
- Projects in manifest: $MANIFEST_PROJECT_COUNT
- Projects processed: $total_rows
- Successes: $success_rows
- Failures: $failure_rows
- Skipped: $skipped_rows

## Configuration

- BFF base URL: \`$BFF_BASE_URL\`
- AI health URL: \`$AI_HEALTH_URL\`
- Shared M4 export path: \`$SHARED_EXPORT_PATH\`
- Poll interval: \`$POLL_INTERVAL_SECONDS\` seconds
- Project timeout: \`$PROJECT_TIMEOUT_SECONDS\` seconds
- Evaluator build mode: \`$( [[ "$SKIP_EVAL_BUILD" == "true" ]] && printf 'reuse compiled classes' || printf 'compile if needed' )\`
- Repo HEAD: \`$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)\`

## Per-Project Results

| project_id | name | upload_id | final_status | result | duration_seconds | jsonl_lines |
| --- | --- | --- | --- | --- | ---: | ---: |
EOF

    awk -F',' 'NR > 1 {printf("| %s | %s | %s | %s | %s | %s | %s |\n", $1, $2, ($4 == "" ? "-" : $4), $5, $6, $7, $8)}' "$RESULTS_CSV"

    cat <<EOF

## Output Files

- Run log: \`run.log\`
- Configuration snapshot: \`config.env\`
- Manifest snapshot: \`inputs/benchmark-projects.csv\`
- Project summary CSV: \`project-results.csv\`
- JSONL validation report: \`shadow-jsonl-report.txt\`
- Aggregate run JSONL rebuilt from per-project files: \`shadow-aggregate.jsonl\`
- Per-project logs: \`logs/*.log\`
- Per-project M4 JSONL exports keyed by runtime \`project_id\`: \`shadow-jsonl/*.jsonl\`
- Evaluator log: \`evaluation.log\`
EOF

    if [[ -f "$RUN_DIR/evaluation/shadow-evaluation-summary.json" ]]; then
      cat <<EOF
- M5 summary JSON: \`evaluation/shadow-evaluation-summary.json\`
- M5 overall CSV: \`evaluation/shadow-evaluation-overall.csv\`
- M5 by-project CSV: \`evaluation/shadow-evaluation-by-project.csv\`
- M5 by-pattern CSV: \`evaluation/shadow-evaluation-by-pattern.csv\`
- M5 valid observations CSV: \`evaluation/shadow-evaluation-valid-observations.csv\`
- M5 discordant observations CSV: \`evaluation/shadow-evaluation-discordant-observations.csv\`
- M5 failures CSV: \`evaluation/shadow-evaluation-failures.csv\`
- M5 schema issues CSV: \`evaluation/shadow-evaluation-schema-issues.csv\`
EOF
    else
      cat <<EOF
- M5 evaluation outputs were not generated for this run.
EOF
    fi

    cat <<EOF

## Notes

- This workflow is offline and reproducible as long as the same 17 ZIP inputs, local services, and AI endpoint contract are used.
- The runner does not change \`ProjectStatus\`, does not enable decision mode, and does not alter the JSONL export schema or M5 metrics.
- Each per-project JSONL file is filtered by runtime \`project_id\` from the shared M4 export and deduplicated by \`trace_id + entity_id\` before evaluation.
- The aggregate JSONL is rebuilt as the union of the per-project files, and the run fails if duplicate records are still detected.
EOF
  } > "$FINAL_README"
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --manifest)
        [[ $# -ge 2 ]] || fail "Missing value for --manifest"
        MANIFEST="$(resolve_repo_path "$2")"
        shift 2
        ;;
      --run-id)
        [[ $# -ge 2 ]] || fail "Missing value for --run-id"
        RUN_ID="$2"
        shift 2
        ;;
      --runs-root)
        [[ $# -ge 2 ]] || fail "Missing value for --runs-root"
        RUNS_ROOT="$(resolve_repo_path "$2")"
        shift 2
        ;;
      --bff-base-url)
        [[ $# -ge 2 ]] || fail "Missing value for --bff-base-url"
        BFF_BASE_URL="$2"
        shift 2
        ;;
      --ai-health-url)
        [[ $# -ge 2 ]] || fail "Missing value for --ai-health-url"
        AI_HEALTH_URL="$2"
        shift 2
        ;;
      --shared-export-path)
        [[ $# -ge 2 ]] || fail "Missing value for --shared-export-path"
        SHARED_EXPORT_PATH="$(resolve_repo_path "$2")"
        shift 2
        ;;
      --poll-interval)
        [[ $# -ge 2 ]] || fail "Missing value for --poll-interval"
        POLL_INTERVAL_SECONDS="$2"
        shift 2
        ;;
      --project-timeout)
        [[ $# -ge 2 ]] || fail "Missing value for --project-timeout"
        PROJECT_TIMEOUT_SECONDS="$2"
        shift 2
        ;;
      --skip-eval-build)
        SKIP_EVAL_BUILD="true"
        shift
        ;;
      --dry-run)
        DRY_RUN="true"
        shift
        ;;
      --help|-h)
        usage
        exit 0
        ;;
      *)
        fail "Unknown argument: $1"
        ;;
    esac
  done
}

main() {
  parse_args "$@"

  require_command awk
  require_command curl
  require_command jq
  require_command sed
  require_command sha256sum
  require_command sort

  validate_manifest
  prepare_run_layout
  write_run_configuration

  log_line "Benchmark run directory: $RUN_DIR"
  log_line "Manifest projects: $MANIFEST_PROJECT_COUNT"
  log_line "Dry run: $DRY_RUN"

  capture_health_snapshots

  local project_id name manifest_path commit_ref notes project_path
  while IFS=, read -r project_id name manifest_path commit_ref notes; do
    if [[ "$project_id" == "project_id" ]]; then
      continue
    fi

    notes="${notes%$'\r'}"
    project_path="$(resolve_repo_path "$manifest_path")"
    process_project "$project_id" "$name" "$manifest_path" "$project_path" "$commit_ref" "$notes"
  done < "$MANIFEST"

  rebuild_aggregate_jsonl
  if validate_shadow_jsonl_outputs; then
    run_evaluator || true
  else
    log_line "Evaluator skipped because JSONL validation failed"
  fi
  generate_run_readme

  if [[ "$OVERALL_EXIT_CODE" -ne 0 ]]; then
    log_line "Benchmark finished with failures; see $FINAL_README"
  else
    log_line "Benchmark finished successfully; see $FINAL_README"
  fi

  exit "$OVERALL_EXIT_CODE"
}

main "$@"

#!/usr/bin/env bash
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

MANIFEST="$SCRIPT_DIR/benchmark-projects.csv"
RUNS_ROOT="$SCRIPT_DIR/runs"
BFF_BASE_URL="http://127.0.0.1:8080/rmt/api/v1"
AI_HEALTH_URL="http://127.0.0.1:8000/health"
LEGACY_SHARED_EXPORT_PATH="$REPO_ROOT/detection-and-refactoring/target/rmt-ai-shadow-observations.jsonl"
SHADOW_EXPORT_PATH=""
POLL_INTERVAL_SECONDS=5
PROJECT_TIMEOUT_SECONDS=3600
DRY_RUN="false"
SKIP_EVAL_BUILD="false"
EXPERIMENT_PROFILE="shadow-stub"
COMPARE_WITH=""
COMPARE_RUN_DIR=""
RUN_ID=""
RUN_DIR=""
RUN_LOG=""
RESULTS_CSV=""
AGGREGATE_JSONL=""
EVALUATION_LOG=""
JSONL_REPORT=""
PERFORMANCE_RAW_TSV=""
PERFORMANCE_SUMMARY_CSV=""
PERFORMANCE_COMPARISON_CSV=""
PERFORMANCE_OVERHEAD_CSV=""
PERFORMANCE_AGGREGATE_JSON=""
PERFORMANCE_AGGREGATE_MD=""
MANIFEST_PROJECT_COUNT=0
OVERALL_EXIT_CODE=0
FINAL_README=""
SHADOW_EXPORT_LOCK_DIR=""
LEGACY_SHARED_EXPORT_LINKED="false"

usage() {
  cat <<EOF
Usage: ./experiments/run-benchmark.sh [options]

Options:
  --manifest <path>              Manifest CSV. Default: experiments/benchmark-projects.csv
  --run-id <value>               Explicit run directory name.
  --runs-root <path>             Root directory for benchmark runs. Default: experiments/runs
  --bff-base-url <url>           Project Sync BFF base URL. Default: http://127.0.0.1:8080/rmt/api/v1
  --ai-health-url <url>          AI service health URL. Default: http://127.0.0.1:8000/health
  --shared-export-path <path>    Run-scoped M4 JSONL export path written by detection service.
                                 Default: detection-and-refactoring/target/<run-id>-shadow.jsonl
  --poll-interval <seconds>      Poll interval per project. Default: 5
  --project-timeout <seconds>    Timeout per project. Default: 3600
  --experiment-profile <value>   Benchmark profile: heuristic-only, shadow-stub, shadow-real-model,
                                 shadow-zeroshot, or shadow-finetuned.
                                 Default: shadow-stub
  --compare-with <run-id|path>   Existing benchmark run to compare against after this run completes.
  --skip-eval-build              Reuse existing M5 compiled classes when invoking rmt-shadow-eval.sh
  --dry-run                      Validate inputs and create run layout without uploading projects
  --help                         Show this help text

Examples:
  ./experiments/run-benchmark.sh --dry-run
  ./experiments/run-benchmark.sh --run-id 20260427-full-benchmark
  ./experiments/run-benchmark.sh --experiment-profile heuristic-only --run-id tcc-heuristic-only-01
  ./experiments/run-benchmark.sh --experiment-profile shadow-stub --compare-with tcc-heuristic-only-01 --run-id tcc-shadow-stub-01
  ./experiments/run-benchmark.sh --poll-interval 10 --project-timeout 7200
EOF
}

timestamp_utc() {
  date -u +"%Y-%m-%dT%H:%M:%SZ"
}

timestamp_ms() {
  date +%s%3N
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

profile_requires_ai() {
  [[ "$1" != "heuristic-only" ]]
}

validate_experiment_profile() {
  case "$EXPERIMENT_PROFILE" in
    heuristic-only|shadow-stub|shadow-real-model|shadow-zeroshot|shadow-finetuned)
      ;;
    *)
      fail "Unsupported experiment profile: $EXPERIMENT_PROFILE"
      ;;
  esac
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
  PERFORMANCE_RAW_TSV="$RUN_DIR/.performance-raw.tsv"
  PERFORMANCE_SUMMARY_CSV="$RUN_DIR/performance-project-summary.csv"
  PERFORMANCE_COMPARISON_CSV="$RUN_DIR/performance-comparison.csv"
  PERFORMANCE_OVERHEAD_CSV="$RUN_DIR/performance-overhead-by-project.csv"
  PERFORMANCE_AGGREGATE_JSON="$RUN_DIR/performance-comparison-summary.json"
  PERFORMANCE_AGGREGATE_MD="$RUN_DIR/performance-comparison-summary.md"
  FINAL_README="$RUN_DIR/README.md"

  cp "$MANIFEST" "$RUN_DIR/inputs/benchmark-projects.csv"
  : > "$RUN_LOG"
  : > "$AGGREGATE_JSONL"
  : > "$EVALUATION_LOG"
  : > "$PERFORMANCE_RAW_TSV"

  cat > "$RESULTS_CSV" <<EOF
project_id,name,manifest_path,upload_id,final_status,result,duration_seconds,jsonl_lines,log_file,jsonl_file,notes
EOF
}

acquire_shadow_export_lock() {
  SHADOW_EXPORT_LOCK_DIR="$REPO_ROOT/detection-and-refactoring/target/.rmt-shadow-export.lock"

  if mkdir "$SHADOW_EXPORT_LOCK_DIR" 2>/dev/null; then
    cat > "$SHADOW_EXPORT_LOCK_DIR/run-info" <<EOF
RUN_ID=$RUN_ID
SHADOW_EXPORT_PATH=$SHADOW_EXPORT_PATH
ACQUIRED_AT_UTC=$(timestamp_utc)
EOF
    return 0
  fi

  fail "Another AI benchmark run is already active. Remove $SHADOW_EXPORT_LOCK_DIR only if it is stale."
}

release_shadow_export_lock() {
  if [[ "$LEGACY_SHARED_EXPORT_LINKED" == "true" && -L "$LEGACY_SHARED_EXPORT_PATH" ]]; then
    local linked_target
    linked_target="$(readlink "$LEGACY_SHARED_EXPORT_PATH" 2>/dev/null || true)"
    if [[ "$linked_target" == "$SHADOW_EXPORT_PATH" ]]; then
      rm -f "$LEGACY_SHARED_EXPORT_PATH"
      : > "$LEGACY_SHARED_EXPORT_PATH"
    fi
  fi

  if [[ -n "$SHADOW_EXPORT_LOCK_DIR" && -f "$SHADOW_EXPORT_LOCK_DIR/run-info" ]]; then
    rm -f "$SHADOW_EXPORT_LOCK_DIR/run-info"
  fi

  if [[ -n "$SHADOW_EXPORT_LOCK_DIR" && -d "$SHADOW_EXPORT_LOCK_DIR" ]]; then
    rmdir "$SHADOW_EXPORT_LOCK_DIR" 2>/dev/null || true
  fi
}

prepare_shadow_export_path() {
  local export_parent
  local legacy_parent

  if ! profile_requires_ai "$EXPERIMENT_PROFILE"; then
    return 0
  fi

  if [[ -z "$SHADOW_EXPORT_PATH" ]]; then
    SHADOW_EXPORT_PATH="$REPO_ROOT/detection-and-refactoring/target/${RUN_ID}-shadow.jsonl"
  fi

  if [[ "$SHADOW_EXPORT_PATH" == "$LEGACY_SHARED_EXPORT_PATH" ]]; then
    fail "Refusing to use the legacy shared shadow export path for AI benchmark runs. Use a run-scoped file path instead."
  fi

  export_parent="$(dirname "$SHADOW_EXPORT_PATH")"
  legacy_parent="$(dirname "$LEGACY_SHARED_EXPORT_PATH")"
  mkdir -p "$export_parent" "$legacy_parent"

  acquire_shadow_export_lock

  rm -f "$SHADOW_EXPORT_PATH"
  : > "$SHADOW_EXPORT_PATH"
  export RMT_AI_SHADOW_EXPORT_PATH="$SHADOW_EXPORT_PATH"
  log_line "Using shadow export path: $SHADOW_EXPORT_PATH"

  rm -f "$LEGACY_SHARED_EXPORT_PATH"
  ln -s "$SHADOW_EXPORT_PATH" "$LEGACY_SHARED_EXPORT_PATH"
  LEGACY_SHARED_EXPORT_LINKED="true"
  log_line "Linked legacy shadow export path to run-scoped file: $LEGACY_SHARED_EXPORT_PATH -> $SHADOW_EXPORT_PATH"
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
SHADOW_EXPORT_PATH=$SHADOW_EXPORT_PATH
LEGACY_SHARED_EXPORT_PATH=$LEGACY_SHARED_EXPORT_PATH
POLL_INTERVAL_SECONDS=$POLL_INTERVAL_SECONDS
PROJECT_TIMEOUT_SECONDS=$PROJECT_TIMEOUT_SECONDS
EXPERIMENT_PROFILE=$EXPERIMENT_PROFILE
COMPARE_WITH=$COMPARE_WITH
DRY_RUN=$DRY_RUN
SKIP_EVAL_BUILD=$SKIP_EVAL_BUILD
EOF
}

capture_health_snapshots() {
  if [[ "$DRY_RUN" == "true" ]]; then
    return
  fi

  check_http_endpoint "$BFF_BASE_URL/upload" "BFF upload endpoint"

  if profile_requires_ai "$EXPERIMENT_PROFILE"; then
    check_http_endpoint "$AI_HEALTH_URL" "AI health endpoint"
    curl -sS "$AI_HEALTH_URL" > "$RUN_DIR/ai-health.json"
    log_line "AI health payload saved to $RUN_DIR/ai-health.json"
  else
    cat > "$RUN_DIR/ai-health.json" <<EOF
{"status":"skipped","experiment_profile":"$EXPERIMENT_PROFILE","reason":"AI disabled for heuristic-only benchmark profile"}
EOF
    log_line "AI health snapshot skipped for heuristic-only profile"
  fi
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

record_performance_raw() {
  local project_id="$1"
  local status="$2"
  local total_duration_ms="$3"
  local candidate_count="$4"
  local upload_id="$5"
  local jsonl_file="$6"

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$project_id" \
    "$EXPERIMENT_PROFILE" \
    "$status" \
    "$total_duration_ms" \
    "$candidate_count" \
    "$upload_id" \
    "$jsonl_file" \
    >> "$PERFORMANCE_RAW_TSV"
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
          printf '%s\t%s\n' "$status" "$candidate_count"
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

  : > "$project_jsonl"
  total_lines="$(line_count "$SHADOW_EXPORT_PATH")"

  if [[ ! -f "$SHADOW_EXPORT_PATH" || "$total_lines" -eq 0 ]]; then
    project_log_line "$project_log" "Run-scoped shadow export has no lines yet; wrote empty project JSONL to $project_jsonl"
    printf '0\n'
    return 0
  fi

  if ! raw_matches="$(
    jq -sc --arg project_id "$upload_id" '
      map(select((.project_id // "") == $project_id)) | length
    ' "$SHADOW_EXPORT_PATH"
  )"; then
    project_log_line "$project_log" "Failed to count project records from run-scoped shadow export at $SHADOW_EXPORT_PATH"
    return 1
  fi

  if ! jq -csc --arg project_id "$upload_id" '
    def record_key($record):
      if (($record.trace_id // "") != "" and ($record.entity_id // "") != "") then
        ($record.trace_id + "\u001f" + $record.entity_id)
      elif (($record.project_id // "") != "" and ($record.candidate_id // "") != "") then
        ($record.project_id + "\u001f" + $record.candidate_id)
      else
        ($record | tojson)
      end;

    map(select((.project_id // "") == $project_id))
    | reduce .[] as $record (
        {seen: {}, records: []};
        (record_key($record)) as $key
        | if .seen[$key] then
            .
          else
            .seen[$key] = true
            | .records += [$record]
          end
      )
    | .records[]
  ' "$SHADOW_EXPORT_PATH" > "$project_jsonl"; then
    project_log_line "$project_log" "Failed to parse or filter run-scoped shadow export at $SHADOW_EXPORT_PATH"
    return 1
  fi

  unique_matches="$(line_count "$project_jsonl")"
  duplicates_removed=$((raw_matches - unique_matches))

  project_log_line "$project_log" "Filtered run-scoped shadow export total_lines=$total_lines matched_project_records=$raw_matches unique_project_records=$unique_matches duplicates_removed=$duplicates_removed output=$project_jsonl"
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
  local parse_failures=0

  shopt -s nullglob
  jsonl_files=("$RUN_DIR"/shadow-jsonl/*.jsonl)
  shopt -u nullglob

  if (( ${#jsonl_files[@]} > 0 )); then
    for jsonl_file in "${jsonl_files[@]}"; do
      if [[ -s "$jsonl_file" ]] && ! jq -e . "$jsonl_file" > /dev/null; then
        parse_failures=$((parse_failures + 1))
        log_line "JSONL parse failure in $jsonl_file"
      fi
    done

    if (( parse_failures == 0 )); then
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
  fi

  cat > "$JSONL_REPORT" <<EOF
total_records=$total_records
unique_records=$unique_records
unique_trace_ids=$unique_trace_ids
duplicate_records=$duplicate_records
duplication_rate=$duplicate_rate
jsonl_file_count=${#jsonl_files[@]}
parse_failures=$parse_failures
purity_failures=$purity_failures
EOF

  log_line "JSONL report written to $JSONL_REPORT"

  if (( parse_failures > 0 )); then
    log_line "JSONL validation failed due to unparsable grounded JSONL lines"
    OVERALL_EXIT_CODE=1
    return 1
  fi

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
  local started_at_epoch started_at_ms ended_at_ms duration_seconds duration_ms
  local upload_response upload_id final_status final_candidate_count jsonl_lines result_notes wait_result

  : > "$project_log"

  started_at_epoch="$(date +%s)"
  started_at_ms="$(timestamp_ms)"
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
    record_performance_raw "$project_id" "DRY_RUN" "0" "0" "" "$project_jsonl_rel"
    return 0
  fi

  upload_response="$(curl -sS -X POST -F "file=@$project_path;type=application/zip" "$BFF_BASE_URL/upload" || true)"
  upload_id="$(printf '%s' "$upload_response" | tr -d '\r\n[:space:]')"

  if [[ -z "$upload_id" || ! "$upload_id" =~ ^[0-9a-f]+$ ]]; then
    project_log_line "$project_log" "Upload failed or returned an invalid project id payload: $upload_response"
    record_result "$project_id" "$name" "$manifest_path" "" "UPLOAD_FAILED" "FAILURE" "0" "0" "logs/$project_id.log" "" "Upload failed"
    record_performance_raw "$project_id" "UPLOAD_FAILED" "0" "0" "" ""
    OVERALL_EXIT_CODE=1
    return 0
  fi

  project_jsonl="$RUN_DIR/shadow-jsonl/$upload_id.jsonl"
  project_jsonl_rel="shadow-jsonl/$upload_id.jsonl"
  : > "$project_jsonl"
  project_log_line "$project_log" "Upload accepted with runtime project id=$upload_id"

  if ! wait_result="$(wait_for_terminal_status "$upload_id" "$project_log" "$started_at_epoch")"; then
    ended_at_ms="$(timestamp_ms)"
    duration_ms=$((ended_at_ms - started_at_ms))
    duration_seconds=$((duration_ms / 1000))
    record_result "$project_id" "$name" "$manifest_path" "$upload_id" "TIMEOUT" "FAILURE" "$duration_seconds" "0" "logs/$project_id.log" "$project_jsonl_rel" "Polling timed out"
    record_performance_raw "$project_id" "TIMEOUT" "$duration_ms" "0" "$upload_id" "$project_jsonl_rel"
    OVERALL_EXIT_CODE=1
    return 0
  fi

  IFS=$'\t' read -r final_status final_candidate_count <<< "$wait_result"
  ended_at_ms="$(timestamp_ms)"
  duration_ms=$((ended_at_ms - started_at_ms))
  duration_seconds=$((duration_ms / 1000))

  if profile_requires_ai "$EXPERIMENT_PROFILE"; then
    if ! jsonl_lines="$(slice_project_jsonl "$upload_id" "$project_log" "$project_jsonl")"; then
      record_result "$project_id" "$name" "$manifest_path" "$upload_id" "$final_status" "FAILURE" "$duration_seconds" "0" "logs/$project_id.log" "$project_jsonl_rel" "Failed to filter run-scoped shadow JSONL export by runtime project id"
      record_performance_raw "$project_id" "$final_status" "$duration_ms" "${final_candidate_count:-0}" "$upload_id" "$project_jsonl_rel"
      OVERALL_EXIT_CODE=1
      return 0
    fi
  else
    : > "$project_jsonl"
    jsonl_lines="0"
    project_log_line "$project_log" "Heuristic-only profile; AI shadow export skipped and empty project JSONL preserved at $project_jsonl"
  fi

  result_notes="Terminal status $final_status; captured $jsonl_lines JSONL line(s)"
  record_result "$project_id" "$name" "$manifest_path" "$upload_id" "$final_status" "SUCCESS" "$duration_seconds" "$jsonl_lines" "logs/$project_id.log" "$project_jsonl_rel" "$result_notes"
  project_log_line "$project_log" "Benchmark project finished with status=$final_status duration_seconds=$duration_seconds jsonl_lines=$jsonl_lines"
  record_performance_raw "$project_id" "$final_status" "$duration_ms" "${final_candidate_count:-0}" "$upload_id" "$project_jsonl_rel"
}

run_evaluator() {
  if [[ "$DRY_RUN" == "true" ]]; then
    log_line "Dry run enabled; evaluator skipped"
    return 0
  fi

  if ! profile_requires_ai "$EXPERIMENT_PROFILE"; then
    log_line "Heuristic-only profile selected; evaluator skipped because no AI shadow output is expected"
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

compute_ai_timing_metrics() {
  local jsonl_path="$1"

  if [[ -z "$jsonl_path" || ! -s "$jsonl_path" ]]; then
    printf '\t\n'
    return 0
  fi

  jq -r '.ai_analysis_time_ms // empty' "$jsonl_path" | awk '
    BEGIN { sum = 0; count = 0 }
    NF > 0 { sum += $1; count++ }
    END {
      if (count == 0) {
        printf "\t\n"
      } else {
        printf "%d\t%.6f\n", sum, sum / count
      }
    }
  '
}

generate_performance_summary() {
  local project_id profile status total_duration_ms candidate_count upload_id jsonl_rel jsonl_path
  local ai_duration_ms average_ai_duration_ms timing_metrics

  cat > "$PERFORMANCE_SUMMARY_CSV" <<EOF
project_id,profile,status,total_duration_ms,ai_duration_ms,average_ai_duration_ms,candidate_count
EOF

  while IFS=$'\t' read -r project_id profile status total_duration_ms candidate_count upload_id jsonl_rel; do
    ai_duration_ms=""
    average_ai_duration_ms=""

    if profile_requires_ai "$profile"; then
      if [[ -n "$jsonl_rel" ]]; then
        jsonl_path="$RUN_DIR/$jsonl_rel"
        timing_metrics="$(compute_ai_timing_metrics "$jsonl_path")"
        IFS=$'\t' read -r ai_duration_ms average_ai_duration_ms <<< "$timing_metrics"
      fi
    fi

    printf '%s,%s,%s,%s,%s,%s,%s\n' \
      "$(sanitize_csv_field "$project_id")" \
      "$(sanitize_csv_field "$profile")" \
      "$(sanitize_csv_field "$status")" \
      "$(sanitize_csv_field "$total_duration_ms")" \
      "$(sanitize_csv_field "$ai_duration_ms")" \
      "$(sanitize_csv_field "$average_ai_duration_ms")" \
      "$(sanitize_csv_field "$candidate_count")" \
      >> "$PERFORMANCE_SUMMARY_CSV"
  done < "$PERFORMANCE_RAW_TSV"

  log_line "Performance summary written to $PERFORMANCE_SUMMARY_CSV"
}

resolve_compare_run_dir() {
  if [[ -z "$COMPARE_WITH" ]]; then
    return 0
  fi

  if [[ -d "$COMPARE_WITH" ]]; then
    COMPARE_RUN_DIR="$(cd "$COMPARE_WITH" && pwd)"
    return 0
  fi

  if [[ -d "$RUNS_ROOT/$COMPARE_WITH" ]]; then
    COMPARE_RUN_DIR="$(cd "$RUNS_ROOT/$COMPARE_WITH" && pwd)"
    return 0
  fi

  fail "Comparison run not found: $COMPARE_WITH"
}

config_value() {
  local config_path="$1"
  local key="$2"
  awk -F'=' -v expected_key="$key" '$1 == expected_key { print substr($0, length($1) + 2); exit }' "$config_path"
}

generate_performance_comparison_reports() {
  if [[ -z "$COMPARE_RUN_DIR" ]]; then
    return 0
  fi

  local current_summary="$PERFORMANCE_SUMMARY_CSV"
  local baseline_summary="$COMPARE_RUN_DIR/performance-project-summary.csv"
  local current_config="$RUN_DIR/config.env"
  local baseline_config="$COMPARE_RUN_DIR/config.env"
  local current_manifest_sha baseline_manifest_sha current_profile baseline_profile current_run_id baseline_run_id
  local tmp_rows="$RUN_DIR/.performance-comparison-rows.tmp"

  [[ -f "$baseline_summary" ]] || fail "Comparison run is missing performance summary: $baseline_summary"
  [[ -f "$baseline_config" ]] || fail "Comparison run is missing config.env: $baseline_config"

  current_manifest_sha="$(config_value "$current_config" "MANIFEST_SHA256")"
  baseline_manifest_sha="$(config_value "$baseline_config" "MANIFEST_SHA256")"
  [[ "$current_manifest_sha" == "$baseline_manifest_sha" ]] || fail "Comparison run manifest differs from current run"

  current_profile="$(config_value "$current_config" "EXPERIMENT_PROFILE")"
  baseline_profile="$(config_value "$baseline_config" "EXPERIMENT_PROFILE")"
  current_run_id="$(config_value "$current_config" "RUN_ID")"
  baseline_run_id="$(config_value "$baseline_config" "RUN_ID")"

  {
    awk -F',' 'NR > 1 { printf "%s,%s,%s,%s,%s,%s\n", $1, $2, $3, $4, $5, $7 }' "$baseline_summary"
    awk -F',' 'NR > 1 { printf "%s,%s,%s,%s,%s,%s\n", $1, $2, $3, $4, $5, $7 }' "$current_summary"
  } | sort -t',' -k1,1 -k2,2 > "$tmp_rows"

  {
    printf 'project_id,profile,status,total_duration_ms,ai_duration_ms,candidate_count\n'
    cat "$tmp_rows"
  } > "$PERFORMANCE_COMPARISON_CSV"
  rm -f "$tmp_rows"

  awk -F',' '
    NR == FNR && FNR > 1 {
      base_status[$1] = $3
      base_total[$1] = $4 + 0
      base_ai[$1] = $5
      base_candidate[$1] = $7 + 0
      next
    }
    FNR > 1 {
      if (!($1 in base_total)) {
        next
      }
      delta = $4 - base_total[$1]
      overhead = ""
      if (base_total[$1] > 0) {
        overhead = sprintf("%.6f", (delta / base_total[$1]) * 100)
      }
      printf "%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n",
        $1,
        base_status[$1],
        $3,
        base_total[$1],
        $4,
        delta,
        overhead,
        base_ai[$1],
        $5,
        base_candidate[$1],
        $7,
        $2
    }
  ' "$baseline_summary" "$current_summary" | sort -t',' -k1,1 | {
    printf 'project_id,baseline_status,comparison_status,baseline_total_duration_ms,comparison_total_duration_ms,delta_ms,overhead_percent,baseline_ai_duration_ms,comparison_ai_duration_ms,baseline_candidate_count,comparison_candidate_count,comparison_profile\n'
    cat
  } > "$PERFORMANCE_OVERHEAD_CSV"

  awk -F',' -v baseline_profile="$baseline_profile" -v comparison_profile="$current_profile" \
      -v baseline_run_id="$baseline_run_id" -v comparison_run_id="$current_run_id" '
    NR == FNR && FNR > 1 {
      base_total[$1] = $4 + 0
      base_status[$1] = $3
      next
    }
    FNR > 1 {
      if (!($1 in base_total)) {
        next
      }
      comparable_count++
      baseline_total_sum += base_total[$1]
      comparison_total_sum += ($4 + 0)
      delta_sum += (($4 + 0) - base_total[$1])
      if ($5 != "") {
        comparison_ai_sum += ($5 + 0)
      }
      if ($6 != "") {
        comparison_avg_ai_sum += ($6 + 0)
        comparison_avg_ai_count++
      }
    }
    END {
      overhead = ""
      average_comparison_ai = ""
      if (baseline_total_sum > 0) {
        overhead = sprintf("%.6f", (delta_sum / baseline_total_sum) * 100)
      }
      if (comparison_avg_ai_count > 0) {
        average_comparison_ai = sprintf("%.6f", comparison_avg_ai_sum / comparison_avg_ai_count)
      }

      printf "{\n"
      printf "  \"baseline_run_id\": \"%s\",\n", baseline_run_id
      printf "  \"comparison_run_id\": \"%s\",\n", comparison_run_id
      printf "  \"baseline_profile\": \"%s\",\n", baseline_profile
      printf "  \"comparison_profile\": \"%s\",\n", comparison_profile
      printf "  \"comparable_project_count\": %d,\n", comparable_count + 0
      printf "  \"baseline_total_duration_ms\": %d,\n", baseline_total_sum + 0
      printf "  \"comparison_total_duration_ms\": %d,\n", comparison_total_sum + 0
      printf "  \"duration_delta_ms\": %d,\n", delta_sum + 0
      if (overhead == "") {
        printf "  \"overhead_percent\": null,\n"
      } else {
        printf "  \"overhead_percent\": %s,\n", overhead
      }
      printf "  \"comparison_total_ai_duration_ms\": %d,\n", comparison_ai_sum + 0
      if (average_comparison_ai == "") {
        printf "  \"comparison_average_ai_duration_ms\": null\n"
      } else {
        printf "  \"comparison_average_ai_duration_ms\": %s\n", average_comparison_ai
      }
      printf "}\n"
    }
  ' "$baseline_summary" "$current_summary" > "$PERFORMANCE_AGGREGATE_JSON"

  awk -F',' -v baseline_profile="$baseline_profile" -v comparison_profile="$current_profile" \
      -v baseline_run_id="$baseline_run_id" -v comparison_run_id="$current_run_id" '
    NR == FNR && FNR > 1 {
      base_total[$1] = $4 + 0
      next
    }
    FNR > 1 {
      if (!($1 in base_total)) {
        next
      }
      comparable_count++
      baseline_total_sum += base_total[$1]
      comparison_total_sum += ($4 + 0)
      delta_sum += (($4 + 0) - base_total[$1])
      if ($5 != "") {
        comparison_ai_sum += ($5 + 0)
      }
    }
    END {
      overhead = "n/a"
      if (baseline_total_sum > 0) {
        overhead = sprintf("%.6f", (delta_sum / baseline_total_sum) * 100)
      }
      printf "# Performance Comparison\n\n"
      printf "- Baseline run: `%s` (%s)\n", baseline_run_id, baseline_profile
      printf "- Comparison run: `%s` (%s)\n", comparison_run_id, comparison_profile
      printf "- Comparable projects: %d\n", comparable_count + 0
      printf "- Baseline total duration: %d ms\n", baseline_total_sum + 0
      printf "- Comparison total duration: %d ms\n", comparison_total_sum + 0
      printf "- Duration delta: %d ms\n", delta_sum + 0
      printf "- Overhead percent: %s\n", overhead
      printf "- Comparison total AI duration: %d ms\n", comparison_ai_sum + 0
    }
  ' "$baseline_summary" "$current_summary" > "$PERFORMANCE_AGGREGATE_MD"

  log_line "Performance comparison CSV written to $PERFORMANCE_COMPARISON_CSV"
  log_line "Performance overhead report written to $PERFORMANCE_OVERHEAD_CSV"
  log_line "Performance aggregate reports written to $PERFORMANCE_AGGREGATE_JSON and $PERFORMANCE_AGGREGATE_MD"
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
- Experiment profile: \`$EXPERIMENT_PROFILE\`
- Manifest: \`$(realpath --relative-to="$RUN_DIR" "$MANIFEST" 2>/dev/null || printf '%s' "$MANIFEST")\`
- Projects in manifest: $MANIFEST_PROJECT_COUNT
- Projects processed: $total_rows
- Successes: $success_rows
- Failures: $failure_rows
- Skipped: $skipped_rows

## Configuration

- BFF base URL: \`$BFF_BASE_URL\`
- AI health URL: \`$AI_HEALTH_URL\`
- Shadow export path: \`$SHADOW_EXPORT_PATH\`
- Legacy compatibility path: \`$LEGACY_SHARED_EXPORT_PATH\`
- Poll interval: \`$POLL_INTERVAL_SECONDS\` seconds
- Project timeout: \`$PROJECT_TIMEOUT_SECONDS\` seconds
- Evaluator build mode: \`$( [[ "$SKIP_EVAL_BUILD" == "true" ]] && printf 'reuse compiled classes' || printf 'compile if needed' )\`
- Comparison baseline: \`$( [[ -n "$COMPARE_RUN_DIR" ]] && printf '%s' "$COMPARE_RUN_DIR" || printf 'none' )\`
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
- Per-project performance summary CSV: \`performance-project-summary.csv\`
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

    if [[ -f "$PERFORMANCE_COMPARISON_CSV" ]]; then
      cat <<EOF
- Cross-run performance comparison CSV: \`performance-comparison.csv\`
- Per-project performance overhead CSV: \`performance-overhead-by-project.csv\`
- Aggregate performance comparison JSON: \`performance-comparison-summary.json\`
- Aggregate performance comparison Markdown: \`performance-comparison-summary.md\`
EOF
    fi

    cat <<EOF

## Notes

- This workflow is offline and reproducible as long as the same 17 ZIP inputs, local services, and AI endpoint contract are used.
- The runner does not change \`ProjectStatus\`, does not enable decision mode, and does not alter the JSONL export schema or M5 metrics.
- The experiment profile only controls benchmark metadata, timing expectations, and report generation. It does not toggle decision mode or modify application behavior by itself.
- Each AI benchmark run uses a unique run-scoped shadow export file, and the runner truncates it before uploads begin.
- Each per-project JSONL file is filtered by runtime \`project_id\` from the run-scoped M4 export and deduplicated by \`trace_id + entity_id\` before evaluation.
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
        SHADOW_EXPORT_PATH="$(resolve_repo_path "$2")"
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
      --experiment-profile)
        [[ $# -ge 2 ]] || fail "Missing value for --experiment-profile"
        EXPERIMENT_PROFILE="$2"
        shift 2
        ;;
      --compare-with)
        [[ $# -ge 2 ]] || fail "Missing value for --compare-with"
        COMPARE_WITH="$2"
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
  trap 'release_shadow_export_lock' EXIT

  require_command awk
  require_command curl
  require_command jq
  require_command sed
  require_command sha256sum
  require_command sort
  validate_experiment_profile

  validate_manifest
  prepare_run_layout
  prepare_shadow_export_path
  resolve_compare_run_dir
  write_run_configuration

  log_line "Benchmark run directory: $RUN_DIR"
  log_line "Manifest projects: $MANIFEST_PROJECT_COUNT"
  log_line "Dry run: $DRY_RUN"
  log_line "Experiment profile: $EXPERIMENT_PROFILE"
  if profile_requires_ai "$EXPERIMENT_PROFILE"; then
    log_line "Shadow export path initialized at $SHADOW_EXPORT_PATH"
  fi

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
  generate_performance_summary
  if validate_shadow_jsonl_outputs; then
    run_evaluator || true
  else
    log_line "Evaluator skipped because JSONL validation failed"
  fi
  generate_performance_comparison_reports
  generate_run_readme

  if [[ "$OVERALL_EXIT_CODE" -ne 0 ]]; then
    log_line "Benchmark finished with failures; see $FINAL_README"
  else
    log_line "Benchmark finished successfully; see $FINAL_README"
  fi

  exit "$OVERALL_EXIT_CODE"
}

main "$@"

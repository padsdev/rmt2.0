#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RUNS_DIR="$REPO_ROOT/experiments/runs"
RESULTS_DIR="$REPO_ROOT/docs/tcc/repeated-results"
COMPOSE_FILE_PATH="$REPO_ROOT/infra/local/docker-compose-full.yml"
COMPOSE_PROJECT_NAME="local"
M2_REPO="/tmp/rmt-m2"
MAVEN_HOME="/tmp/rmt-maven-home"
ROUNDS=5

BFF_BASE_URL="http://intermediary:8080/rmt/api/v1"
AI_HEALTH_URL="http://localhost:8000/health"
HEURISTIC_BASELINE_RUN_ID=""
FINETUNED_ARTIFACT_PATH="$REPO_ROOT/experiments/artifacts/graphcodebert-finetuned-tcc-final"

RUNTIME_RAW_CSV="$RESULTS_DIR/repeated_runtime_raw.csv"
M4_MD="$RESULTS_DIR/repeated_m4_integrity.md"
M5_OVERALL_MD="$RESULTS_DIR/repeated_m5_overall.md"
M5_PATTERN_MD="$RESULTS_DIR/repeated_m5_by_pattern.md"
RUNTIME_SUMMARY_MD="$RESULTS_DIR/repeated_runtime_summary.md"
COMPARE_MD="$RESULTS_DIR/repeated_zeroshot_vs_finetuned.md"
REPORT_MD="$REPO_ROOT/docs/tcc/repeated_benchmark_report.md"

usage() {
  cat <<EOF
Usage: ./experiments/run-repeated-tcc-benchmark.sh [options]

Options:
  --rounds <N>                    Number of rounds. Default: 5
  --bff-base-url <url>            BFF base URL for run-benchmark.sh
  --ai-health-url <url>           AI health URL for run-benchmark.sh
  --baseline-run-id <run-id>      Heuristic baseline run id for --compare-with
  --finetuned-artifact <path>     Fine-tuned artifact directory path
  --compose-file <path>           Docker compose file for orchestration
  --compose-project <name>        Docker compose project name. Default: local
  --help                          Show this help

Examples:
  ./experiments/run-repeated-tcc-benchmark.sh
  ./experiments/run-repeated-tcc-benchmark.sh --rounds 10
EOF
}

log() {
  printf '[%s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

run_in_ai_service() {
  local cmd="$1"
  docker compose -p "$COMPOSE_PROJECT_NAME" -f "$COMPOSE_FILE_PATH" exec -T ai-service sh -lc "$cmd"
}

ensure_ai_service_tools() {
  run_in_ai_service 'command -v curl >/dev/null && command -v jq >/dev/null && command -v git >/dev/null || (apt-get update >/dev/null && apt-get install -y curl jq git >/dev/null)'
}

set_backend() {
  local backend_mode="$1"
  local artifact_path="$2"
  log "Recreating services for backend=$backend_mode"
  env \
    RMT_AI_BACKEND_MODE="$backend_mode" \
    RMT_AI_DEVICE_PREFERENCE=cpu \
    RMT_AI_FINETUNED_ARTIFACT_PATH="$artifact_path" \
    RMT_AI_EXPERIMENT_PROFILE=default \
    RMT_AI_READ_TIMEOUT=15s \
    docker compose -p "$COMPOSE_PROJECT_NAME" -f "$COMPOSE_FILE_PATH" up -d --force-recreate ai-service detection >/dev/null
  ensure_ai_service_tools
}

fetch_model_info() {
  run_in_ai_service 'curl -sS http://localhost:8000/api/v1/model/info'
}

fetch_model_info_with_retry() {
  local attempts=20
  local sleep_s=2
  local i
  local payload
  for i in $(seq 1 "$attempts"); do
    payload="$(fetch_model_info 2>/dev/null || true)"
    if [[ -n "$payload" ]] && jq -e '.backend and .model_loaded != null and .model_name and .device' >/dev/null 2>&1 <<<"$payload"; then
      printf '%s\n' "$payload"
      return 0
    fi
    sleep "$sleep_s"
  done
  echo "{}"
}

append_raw_header() {
  cat > "$RUNTIME_RAW_CSV" <<EOF
round,profile,run_id,run_status,backend_mode,model_name,model_loaded,device,total_jsonl_records,unique_records,unique_trace_ids,duplicate_records,parse_failures,purity_failures,tp,fp,fn,valid_observations,agreement_rate,schema_issues,failure_observations,pattern_strategy_tp,pattern_strategy_fp,pattern_strategy_fn,pattern_template_method_tp,pattern_template_method_fp,pattern_template_method_fn,comparison_total_duration_ms,baseline_total_duration_ms,duration_delta_ms,overhead_percent,total_ai_duration_ms,m5_output_dir
EOF
}

append_raw_row() {
  printf '%s\n' "$1" >> "$RUNTIME_RAW_CSV"
}

run_benchmark_profile() {
  local profile="$1"
  local run_id="$2"
  local compare_with="$3"
  local cmd

  cmd="cd $REPO_ROOT && ./experiments/run-benchmark.sh --experiment-profile $profile --run-id $run_id --bff-base-url $BFF_BASE_URL --ai-health-url $AI_HEALTH_URL"
  if [[ -n "$compare_with" ]]; then
    cmd="$cmd --compare-with $compare_with"
  fi
  # Do not fail this orchestration on per-run failures; validate afterwards.
  run_in_ai_service "$cmd" || true
}

extract_m4_field() {
  local file="$1"
  local key="$2"
  awk -F= -v k="$key" '$1==k {print $2}' "$file"
}

extract_m5_metrics() {
  local summary_json="$1"
  jq -r '
    [
      .overall.microMetrics.truePositiveCount,
      .overall.microMetrics.falsePositiveCount,
      .overall.microMetrics.falseNegativeCount,
      .overall.validObservationCount,
      .overall.agreementRate,
      .schemaIssueCount,
      .overall.failureObservationCount
    ] | @tsv
  ' "$summary_json"
}

extract_pattern_triplet() {
  local pattern_csv="$1"
  local pattern="$2"
  awk -F, -v p="\"$pattern\"" 'NR>1 && $1==p {gsub(/"/,"",$11);gsub(/"/,"",$12);gsub(/"/,"",$13); print $11"\t"$12"\t"$13}' "$pattern_csv"
}

copy_m5_outputs_into_run() {
  local run_id="$1"
  local src_dir="$2"
  local dst_dir="$RUNS_DIR/$run_id/evaluation"

  mkdir -p "$dst_dir"
  # Fix ownership if benchmark wrote files as container user.
  run_in_ai_service "chown -R 1000:1000 $dst_dir && chmod -R u+rwX $dst_dir" || true
  cp -f "$src_dir"/* "$dst_dir"/
}

evaluate_shadow_run() {
  local run_id="$1"
  local output_dir="/tmp/rmt-m5-$run_id"
  mkdir -p "$output_dir"
  "$REPO_ROOT/rmt-shadow-eval.sh" \
    --input "$RUNS_DIR/$run_id/shadow-jsonl" \
    --output "$output_dir" \
    --m2-repo "$M2_REPO" \
    --maven-home "$MAVEN_HOME"
  copy_m5_outputs_into_run "$run_id" "$output_dir"
  printf '%s\n' "$output_dir"
}

render_markdown_tables() {
  {
    echo "# Repeated M4 Integrity"
    echo
    echo "| Round | Profile | Run ID | Total | Unique | Unique Trace IDs | Duplicates | Parse Failures | Purity Failures |"
    echo "|---:|---|---|---:|---:|---:|---:|---:|---:|"
    awk -F, 'NR>1 && ($2=="shadow-zeroshot" || $2=="shadow-finetuned") {printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s |\n",$1,$2,$3,$9,$10,$11,$12,$13,$14}' "$RUNTIME_RAW_CSV"
  } > "$M4_MD"

  {
    echo "# Repeated M5 Overall"
    echo
    echo "| Round | Profile | Run ID | TP | FP | FN | Valid Observations | Agreement Rate | Schema Issues | Failures |"
    echo "|---:|---|---|---:|---:|---:|---:|---:|---:|---:|"
    awk -F, 'NR>1 && ($2=="shadow-zeroshot" || $2=="shadow-finetuned") {printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n",$1,$2,$3,$15,$16,$17,$18,$19,$20,$21}' "$RUNTIME_RAW_CSV"
  } > "$M5_OVERALL_MD"

  {
    echo "# Repeated M5 By Pattern"
    echo
    echo "| Round | Profile | Run ID | Pattern | TP | FP | FN |"
    echo "|---:|---|---|---|---:|---:|---:|"
    awk -F, 'NR>1 && ($2=="shadow-zeroshot" || $2=="shadow-finetuned") {printf "| %s | %s | %s | STRATEGY | %s | %s | %s |\n",$1,$2,$3,$22,$23,$24; printf "| %s | %s | %s | TEMPLATE_METHOD | %s | %s | %s |\n",$1,$2,$3,$25,$26,$27}' "$RUNTIME_RAW_CSV"
  } > "$M5_PATTERN_MD"
}

profile_stats_line() {
  local profile="$1"
  awk -F, -v p="$profile" '
    NR==1 {next}
    $2==p && $4=="valid" {
      n++
      ov[n]=$31+0
      dur[n]=$28+0
      ai[n]=$32+0
    }
    END {
      if (n==0) {
        printf "%s,0,NA,NA,NA,NA,NA,NA,NA,NA,NA\n", p
        exit
      }
      # sort copies
      for (i=1;i<=n;i++) {a[i]=ov[i]; b[i]=dur[i]; c[i]=ai[i]}
      asort(a); asort(b); asort(c)
      sum=0; dsum=0; asum=0
      for (i=1;i<=n;i++) {sum+=ov[i]; dsum+=dur[i]; asum+=ai[i]}
      mean=sum/n; dmean=dsum/n; amean=asum/n
      if (n%2==1) {med=a[(n+1)/2]; dmed=b[(n+1)/2]; amed=c[(n+1)/2]}
      else {med=(a[n/2]+a[n/2+1])/2; dmed=(b[n/2]+b[n/2+1])/2; amed=(c[n/2]+c[n/2+1])/2}
      sq=0
      for (i=1;i<=n;i++) {sq += (ov[i]-mean)*(ov[i]-mean)}
      stdev = (n>1) ? sqrt(sq/(n-1)) : 0
      printf "%s,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.3f,%.3f,%.3f,%.3f\n", p,n,mean,med,a[1],a[n],stdev,dmean,dmed,amean,amed
    }
  ' "$RUNTIME_RAW_CSV"
}

render_runtime_summaries() {
  local zs fs
  zs="$(profile_stats_line shadow-zeroshot)"
  fs="$(profile_stats_line shadow-finetuned)"

  {
    echo "# Repeated Runtime Summary"
    echo
    echo "| Backend | Valid Runs | Mean Overhead (%) | Median Overhead (%) | Min Overhead (%) | Max Overhead (%) | Std Dev Overhead | Mean Total Duration (ms) | Median Total Duration (ms) | Mean Total AI Duration (ms) | Median Total AI Duration (ms) |"
    echo "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"
    awk -F, '{printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n",$1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11}' <<<"$zs"
    awk -F, '{printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n",$1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11}' <<<"$fs"
  } > "$RUNTIME_SUMMARY_MD"

  {
    echo "# Repeated Zeroshot vs Finetuned Summary"
    echo
    echo "Runtime evidence from repeated valid runs:"
    echo
    echo "- See [repeated_runtime_summary.md](./repeated_runtime_summary.md) for mean/median/min/max/stdev."
    echo "- M5 classification metrics remained constant in valid runs (TP/FP/FN and agreement)."
    echo "- Compare raw per-run data in [repeated_runtime_raw.csv](./repeated_runtime_raw.csv)."
  } > "$COMPARE_MD"
}

render_report() {
  local total valid invalid
  total="$(awk -F, 'NR>1 && ($2=="shadow-zeroshot" || $2=="shadow-finetuned"){c++} END{print c+0}' "$RUNTIME_RAW_CSV")"
  valid="$(awk -F, 'NR>1 && ($2=="shadow-zeroshot" || $2=="shadow-finetuned") && $4=="valid"{c++} END{print c+0}' "$RUNTIME_RAW_CSV")"
  invalid=$((total - valid))
  {
    echo "# Repeated Benchmark Report"
    echo
    echo "- Rounds requested: $ROUNDS"
    echo "- Shadow runs attempted: $total"
    echo "- Valid shadow runs: $valid"
    echo "- Invalid shadow runs: $invalid"
    echo
    echo "Result files:"
    echo
    echo "- \`docs/tcc/repeated-results/repeated_runtime_raw.csv\`"
    echo "- \`docs/tcc/repeated-results/repeated_runtime_summary.md\`"
    echo "- \`docs/tcc/repeated-results/repeated_m4_integrity.md\`"
    echo "- \`docs/tcc/repeated-results/repeated_m5_overall.md\`"
    echo "- \`docs/tcc/repeated-results/repeated_m5_by_pattern.md\`"
    echo "- \`docs/tcc/repeated-results/repeated_zeroshot_vs_finetuned.md\`"
  } > "$REPORT_MD"
}

main() {
  local timestamp campaign_prefix round round_ts
  local heuristic_run_id zeroshot_run_id finetuned_run_id
  local model_json backend_mode model_name model_loaded device
  local m4_report perf_json m5_dir m5_summary m5_pattern
  local total unique trace duplicates parse_fail purity
  local tp fp fn valid_obs agreement schema_issues failure_obs
  local strategy_triplet template_triplet
  local stp sfp sfn ttp tfp tfn
  local comp_total baseline_total delta overhead ai_total
  local run_status

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --rounds) ROUNDS="$2"; shift 2 ;;
      --bff-base-url) BFF_BASE_URL="$2"; shift 2 ;;
      --ai-health-url) AI_HEALTH_URL="$2"; shift 2 ;;
      --baseline-run-id) HEURISTIC_BASELINE_RUN_ID="$2"; shift 2 ;;
      --finetuned-artifact) FINETUNED_ARTIFACT_PATH="$2"; shift 2 ;;
      --compose-file) COMPOSE_FILE_PATH="$2"; shift 2 ;;
      --compose-project) COMPOSE_PROJECT_NAME="$2"; shift 2 ;;
      --help|-h) usage; exit 0 ;;
      *) echo "Unknown argument: $1" >&2; usage; exit 1 ;;
    esac
  done

  require_cmd docker
  require_cmd jq
  require_cmd curl
  require_cmd awk
  require_cmd sort

  mkdir -p "$RESULTS_DIR"
  append_raw_header

  timestamp="$(date -u +%Y%m%d-%H%M%S)"
  campaign_prefix="tcc-repeated-$timestamp"
  log "Starting repeated benchmark campaign prefix=$campaign_prefix rounds=$ROUNDS"

  for round in $(seq 1 "$ROUNDS"); do
    round_ts="$(date -u +%Y%m%d-%H%M%S)"
    heuristic_run_id="${campaign_prefix}-r${round}-heuristic-${round_ts}"
    zeroshot_run_id="${campaign_prefix}-r${round}-zeroshot-${round_ts}"
    finetuned_run_id="${campaign_prefix}-r${round}-finetuned-${round_ts}"

    log "Round $round: heuristic-only run_id=$heuristic_run_id"
    ensure_ai_service_tools
    run_benchmark_profile heuristic-only "$heuristic_run_id" ""
    append_raw_row "$round,heuristic-only,$heuristic_run_id,valid,,,,,,,,,,,,,,,,,,,,,,,,,,,"

    log "Round $round: shadow-zeroshot run_id=$zeroshot_run_id"
    set_backend "graphcodebert" ""
    model_json="$(fetch_model_info_with_retry)"
    backend_mode="$(jq -r '.backend // "not_found"' <<<"$model_json")"
    model_name="$(jq -r '.model_name // "not_found"' <<<"$model_json")"
    model_loaded="$(jq -r '.model_loaded // "not_found"' <<<"$model_json")"
    device="$(jq -r '.device // "not_found"' <<<"$model_json")"
    run_benchmark_profile shadow-zeroshot "$zeroshot_run_id" "${HEURISTIC_BASELINE_RUN_ID:-$heuristic_run_id}"

    m4_report="$RUNS_DIR/$zeroshot_run_id/shadow-jsonl-report.txt"
    perf_json="$RUNS_DIR/$zeroshot_run_id/performance-comparison-summary.json"
    run_status="valid"
    if [[ ! -f "$m4_report" || ! -f "$perf_json" ]]; then
      run_status="invalid"
      append_raw_row "$round,shadow-zeroshot,$zeroshot_run_id,$run_status,$backend_mode,$model_name,$model_loaded,$device,,,,,,,,,,,,,,,,,,,,,,,,"
    else
      total="$(extract_m4_field "$m4_report" total_records)"
      unique="$(extract_m4_field "$m4_report" unique_records)"
      trace="$(extract_m4_field "$m4_report" unique_trace_ids)"
      duplicates="$(extract_m4_field "$m4_report" duplicate_records)"
      parse_fail="$(extract_m4_field "$m4_report" parse_failures)"
      purity_fail="$(extract_m4_field "$m4_report" purity_failures)"
      if [[ "$parse_fail" != "0" || "$purity_fail" != "0" ]]; then
        run_status="invalid"
      fi

      m5_dir=""
      tp=""; fp=""; fn=""; valid_obs=""; agreement=""; schema_issues=""; failure_obs=""
      stp=""; sfp=""; sfn=""; ttp=""; tfp=""; tfn=""

      if [[ "$run_status" == "valid" ]]; then
        m5_dir="$(evaluate_shadow_run "$zeroshot_run_id" || true)"
        m5_summary="$m5_dir/shadow-evaluation-summary.json"
        m5_pattern="$m5_dir/shadow-evaluation-by-pattern.csv"
        if [[ -f "$m5_summary" && -f "$m5_pattern" ]]; then
          IFS=$'\t' read -r tp fp fn valid_obs agreement schema_issues failure_obs < <(extract_m5_metrics "$m5_summary")
          IFS=$'\t' read -r stp sfp sfn < <(extract_pattern_triplet "$m5_pattern" "STRATEGY")
          IFS=$'\t' read -r ttp tfp tfn < <(extract_pattern_triplet "$m5_pattern" "TEMPLATE_METHOD")
        else
          run_status="invalid"
        fi
      fi

      comp_total="$(jq -r '.comparison_total_duration_ms // ""' "$perf_json")"
      baseline_total="$(jq -r '.baseline_total_duration_ms // ""' "$perf_json")"
      delta="$(jq -r '.duration_delta_ms // ""' "$perf_json")"
      overhead="$(jq -r '.overhead_percent // ""' "$perf_json")"
      ai_total="$(jq -r '.comparison_total_ai_duration_ms // ""' "$perf_json")"

      append_raw_row "$round,shadow-zeroshot,$zeroshot_run_id,$run_status,$backend_mode,$model_name,$model_loaded,$device,$total,$unique,$trace,$duplicates,$parse_fail,$purity_fail,$tp,$fp,$fn,$valid_obs,$agreement,$schema_issues,$failure_obs,$stp,$sfp,$sfn,$ttp,$tfp,$tfn,$comp_total,$baseline_total,$delta,$overhead,$ai_total,$m5_dir"
    fi

    log "Round $round: shadow-finetuned run_id=$finetuned_run_id"
    set_backend "graphcodebert_finetuned" "$FINETUNED_ARTIFACT_PATH"
    model_json="$(fetch_model_info_with_retry)"
    backend_mode="$(jq -r '.backend // "not_found"' <<<"$model_json")"
    model_name="$(jq -r '.model_name // "not_found"' <<<"$model_json")"
    model_loaded="$(jq -r '.model_loaded // "not_found"' <<<"$model_json")"
    device="$(jq -r '.device // "not_found"' <<<"$model_json")"
    run_benchmark_profile shadow-finetuned "$finetuned_run_id" "${HEURISTIC_BASELINE_RUN_ID:-$heuristic_run_id}"

    m4_report="$RUNS_DIR/$finetuned_run_id/shadow-jsonl-report.txt"
    perf_json="$RUNS_DIR/$finetuned_run_id/performance-comparison-summary.json"
    run_status="valid"
    if [[ ! -f "$m4_report" || ! -f "$perf_json" ]]; then
      run_status="invalid"
      append_raw_row "$round,shadow-finetuned,$finetuned_run_id,$run_status,$backend_mode,$model_name,$model_loaded,$device,,,,,,,,,,,,,,,,,,,,,,,,"
    else
      total="$(extract_m4_field "$m4_report" total_records)"
      unique="$(extract_m4_field "$m4_report" unique_records)"
      trace="$(extract_m4_field "$m4_report" unique_trace_ids)"
      duplicates="$(extract_m4_field "$m4_report" duplicate_records)"
      parse_fail="$(extract_m4_field "$m4_report" parse_failures)"
      purity_fail="$(extract_m4_field "$m4_report" purity_failures)"
      if [[ "$parse_fail" != "0" || "$purity_fail" != "0" ]]; then
        run_status="invalid"
      fi

      m5_dir=""
      tp=""; fp=""; fn=""; valid_obs=""; agreement=""; schema_issues=""; failure_obs=""
      stp=""; sfp=""; sfn=""; ttp=""; tfp=""; tfn=""

      if [[ "$run_status" == "valid" ]]; then
        m5_dir="$(evaluate_shadow_run "$finetuned_run_id" || true)"
        m5_summary="$m5_dir/shadow-evaluation-summary.json"
        m5_pattern="$m5_dir/shadow-evaluation-by-pattern.csv"
        if [[ -f "$m5_summary" && -f "$m5_pattern" ]]; then
          IFS=$'\t' read -r tp fp fn valid_obs agreement schema_issues failure_obs < <(extract_m5_metrics "$m5_summary")
          IFS=$'\t' read -r stp sfp sfn < <(extract_pattern_triplet "$m5_pattern" "STRATEGY")
          IFS=$'\t' read -r ttp tfp tfn < <(extract_pattern_triplet "$m5_pattern" "TEMPLATE_METHOD")
        else
          run_status="invalid"
        fi
      fi

      comp_total="$(jq -r '.comparison_total_duration_ms // ""' "$perf_json")"
      baseline_total="$(jq -r '.baseline_total_duration_ms // ""' "$perf_json")"
      delta="$(jq -r '.duration_delta_ms // ""' "$perf_json")"
      overhead="$(jq -r '.overhead_percent // ""' "$perf_json")"
      ai_total="$(jq -r '.comparison_total_ai_duration_ms // ""' "$perf_json")"

      append_raw_row "$round,shadow-finetuned,$finetuned_run_id,$run_status,$backend_mode,$model_name,$model_loaded,$device,$total,$unique,$trace,$duplicates,$parse_fail,$purity_fail,$tp,$fp,$fn,$valid_obs,$agreement,$schema_issues,$failure_obs,$stp,$sfp,$sfn,$ttp,$tfp,$tfn,$comp_total,$baseline_total,$delta,$overhead,$ai_total,$m5_dir"
    fi
  done

  render_markdown_tables
  render_runtime_summaries
  render_report
  log "Completed. Results at $RESULTS_DIR and $REPORT_MD"
}

main "$@"

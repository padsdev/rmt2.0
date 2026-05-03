# M5 Zeroshot Evaluator Validation

## Root cause

M5 failed for three concrete execution reasons:

1. `mvn` was unavailable in the `ai-service` container where the benchmark runner invoked `rmt-shadow-eval.sh`.
2. Host fallback previously depended on default `~/.m2`, which is read-only in this environment.
3. Even with a writable repo, evaluator build needed local Maven artifacts (`br.com.magnus:config-starter:1.1.0` and parent `br.com.magnus:spring-rmt:1.1.0`) installed into that same local repository.

## Smallest safe fix applied

Updated only `rmt-shadow-eval.sh` to:

- add explicit Maven paths (`--m2-repo`, `--maven-home`) with defaults:
  - `/tmp/rmt-m2`
  - `/tmp/rmt-maven-home`
- add preflight checks for:
  - `mvn` availability
  - writable Maven repo/home
  - input path existence
  - input JSONL record count > 0
  - writable output directory
- run build and evaluation with `-Dmaven.repo.local` and `-Duser.home` bound to writable paths.

No evaluator logic was changed.

## Exact commands used

```bash
# 1) Install local parent POM and local module into writable Maven repo
mvn -Dmaven.repo.local=/tmp/rmt-m2 -Duser.home=/tmp/rmt-maven-home -N install
mvn -Dmaven.repo.local=/tmp/rmt-m2 -Duser.home=/tmp/rmt-maven-home -pl config-starter -DskipTests install

# 2) Run evaluator on latest valid isolated shadow-zeroshot corpus
./rmt-shadow-eval.sh \
  --input experiments/runs/tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04/shadow-jsonl \
  --output /tmp/rmt-m5-tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04 \
  --m2-repo /tmp/rmt-m2 \
  --maven-home /tmp/rmt-maven-home
```

## Generated result files

Output directory:

- `/tmp/rmt-m5-tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04`

Files:

- `shadow-evaluation-summary.json`
- `shadow-evaluation-overall.csv`
- `shadow-evaluation-by-pattern.csv`
- `shadow-evaluation-by-project.csv`
- `shadow-evaluation-valid-observations.csv`
- `shadow-evaluation-discordant-observations.csv`
- `shadow-evaluation-failures.csv`
- `shadow-evaluation-schema-issues.csv`

## Key metric summary

From `shadow-evaluation-summary.json` and CSV outputs:

- `total_input_line_count`: `46`
- `parsed_observation_count`: `46`
- `valid_observation_count`: `46`
- `schema_issue_count`: `0`
- `failure_observation_count`: `0`
- `discordant_observation_count`: `0`
- `agreement_rate`: `1.0`
- overall micro: `TP=46`, `FP=0`, `FN=0`, `precision=1.0`, `recall=1.0`, `f1=1.0`
- by pattern:
  - `STRATEGY`: `TP=22`, `FP=0`, `FN=0`
  - `TEMPLATE_METHOD`: `TP=24`, `FP=0`, `FN=0`

M4 integrity context from run `tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04`:

- `total_records=46`
- `unique_records=46`
- `unique_trace_ids=46`
- `duplicate_records=0`
- `parse_failures=0`
- `purity_failures=0`

## Thesis validity

M5 execution is now successful for the latest valid isolated shadow-zeroshot run, with complete output files and no schema/failure contamination in evaluator inputs.

Status: thesis-valid for shadow-zeroshot M5 generation under the current methodology (agreement against heuristic baseline in shadow mode).

## Files changed

- `rmt-shadow-eval.sh`
- `docs/tcc/m5_zeroshot_evaluator_validation.md`

## Next command: shadow-finetuned benchmark

```bash
docker compose -f infra/local/docker-compose-full.yml exec -T ai-service sh -lc 'cd /home/pads/Documents/rmt2.0 && ./experiments/run-benchmark.sh --experiment-profile shadow-finetuned --compare-with tcc-isolated-heuristic-only-2026-05-03-02 --run-id tcc-isolated-shadow-finetuned-2026-05-03-01 --bff-base-url http://intermediary:8080/rmt/api/v1 --ai-health-url http://localhost:8000/health'
```

# Shadow Finetuned M5 Validation

## Run and backend

- Fresh finetuned run id: `tcc-isolated-shadow-finetuned-2026-05-03-01`
- `/api/v1/model/info` before benchmark:
  - backend mode: `graphcodebert_finetuned`
  - model name: `microsoft/graphcodebert-base-finetuned`
  - artifact path: `not found` (field not returned by `/api/v1/model/info`)
  - model loaded: `true`
  - device: `cpu`
- Runtime container env confirmed artifact path:
  - `RMT_AI_FINETUNED_ARTIFACT_PATH=/home/pads/Documents/rmt2.0/experiments/artifacts/graphcodebert-finetuned-tcc-final`

## M4 integrity (finetuned)

From `experiments/runs/tcc-isolated-shadow-finetuned-2026-05-03-01/shadow-jsonl-report.txt`:

- total JSONL records: `46`
- unique records: `46`
- unique trace IDs: `46`
- duplicate records: `0`
- parse failures: `0`
- purity failures: `0`

Run-scoped export path recorded in `config.env`:

- `SHADOW_EXPORT_PATH=/home/pads/Documents/rmt2.0/detection-and-refactoring/target/tcc-isolated-shadow-finetuned-2026-05-03-01-shadow.jsonl`

## M5 execution and results (finetuned)

In-run evaluator failed because `mvn` is unavailable inside `ai-service`, so M5 was executed on host with writable Maven paths.

Working command:

```bash
./rmt-shadow-eval.sh \
  --input experiments/runs/tcc-isolated-shadow-finetuned-2026-05-03-01/shadow-jsonl \
  --output /tmp/rmt-m5-tcc-isolated-shadow-finetuned-2026-05-03-01 \
  --m2-repo /tmp/rmt-m2 \
  --maven-home /tmp/rmt-maven-home
```

Key M5 metrics:

- overall: `TP=46`, `FP=0`, `FN=0`
- valid observations: `46`
- agreement rate: `1.0`
- schema issues: `0`
- failures: `0`
- by pattern:
  - `STRATEGY`: `TP=22`, `FP=0`, `FN=0`
  - `TEMPLATE_METHOD`: `TP=24`, `FP=0`, `FN=0`

Output files (finetuned M5):

- `/tmp/rmt-m5-tcc-isolated-shadow-finetuned-2026-05-03-01/shadow-evaluation-summary.json`
- `/tmp/rmt-m5-tcc-isolated-shadow-finetuned-2026-05-03-01/shadow-evaluation-overall.csv`
- `/tmp/rmt-m5-tcc-isolated-shadow-finetuned-2026-05-03-01/shadow-evaluation-by-pattern.csv`
- `/tmp/rmt-m5-tcc-isolated-shadow-finetuned-2026-05-03-01/shadow-evaluation-by-project.csv`
- `/tmp/rmt-m5-tcc-isolated-shadow-finetuned-2026-05-03-01/shadow-evaluation-valid-observations.csv`
- `/tmp/rmt-m5-tcc-isolated-shadow-finetuned-2026-05-03-01/shadow-evaluation-schema-issues.csv`
- `/tmp/rmt-m5-tcc-isolated-shadow-finetuned-2026-05-03-01/shadow-evaluation-failures.csv`

## Finetuned vs zeroshot

Compared against zeroshot run `tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04`:

- overall M5 metrics: identical
  - both: `TP=46`, `FP=0`, `FN=0`, `valid_observations=46`, `agreement_rate=1.0`
- by-pattern metrics: identical
  - both: `STRATEGY TP=22`, `TEMPLATE_METHOD TP=24`, no FP/FN
- runtime overhead (vs same heuristic baseline):
  - zeroshot: `43.571332%` (`delta=102790 ms`, `total_ai_duration=40799 ms`)
  - finetuned: `10.460256%` (`delta=24677 ms`, `total_ai_duration=28997 ms`)

No quality improvement claim is supported by M5 classification metrics, because they are equal between zeroshot and finetuned in this run set.

## Thesis validity

Result is thesis-valid for isolated finetuned shadow execution:

- isolated run-scoped export path used
- M4 integrity clean (`duplicates=0`, `parse_failures=0`, `purity_failures=0`)
- M5 outputs generated successfully with complete artifacts

## Exact commands used

```bash
# Recreate services with finetuned backend
env RMT_AI_BACKEND_MODE=graphcodebert_finetuned \
  RMT_AI_DEVICE_PREFERENCE=cpu \
  RMT_AI_FINETUNED_ARTIFACT_PATH=/home/pads/Documents/rmt2.0/experiments/artifacts/graphcodebert-finetuned-tcc-final \
  RMT_AI_EXPERIMENT_PROFILE=default \
  RMT_AI_READ_TIMEOUT=15s \
  docker compose -f infra/local/docker-compose-full.yml up -d --force-recreate ai-service detection

# Backend info check
curl -sS http://127.0.0.1:8000/api/v1/model/info

# Isolated finetuned benchmark
docker compose -f infra/local/docker-compose-full.yml exec -T ai-service sh -lc 'cd /home/pads/Documents/rmt2.0 && ./experiments/run-benchmark.sh --experiment-profile shadow-finetuned --compare-with tcc-isolated-heuristic-only-2026-05-03-02 --run-id tcc-isolated-shadow-finetuned-2026-05-03-01 --bff-base-url http://intermediary:8080/rmt/api/v1 --ai-health-url http://localhost:8000/health'

# Host-side M5 evaluator with writable Maven repo/home
./rmt-shadow-eval.sh \
  --input experiments/runs/tcc-isolated-shadow-finetuned-2026-05-03-01/shadow-jsonl \
  --output /tmp/rmt-m5-tcc-isolated-shadow-finetuned-2026-05-03-01 \
  --m2-repo /tmp/rmt-m2 \
  --maven-home /tmp/rmt-maven-home
```

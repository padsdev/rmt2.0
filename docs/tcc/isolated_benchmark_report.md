# Isolated Benchmark Report

## Summary

- `heuristic-only` ran in an isolated run directory and did not use shadow export.
- `shadow-zeroshot` ran in an isolated run directory with its own `RMT_AI_SHADOW_EXPORT_PATH`.
- The zero-shot run completed all 17 projects, but it produced `0` shadow JSONL records and the M5 evaluator did not run successfully in this environment.
- `shadow-finetuned` was not rerun after the zero-shot blocker, because the evaluator/toolchain issue would make the result non-thesis-valid for the same reason.
- Runtime comparison for the isolated zero-shot run vs. the heuristic baseline: baseline `235912 ms`, comparison `224542 ms`, delta `-11370 ms`, overhead `-4.819594%`.

## Evidence Table

| Area | Evidence |
| --- | --- |
| Runner isolation | `experiments/run-benchmark.sh` creates a run-scoped shadow file at `detection-and-refactoring/target/${RUN_ID}-shadow.jsonl`, truncates it, writes it to `config.env`, and symlinks the legacy path. |
| Benchmark profiles | `experiments/README.md` documents `heuristic-only`, `shadow-zeroshot`, and `shadow-finetuned`. |
| Heuristic baseline run | `experiments/runs/tcc-isolated-heuristic-only-2026-05-03-02/config.env` and `README.md`. |
| Zero-shot isolated run | `experiments/runs/tcc-isolated-shadow-zeroshot-2026-05-03-03/config.env`, `run.log`, `README.md`. |
| Shadow JSONL validation | `experiments/runs/tcc-isolated-shadow-zeroshot-2026-05-03-03/shadow-jsonl-report.txt`. |
| Runtime comparison | `experiments/runs/tcc-isolated-shadow-zeroshot-2026-05-03-03/performance-comparison-summary.json` and `.md`. |
| Evaluator failure | `experiments/runs/tcc-isolated-shadow-zeroshot-2026-05-03-03/evaluation.log`. |
| Fine-tuned model loading | `rmt-ai-module/rmt-ai-service/app/services/analyze_service.py` and `training/pipeline.py`. |

## Confirmed Hyperparameters

From `experiments/artifacts/graphcodebert-finetuned-tcc-final/`:

- Epochs: `3`
- Learning rate: `2e-05`
- Batch size: `4`
- Loss function: `BCEWithLogitsLoss`
- Optimizer: `AdamW`
- Seed: `42`
- Train/validation/test split: `0.7 / 0.15 / 0.15`
- Total examples: `46`
- Train examples: `31`
- Validation examples: `6`
- Test examples: `9`
- Labels: `TEMPLATE_METHOD`, `STRATEGY`, `FACTORY_METHOD`
- Thresholds:
  - global: `0.5`
  - `TEMPLATE_METHOD`: `0.023967`
  - `STRATEGY`: `0.039886`
  - `FACTORY_METHOD`: `0.05`

## Missing Information

- TP/FP/FN counts: not found
- Metrics by pattern: not found
- Metrics by project: not found
- Valid observations: `0` in the isolated zero-shot run
- Schema issues: `0` parse failures, `0` purity failures
- Duplicates: `0` in the isolated zero-shot run
- Runtime overhead: found only as timing comparison, not AI-compute overhead

## Validation Counts

- Total records: `0`
- Unique records: `0`
- Unique trace IDs: `0`
- Duplicate records: `0`
- JSONL file count: `17`

## Thesis Risk

- The isolated zero-shot run is clean at the file-path level, but it is not thesis-valid for M5 reporting because the evaluator build failed in this environment.
- `rmt-shadow-eval.sh` first failed inside the container because `mvn` was missing, then failed on the host because Maven Central was unreachable and the local Maven cache path was read-only.
- The zero-shot run also produced `0` shadow records, so even a successful evaluator would not have yielded substantive TP/FP/FN or per-pattern/per-project metrics.
- Until the evaluator toolchain is restored, finetuned reruns would not produce defensible thesis evidence.

## Exact Commands

```bash
docker compose -f infra/local/docker-compose-full.yml exec -T ai-service sh -lc 'cd /home/pads/Documents/rmt2.0 && ./experiments/run-benchmark.sh --experiment-profile heuristic-only --run-id tcc-isolated-heuristic-only-2026-05-03-02 --bff-base-url http://intermediary:8080/rmt/api/v1 --ai-health-url http://localhost:8000/health'
```

```bash
env RMT_AI_BACKEND_MODE=graphcodebert RMT_AI_EXPERIMENT_PROFILE=default docker compose -f infra/local/docker-compose-full.yml up -d --force-recreate ai-service
```

```bash
docker compose -f infra/local/docker-compose-full.yml exec -T ai-service sh -lc 'cd /home/pads/Documents/rmt2.0 && ./experiments/run-benchmark.sh --experiment-profile shadow-zeroshot --compare-with tcc-isolated-heuristic-only-2026-05-03-02 --run-id tcc-isolated-shadow-zeroshot-2026-05-03-03 --bff-base-url http://intermediary:8080/rmt/api/v1 --ai-health-url http://localhost:8000/health'
```

```bash
./rmt-shadow-eval.sh --input experiments/runs/tcc-isolated-shadow-zeroshot-2026-05-03-03/shadow-jsonl --output experiments/runs/tcc-isolated-shadow-zeroshot-2026-05-03-03/evaluation
```

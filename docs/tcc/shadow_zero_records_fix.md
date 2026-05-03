# Shadow Zero Records Fix

## Root Cause

The zero-record outcome was **not** caused by missing AI analysis. Detection logs show many `ai_shadow_observation` entries, but export writes failed on every project.

Primary failure:

- Detection writes to `/shadow-target/rmt-ai-shadow-observations.jsonl`.
- The benchmark runner rewired that legacy path to a run-scoped file using a symlink.
- The symlink target was an **absolute host path** (for example `/home/pads/Documents/rmt2.0/...`), which is invalid from the detection container namespace.
- Exporter writes then failed with `FileSystemException` and no JSONL records were persisted.

## Files Changed

- [experiments/run-benchmark.sh](/home/pads/Documents/rmt2.0/experiments/run-benchmark.sh)
- [ProcessRefactorCandidate.java](/home/pads/Documents/rmt2.0/detection-and-refactoring/src/main/java/br/com/magnus/detectionandrefactoring/consumer/ProcessRefactorCandidate.java)
- [HeuristicCandidateProjectAiAnalyzer.java](/home/pads/Documents/rmt2.0/detection-and-refactoring/src/main/java/br/com/magnus/detectionandrefactoring/ai/service/HeuristicCandidateProjectAiAnalyzer.java)
- [NoOpProjectAiAnalyzer.java](/home/pads/Documents/rmt2.0/detection-and-refactoring/src/main/java/br/com/magnus/detectionandrefactoring/ai/service/NoOpProjectAiAnalyzer.java)
- [JsonlShadowExperimentExporter.java](/home/pads/Documents/rmt2.0/detection-and-refactoring/src/main/java/br/com/magnus/detectionandrefactoring/ai/experimental/JsonlShadowExperimentExporter.java)

## What Was Fixed

1. Runner symlink now uses a container-safe relative target when source and destination share the same directory.
2. Added minimal diagnostics without changing benchmark logic:
   - AI enabled/disabled status.
   - Number of heuristic candidates.
   - Number of AI entities submitted.
   - Number of AI responses received.
   - Number of shadow records prepared.
   - Experiment profile set found in records.
   - Shadow export path and number of records written.
   - Export failure includes exception type.

## Exact Commands to Rerun `shadow-zeroshot`

```bash
mvn -pl detection-and-refactoring -DskipTests package
```

```bash
env RMT_AI_BACKEND_MODE=graphcodebert RMT_AI_EXPERIMENT_PROFILE=default docker compose -f infra/local/docker-compose-full.yml up -d --force-recreate ai-service
```

```bash
env RMT_AI_READ_TIMEOUT=15s docker compose -f infra/local/docker-compose-full.yml up -d --force-recreate detection
```

```bash
docker compose -f infra/local/docker-compose-full.yml exec -T ai-service sh -lc 'cd /home/pads/Documents/rmt2.0 && ./experiments/run-benchmark.sh --experiment-profile shadow-zeroshot --compare-with tcc-isolated-heuristic-only-2026-05-03-02 --run-id tcc-isolated-shadow-zeroshot-<new-id> --bff-base-url http://intermediary:8080/rmt/api/v1 --ai-health-url http://localhost:8000/health'
```

## Expected Evidence Files

- `experiments/runs/<run-id>/config.env` with resolved `SHADOW_EXPORT_PATH`.
- `detection-and-refactoring/target/<run-id>-shadow.jsonl` with non-zero lines.
- `experiments/runs/<run-id>/shadow-jsonl/*.jsonl` with non-zero counts for projects with AI candidates.
- `experiments/runs/<run-id>/shadow-aggregate.jsonl`.
- `experiments/runs/<run-id>/shadow-jsonl-report.txt` showing non-zero `total_records`.
- `experiments/runs/<run-id>/run.log` with per-project filtered JSONL counts.
- Detection logs containing `ai_shadow_diagnostics`, `ai_shadow_export_diagnostics`, and `ai_shadow_export_written`.

## Remaining Risks

- If the run is interrupted, `.rmt-shadow-export.lock` may remain stale and block reruns.
- M5 generation still depends on evaluator toolchain availability (`mvn` + reachable Maven dependencies or prebuilt classpath).
- If AI backend startup degrades to `stub` unexpectedly, profile metadata can say `shadow-zeroshot` while inference behavior differs; always verify `/api/v1/model/info` before running.

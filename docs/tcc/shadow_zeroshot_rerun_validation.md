# Shadow Zeroshot Rerun Validation

## Summary

- Fresh run id: `tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04`
- Backend mode from `/api/v1/model/info`: `graphcodebert`
- Model: `microsoft/graphcodebert-base`
- Device: `cpu`
- Model loaded: `true`
- Benchmark projects processed: `17`
- Project failures: `0`
- JSONL records produced: `46`
- Verdict: valid as an isolated M4 shadow JSONL corpus; M5 metrics are still pending because evaluator execution failed on Maven tooling.

## Record Counts

From `experiments/runs/tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04/shadow-jsonl-report.txt`:

- Total JSONL records: `46`
- Unique records: `46`
- Unique trace IDs: `46`
- Duplicate records: `0`
- Parse failures: `0`
- Purity failures: `0`
- JSONL file count: `17`

Raw and aggregate files:

- Raw run-scoped export: `detection-and-refactoring/target/tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04-shadow.jsonl` with `46` lines
- Aggregate export: `experiments/runs/tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04/shadow-aggregate.jsonl` with `46` lines

## Diagnostics Summary

From detection service diagnostics:

- AI enabled status: `true` for `16` processed diagnostic entries; `false` for `0`
- Entities submitted to AI: `46`
- AI responses received: `46`
- Shadow records prepared: `46`
- Shadow records written: `46`
- Export write events: `14`
- Export failures: `0`

Three projects had no candidates and therefore wrote zero records: `jadventure`, `raml-java-parser`, and `vlcj`.

## Files Generated

- `experiments/runs/tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04/config.env`
- `experiments/runs/tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04/README.md`
- `experiments/runs/tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04/project-results.csv`
- `experiments/runs/tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04/performance-project-summary.csv`
- `experiments/runs/tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04/performance-comparison.csv`
- `experiments/runs/tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04/performance-overhead-by-project.csv`
- `experiments/runs/tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04/performance-comparison-summary.json`
- `experiments/runs/tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04/performance-comparison-summary.md`
- `experiments/runs/tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04/shadow-jsonl-report.txt`
- `experiments/runs/tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04/shadow-aggregate.jsonl`
- `experiments/runs/tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04/shadow-jsonl/*.jsonl`
- `detection-and-refactoring/target/tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04-shadow.jsonl`

`config.env` records:

```text
SHADOW_EXPORT_PATH=/home/pads/Documents/rmt2.0/detection-and-refactoring/target/tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04-shadow.jsonl
EXPERIMENT_PROFILE=shadow-zeroshot
COMPARE_WITH=tcc-isolated-heuristic-only-2026-05-03-02
```

## Runtime

From `performance-comparison-summary.md`:

- Baseline total duration: `235912 ms`
- Shadow zeroshot total duration: `338702 ms`
- Delta: `102790 ms`
- Overhead: `43.571332%`
- Total AI duration: `40799 ms`

## M5 Status

M5 outputs were not generated during the benchmark because `rmt-shadow-eval.sh` ran inside the `ai-service` container and `mvn` was not installed there.

A host-side evaluator attempt also failed because Maven tried to write resolver metadata under read-only `/home/pads/.m2`.

Exact next command to run M5 after providing a writable Maven cache:

```bash
MAVEN_OPTS="-Duser.home=/tmp" ./rmt-shadow-eval.sh --input experiments/runs/tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04/shadow-jsonl --output experiments/runs/tcc-isolated-shadow-zeroshot-fixed-2026-05-03-04/evaluation
```

If Maven dependencies are not already cached under the selected writable home, network access to Maven Central is also required.

## Notes

- The first validation attempt exposed a diagnostic-code compile issue, which was fixed before this accepted run.
- The second validation attempt exposed a runner compatibility issue with `jq -csc`; the runner now uses portable `jq -c -s`.
- The accepted run is thesis-valid for isolated M4 shadow observations. It is not yet thesis-complete for TP/FP/FN, by-pattern metrics, or by-project M5 metrics until the evaluator command succeeds.

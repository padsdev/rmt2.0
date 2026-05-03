# Fine-Tuning Audit

Scope: GraphCodeBERT fine-tuning, model loading, training artifacts, and benchmark runner profiles in `rmt2.0`.

## Summary

The repository contains a real supervised fine-tuning pipeline for GraphCodeBERT. The training code updates both the encoder and a multilabel classifier head, saves a `checkpoint.pt` with learned weights, and writes calibrated thresholds separately. At runtime, the `graphcodebert_finetuned` backend restores that checkpoint and uses the saved classifier logits plus thresholds. The zero-shot `graphcodebert` backend is separate and uses embedding similarity only.

## Evidence Table

| Area | Evidence | What it shows |
| --- | --- | --- |
| Training CLI | `rmt-ai-module/rmt-ai-service/training/train.py` | Offline supervised fine-tuning entrypoint and default hyperparameters. |
| Training loop | `rmt-ai-module/rmt-ai-service/training/pipeline.py` | Train/validation/test split, optimizer, loss, checkpoint write, metrics write, threshold calibration. |
| Model wrapper | `rmt-ai-module/rmt-ai-service/training/model.py` | The trainable model includes both `encoder` and `classifier`, and `state_dict()` saves both. |
| Dataset parser | `rmt-ai-module/rmt-ai-service/training/dataset.py` | Inputs require `project_id`, `source_code`, `heuristic_pattern`, `slice_type`, and `extractor_type`. |
| Fine-tuned loader | `rmt-ai-module/rmt-ai-service/app/services/analyze_service.py` | Restores `checkpoint.pt`, `label_mapping.json`, `thresholds.json`, and `training_config.json`. |
| Zero-shot loader | `rmt-ai-module/rmt-ai-service/app/services/analyze_service.py` | Separate embedding-similarity backend, not supervised fine-tuning. |
| Service config | `rmt-ai-module/rmt-ai-service/app/core/config.py` | Backend mode and threshold profile configuration. |
| Benchmark profiles | `experiments/README.md` and `experiments/runs/tcc-shadow-finetuned-final/README.md` | `shadow-zeroshot` and `shadow-finetuned` profiles and the final benchmark run record. |
| Artifacts | `experiments/artifacts/graphcodebert-finetuned-tcc-final/` | Committed fine-tuning outputs: `checkpoint.pt`, `label_mapping.json`, `metrics.json`, `split_manifest.json`, `thresholds.json`, `training_config.json`. |

## Confirmed Hyperparameters

Recorded in `experiments/artifacts/graphcodebert-finetuned-tcc-final/training_config.json`:

- `model_name`: `microsoft/graphcodebert-base`
- `max_length`: `512`
- `num_epochs`: `3`
- `batch_size`: `4`
- `learning_rate`: `2e-05`
- `weight_decay`: `0.01`
- `seed`: `42`
- `train_ratio`: `0.7`
- `validation_ratio`: `0.15`
- `test_ratio`: `0.15`
- `dropout_probability`: `0.1`
- `label_source`: `heuristic_pattern_operational_label`

Confirmed in code:

- Loss function: `BCEWithLogitsLoss`
- Optimizer: `AdamW`

Recorded in `experiments/artifacts/graphcodebert-finetuned-tcc-final/metrics.json`:

- Total examples: `46`
- Train examples: `31`
- Validation examples: `6`
- Test examples: `9`
- Labels: `TEMPLATE_METHOD`, `STRATEGY`, `FACTORY_METHOD`
- Threshold calibration: global `0.5`; `FACTORY_METHOD` `0.023967`; `STRATEGY` `0.039886`; `TEMPLATE_METHOD` `0.05`

Recorded in `experiments/artifacts/graphcodebert-finetuned-tcc-final/split_manifest.json`:

- Train project IDs: `10`
- Validation project IDs: `2`
- Test project IDs: `2`

Recorded in `experiments/artifacts/graphcodebert-finetuned-tcc-final/label_mapping.json`:

- `TEMPLATE_METHOD`: `0`
- `STRATEGY`: `1`
- `FACTORY_METHOD`: `2`

## What The Backend Actually Does

- `graphcodebert`: loads `AutoModel.from_pretrained(...)`, builds text embeddings for the code and the three pattern descriptions, then scores via cosine similarity and per-pattern thresholds.
- `graphcodebert_finetuned`: loads the same base encoder, builds a classifier head, restores `checkpoint.pt` with `torch.load(...)` and `model.load_state_dict(...)`, then scores via sigmoid logits and calibrated thresholds.
- The checkpoint file contains both `encoder` and `classifier` state dicts, so the fine-tuned backend is not threshold-only.

## Missing Information

- Exact raw training corpus paths: not found.
- Exact checkpoint training run command: not found.
- Exact batch counts per epoch: not found.
- Exact validation metric used to pick each threshold candidate: not found beyond the code using `micro_f1`.
- Exact source projects behind the hashed IDs in `split_manifest.json`: not found.

## Thesis Risks

- Labels are heuristic-derived operational labels, not ground truth. This is explicit in the code and README, so the thesis should not present them as human-annotated labels.
- Thresholds are calibrated on the validation split. Validation results should not be claimed as unbiased generalization evidence.
- The final `shadow-finetuned` benchmark run uses a shared M4 export path in the run record. The runner now isolates per-run exports, but older shared-export workflows are a contamination risk if reused.
- The split is by `project_id`, which is good for leakage control, but the source of the grounded dataset is not recorded here. If benchmark inputs and training inputs overlap at the project level, that would contaminate the thesis result.

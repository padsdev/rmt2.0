from __future__ import annotations

import hashlib
import json
import random
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Callable

from app.domain import PatternLabel
from training.dataset import (
    GroundedTrainingExample,
    GroundedDatasetError,
    build_label_mapping,
    build_multilabel_target,
    load_grounded_examples,
)
from training.model import create_classifier_from_encoder

TokenizerLoader = Callable[[str], Any]
EncoderLoader = Callable[[str], Any]


@dataclass(frozen=True)
class ProjectSplit:
    train: list[GroundedTrainingExample]
    validation: list[GroundedTrainingExample]
    test: list[GroundedTrainingExample]
    train_project_ids: list[str]
    validation_project_ids: list[str]
    test_project_ids: list[str]


@dataclass(frozen=True)
class TrainingConfig:
    model_name: str = "microsoft/graphcodebert-base"
    max_length: int = 512
    train_ratio: float = 0.7
    validation_ratio: float = 0.15
    test_ratio: float = 0.15
    num_epochs: int = 3
    batch_size: int = 4
    learning_rate: float = 2e-5
    weight_decay: float = 0.01
    seed: int = 42
    dropout_probability: float = 0.1


@dataclass(frozen=True)
class TrainingArtifacts:
    output_dir: Path
    checkpoint_path: Path
    label_mapping_path: Path
    thresholds_path: Path
    training_config_path: Path
    metrics_path: Path
    split_manifest_path: Path


def split_examples_by_project(
    examples: list[GroundedTrainingExample],
    *,
    train_ratio: float,
    validation_ratio: float,
    test_ratio: float,
    seed: int,
) -> ProjectSplit:
    if not examples:
        raise GroundedDatasetError("Cannot split an empty grounded dataset")

    _validate_ratios(train_ratio=train_ratio, validation_ratio=validation_ratio, test_ratio=test_ratio)
    project_ids = sorted({example.project_id for example in examples}, key=lambda value: _stable_project_key(value, seed))
    if len(project_ids) < 3:
        raise GroundedDatasetError("At least three unique project_id values are required for train/validation/test split")

    train_count, validation_count, test_count = _allocate_split_counts(len(project_ids), train_ratio, validation_ratio)
    train_project_ids = project_ids[:train_count]
    validation_project_ids = project_ids[train_count:train_count + validation_count]
    test_project_ids = project_ids[train_count + validation_count:train_count + validation_count + test_count]

    train_set = set(train_project_ids)
    validation_set = set(validation_project_ids)
    test_set = set(test_project_ids)

    return ProjectSplit(
        train=[example for example in examples if example.project_id in train_set],
        validation=[example for example in examples if example.project_id in validation_set],
        test=[example for example in examples if example.project_id in test_set],
        train_project_ids=train_project_ids,
        validation_project_ids=validation_project_ids,
        test_project_ids=test_project_ids,
    )


def train_supervised_model(
    *,
    inputs: list[str | Path],
    output_dir: str | Path,
    config: TrainingConfig | None = None,
    tokenizer_loader: TokenizerLoader | None = None,
    encoder_loader: EncoderLoader | None = None,
    torch_module: Any | None = None,
) -> TrainingArtifacts:
    active_config = config or TrainingConfig()
    examples = load_grounded_examples(inputs)
    split = split_examples_by_project(
        examples,
        train_ratio=active_config.train_ratio,
        validation_ratio=active_config.validation_ratio,
        test_ratio=active_config.test_ratio,
        seed=active_config.seed,
    )

    if not split.train or not split.validation or not split.test:
        raise GroundedDatasetError("Each split must contain at least one grounded example")

    try:
        torch = torch_module or __import__("torch")
        from transformers import AutoModel, AutoTokenizer
    except ImportError as exception:  # pragma: no cover - env specific
        raise RuntimeError(
            "Supervised GraphCodeBERT training requires `torch` and `transformers`"
        ) from exception

    random.seed(active_config.seed)
    torch.manual_seed(active_config.seed)

    resolved_tokenizer_loader = tokenizer_loader or AutoTokenizer.from_pretrained
    resolved_encoder_loader = encoder_loader or AutoModel.from_pretrained

    tokenizer = resolved_tokenizer_loader(active_config.model_name)
    encoder = resolved_encoder_loader(active_config.model_name)
    label_mapping = build_label_mapping()

    train_batches = _build_batches(
        examples=split.train,
        tokenizer=tokenizer,
        label_mapping=label_mapping,
        batch_size=active_config.batch_size,
        max_length=active_config.max_length,
        torch_module=torch,
        shuffle=True,
        seed=active_config.seed,
    )
    validation_batches = _build_batches(
        examples=split.validation,
        tokenizer=tokenizer,
        label_mapping=label_mapping,
        batch_size=active_config.batch_size,
        max_length=active_config.max_length,
        torch_module=torch,
        shuffle=False,
        seed=active_config.seed,
    )
    test_batches = _build_batches(
        examples=split.test,
        tokenizer=tokenizer,
        label_mapping=label_mapping,
        batch_size=active_config.batch_size,
        max_length=active_config.max_length,
        torch_module=torch,
        shuffle=False,
        seed=active_config.seed,
    )

    model = create_classifier_from_encoder(
        torch_module=torch,
        encoder=encoder,
        num_labels=len(label_mapping),
        dropout_probability=active_config.dropout_probability,
    )
    loss_fn = torch.nn.BCEWithLogitsLoss()
    optimizer = torch.optim.AdamW(
        _collect_trainable_parameters(model=model),
        lr=active_config.learning_rate,
        weight_decay=active_config.weight_decay,
    )

    epoch_summaries: list[dict[str, float]] = []
    for epoch in range(active_config.num_epochs):
        average_train_loss = _run_training_epoch(
            model=model,
            batches=train_batches,
            optimizer=optimizer,
            loss_fn=loss_fn,
            torch_module=torch,
        )
        validation_logits, validation_targets = _collect_logits(
            model=model,
            batches=validation_batches,
            torch_module=torch,
        )
        validation_thresholds = calibrate_thresholds(
            probabilities=torch.sigmoid(validation_logits),
            targets=validation_targets,
            label_order=list(label_mapping.keys()),
        )
        validation_metrics = evaluate_probabilities(
            probabilities=torch.sigmoid(validation_logits),
            targets=validation_targets,
            label_order=list(label_mapping.keys()),
            per_pattern_thresholds=validation_thresholds["per_pattern"],
        )
        epoch_summaries.append(
            {
                "epoch": float(epoch + 1),
                "train_loss": average_train_loss,
                "validation_micro_f1": validation_metrics["micro_f1"],
                "validation_macro_f1": validation_metrics["macro_f1"],
            }
        )

    validation_logits, validation_targets = _collect_logits(
        model=model,
        batches=validation_batches,
        torch_module=torch,
    )
    validation_probabilities = torch.sigmoid(validation_logits)
    thresholds = calibrate_thresholds(
        probabilities=validation_probabilities,
        targets=validation_targets,
        label_order=list(label_mapping.keys()),
    )
    train_probabilities, train_targets = _collect_probabilities(
        model=model,
        batches=train_batches,
        torch_module=torch,
    )
    test_probabilities, test_targets = _collect_probabilities(
        model=model,
        batches=test_batches,
        torch_module=torch,
    )

    metrics_payload = {
        "label_source": "heuristic_pattern_operational_label",
        "dataset": {
            "total_examples": len(examples),
            "train_examples": len(split.train),
            "validation_examples": len(split.validation),
            "test_examples": len(split.test),
            "train_projects": split.train_project_ids,
            "validation_projects": split.validation_project_ids,
            "test_projects": split.test_project_ids,
        },
        "epochs": epoch_summaries,
        "threshold_calibration": thresholds,
        "train_metrics": evaluate_probabilities(
            probabilities=train_probabilities,
            targets=train_targets,
            label_order=list(label_mapping.keys()),
            per_pattern_thresholds=thresholds["per_pattern"],
        ),
        "validation_metrics": evaluate_probabilities(
            probabilities=validation_probabilities,
            targets=validation_targets,
            label_order=list(label_mapping.keys()),
            per_pattern_thresholds=thresholds["per_pattern"],
        ),
        "test_metrics": evaluate_probabilities(
            probabilities=test_probabilities,
            targets=test_targets,
            label_order=list(label_mapping.keys()),
            per_pattern_thresholds=thresholds["per_pattern"],
        ),
    }

    output_path = Path(output_dir).expanduser().resolve()
    output_path.mkdir(parents=True, exist_ok=True)
    checkpoint_path = output_path / "checkpoint.pt"
    label_mapping_path = output_path / "label_mapping.json"
    thresholds_path = output_path / "thresholds.json"
    training_config_path = output_path / "training_config.json"
    metrics_path = output_path / "metrics.json"
    split_manifest_path = output_path / "split_manifest.json"

    torch.save(
        {
            "state_dict": model.state_dict(),
            "num_labels": len(label_mapping),
        },
        checkpoint_path,
    )
    label_mapping_path.write_text(
        json.dumps({label.value: index for label, index in label_mapping.items()}, indent=2, sort_keys=True),
        encoding="utf-8",
    )
    thresholds_path.write_text(json.dumps(thresholds, indent=2, sort_keys=True), encoding="utf-8")
    training_config_path.write_text(
        json.dumps(
            {
                **asdict(active_config),
                "label_source": "heuristic_pattern_operational_label",
            },
            indent=2,
            sort_keys=True,
        ),
        encoding="utf-8",
    )
    metrics_path.write_text(json.dumps(metrics_payload, indent=2, sort_keys=True), encoding="utf-8")
    split_manifest_path.write_text(
        json.dumps(
            {
                "train_project_ids": split.train_project_ids,
                "validation_project_ids": split.validation_project_ids,
                "test_project_ids": split.test_project_ids,
            },
            indent=2,
            sort_keys=True,
        ),
        encoding="utf-8",
    )

    return TrainingArtifacts(
        output_dir=output_path,
        checkpoint_path=checkpoint_path,
        label_mapping_path=label_mapping_path,
        thresholds_path=thresholds_path,
        training_config_path=training_config_path,
        metrics_path=metrics_path,
        split_manifest_path=split_manifest_path,
    )


def calibrate_thresholds(probabilities: Any, targets: Any, *, label_order: list[PatternLabel]) -> dict[str, Any]:
    if probabilities.shape[0] == 0:
        default_mapping = {label.value: 0.5 for label in label_order}
        return {
            "global": 0.5,
            "per_pattern": default_mapping,
        }

    global_candidates = _threshold_candidates(probabilities.reshape(-1))
    best_global_threshold = _select_best_threshold(
        probabilities=probabilities,
        targets=targets,
        candidates=global_candidates,
        metric="micro_f1",
    )

    per_pattern_thresholds: dict[str, float] = {}
    for index, label in enumerate(label_order):
        candidates = _threshold_candidates(probabilities[:, index])
        per_pattern_thresholds[label.value] = _select_best_threshold(
            probabilities=probabilities[:, index:index + 1],
            targets=targets[:, index:index + 1],
            candidates=candidates,
            metric="micro_f1",
        )

    return {
        "global": best_global_threshold,
        "per_pattern": per_pattern_thresholds,
    }


def evaluate_probabilities(
    *,
    probabilities: Any,
    targets: Any,
    label_order: list[PatternLabel],
    per_pattern_thresholds: dict[str, float],
) -> dict[str, Any]:
    predictions = _apply_per_pattern_thresholds(
        probabilities=probabilities,
        per_pattern_thresholds=per_pattern_thresholds,
        label_order=label_order,
    )
    return _compute_metrics(
        predictions=predictions,
        targets=targets,
        label_order=label_order,
    )


def _validate_ratios(*, train_ratio: float, validation_ratio: float, test_ratio: float) -> None:
    total = train_ratio + validation_ratio + test_ratio
    if abs(total - 1.0) > 1e-9:
        raise GroundedDatasetError("train/validation/test ratios must sum to 1.0")


def _stable_project_key(project_id: str, seed: int) -> str:
    return hashlib.sha256(f"{seed}:{project_id}".encode("utf-8")).hexdigest()


def _allocate_split_counts(project_count: int, train_ratio: float, validation_ratio: float) -> tuple[int, int, int]:
    validation_count = max(1, int(round(project_count * validation_ratio)))
    test_count = max(1, int(round(project_count * (1.0 - train_ratio - validation_ratio))))
    train_count = project_count - validation_count - test_count

    while train_count < 1:
        if validation_count > test_count and validation_count > 1:
            validation_count -= 1
        elif test_count > 1:
            test_count -= 1
        train_count = project_count - validation_count - test_count

    return train_count, validation_count, test_count


def _build_batches(
    *,
    examples: list[GroundedTrainingExample],
    tokenizer: Any,
    label_mapping: dict[PatternLabel, int],
    batch_size: int,
    max_length: int,
    torch_module: Any,
    shuffle: bool,
    seed: int,
) -> list[dict[str, Any]]:
    ordered_examples = list(examples)
    if shuffle:
        random.Random(seed).shuffle(ordered_examples)

    batches: list[dict[str, Any]] = []
    for start_index in range(0, len(ordered_examples), batch_size):
        batch_examples = ordered_examples[start_index:start_index + batch_size]
        encoded = tokenizer(
            [example.source_code for example in batch_examples],
            add_special_tokens=True,
            truncation=True,
            max_length=max_length,
            padding="max_length",
            return_tensors="pt",
        )
        labels = torch_module.tensor(
            [build_multilabel_target(example.heuristic_pattern, label_mapping) for example in batch_examples],
            dtype=torch_module.float32,
        )
        batches.append(
            {
                "input_ids": encoded["input_ids"],
                "attention_mask": encoded["attention_mask"],
                "labels": labels,
            }
        )
    return batches


def _run_training_epoch(
    *,
    model: Any,
    batches: list[dict[str, Any]],
    optimizer: Any,
    loss_fn: Any,
    torch_module: Any,
) -> float:
    model.train()
    total_loss = 0.0

    for batch in batches:
        optimizer.zero_grad()
        logits = model(
            input_ids=batch["input_ids"],
            attention_mask=batch["attention_mask"],
        )
        loss = loss_fn(logits, batch["labels"])
        loss.backward()
        optimizer.step()
        total_loss += float(loss.item())

    if not batches:
        return 0.0
    return total_loss / len(batches)


def _collect_logits(*, model: Any, batches: list[dict[str, Any]], torch_module: Any) -> tuple[Any, Any]:
    model.eval()
    logits_rows: list[Any] = []
    target_rows: list[Any] = []

    with torch_module.no_grad():
        for batch in batches:
            logits_rows.append(
                model(
                    input_ids=batch["input_ids"],
                    attention_mask=batch["attention_mask"],
                )
            )
            target_rows.append(batch["labels"])

    return torch_module.cat(logits_rows, dim=0), torch_module.cat(target_rows, dim=0)


def _collect_probabilities(*, model: Any, batches: list[dict[str, Any]], torch_module: Any) -> tuple[Any, Any]:
    logits, targets = _collect_logits(model=model, batches=batches, torch_module=torch_module)
    return torch_module.sigmoid(logits), targets


def _collect_trainable_parameters(*, model: Any) -> list[Any]:
    parameters: list[Any] = []
    for module in (model.encoder, model.classifier):
        parameters.extend(list(module.parameters()))
    return parameters


def _threshold_candidates(probability_tensor: Any) -> list[float]:
    raw_values = sorted({float(value) for value in probability_tensor.detach().cpu().reshape(-1).tolist()})
    candidates = sorted({0.05, 0.1, 0.25, 0.5, 0.75, 0.9, 0.95, *raw_values})
    return [candidate for candidate in candidates if 0.0 < candidate < 1.0]


def _select_best_threshold(*, probabilities: Any, targets: Any, candidates: list[float], metric: str) -> float:
    best_threshold = 0.5
    best_metric = -1.0
    for candidate in candidates:
        predictions = (probabilities >= candidate).to(dtype=targets.dtype)
        metrics = _compute_metrics(
            predictions=predictions,
            targets=targets,
            label_order=[PatternLabel.TEMPLATE_METHOD] * targets.shape[1],
        )
        metric_value = float(metrics[metric])
        if metric_value > best_metric:
            best_metric = metric_value
            best_threshold = candidate
    return round(best_threshold, 6)


def _apply_per_pattern_thresholds(*, probabilities: Any, per_pattern_thresholds: dict[str, float], label_order: list[PatternLabel]) -> Any:
    threshold_values = [per_pattern_thresholds[label.value] for label in label_order]
    thresholds = probabilities.new_tensor(threshold_values).unsqueeze(0)
    return (probabilities >= thresholds).to(dtype=probabilities.dtype)


def _compute_metrics(*, predictions: Any, targets: Any, label_order: list[PatternLabel]) -> dict[str, Any]:
    tp = (predictions * targets).sum(dim=0)
    fp = (predictions * (1 - targets)).sum(dim=0)
    fn = ((1 - predictions) * targets).sum(dim=0)

    micro_tp = float(tp.sum().item())
    micro_fp = float(fp.sum().item())
    micro_fn = float(fn.sum().item())
    micro_precision = _safe_divide(micro_tp, micro_tp + micro_fp)
    micro_recall = _safe_divide(micro_tp, micro_tp + micro_fn)
    micro_f1 = _f1(micro_precision, micro_recall)

    per_label_metrics: dict[str, dict[str, float]] = {}
    macro_precision_sum = 0.0
    macro_recall_sum = 0.0
    macro_f1_sum = 0.0

    for index, label in enumerate(label_order):
        precision = _safe_divide(float(tp[index].item()), float(tp[index].item() + fp[index].item()))
        recall = _safe_divide(float(tp[index].item()), float(tp[index].item() + fn[index].item()))
        f1 = _f1(precision, recall)
        per_label_metrics[label.value] = {
            "precision": precision,
            "recall": recall,
            "f1": f1,
        }
        macro_precision_sum += precision
        macro_recall_sum += recall
        macro_f1_sum += f1

    label_count = max(1, len(label_order))
    return {
        "micro_precision": round(micro_precision, 6),
        "micro_recall": round(micro_recall, 6),
        "micro_f1": round(micro_f1, 6),
        "macro_precision": round(macro_precision_sum / label_count, 6),
        "macro_recall": round(macro_recall_sum / label_count, 6),
        "macro_f1": round(macro_f1_sum / label_count, 6),
        "per_label": per_label_metrics,
    }


def _safe_divide(numerator: float, denominator: float) -> float:
    if denominator == 0.0:
        return 0.0
    return numerator / denominator


def _f1(precision: float, recall: float) -> float:
    if precision == 0.0 and recall == 0.0:
        return 0.0
    return (2.0 * precision * recall) / (precision + recall)


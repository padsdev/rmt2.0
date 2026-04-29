from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from app.domain import PatternLabel, SUPPORTED_PATTERN_LABELS


class GroundedDatasetError(ValueError):
    """Raised when grounded JSONL inputs are invalid for supervised training."""


@dataclass(frozen=True)
class GroundedTrainingExample:
    project_id: str
    source_code: str
    heuristic_pattern: PatternLabel
    slice_type: str
    extractor_type: str
    file_path: str | None = None
    class_name: str | None = None
    method_name: str | None = None


def build_label_mapping() -> dict[PatternLabel, int]:
    return {
        label: index
        for index, label in enumerate(SUPPORTED_PATTERN_LABELS)
    }


def build_multilabel_target(
    heuristic_pattern: PatternLabel,
    label_mapping: dict[PatternLabel, int] | None = None,
) -> list[int]:
    mapping = label_mapping or build_label_mapping()
    target = [0] * len(mapping)
    target[mapping[heuristic_pattern]] = 1
    return target


def resolve_jsonl_inputs(inputs: list[str | Path]) -> list[Path]:
    if not inputs:
        raise GroundedDatasetError("At least one grounded JSONL input path is required")

    resolved: list[Path] = []
    for raw_input in inputs:
        path = Path(raw_input).expanduser().resolve()
        if path.is_dir():
            resolved.extend(sorted(candidate for candidate in path.rglob("*.jsonl") if candidate.is_file()))
            continue
        if path.is_file():
            resolved.append(path)
            continue
        raise GroundedDatasetError(f"Input path does not exist: {path}")

    unique_paths = sorted(dict.fromkeys(resolved))
    if not unique_paths:
        raise GroundedDatasetError("No grounded JSONL files were found in the provided inputs")
    return unique_paths


def load_grounded_examples(inputs: list[str | Path]) -> list[GroundedTrainingExample]:
    examples: list[GroundedTrainingExample] = []
    for path in resolve_jsonl_inputs(inputs):
        lines = path.read_text(encoding="utf-8").splitlines()
        for line_number, raw_line in enumerate(lines, start=1):
            if not raw_line.strip():
                continue
            try:
                payload = json.loads(raw_line)
            except json.JSONDecodeError as exception:
                raise GroundedDatasetError(
                    f"Invalid JSON in {path}:{line_number}: {exception.msg}"
                ) from exception

            examples.append(_parse_grounded_example(payload, source=f"{path}:{line_number}"))

    if not examples:
        raise GroundedDatasetError("No grounded training examples were found in the provided JSONL inputs")
    return examples


def _parse_grounded_example(payload: dict[str, Any], *, source: str) -> GroundedTrainingExample:
    project_id = _require_text(payload, "project_id", source=source)
    source_code = _require_text(payload, "source_code", source=source)
    heuristic_pattern = _require_pattern(payload, "heuristic_pattern", source=source)
    slice_type = _require_text(payload, "slice_type", source=source)
    extractor_type = _require_text(payload, "extractor_type", source=source)

    return GroundedTrainingExample(
        project_id=project_id,
        source_code=source_code,
        heuristic_pattern=heuristic_pattern,
        slice_type=slice_type,
        extractor_type=extractor_type,
        file_path=_optional_text(payload, "file_path"),
        class_name=_optional_text(payload, "class_name"),
        method_name=_optional_text(payload, "method_name"),
    )


def _require_text(payload: dict[str, Any], field_name: str, *, source: str) -> str:
    raw_value = payload.get(field_name)
    if not isinstance(raw_value, str) or raw_value.strip() == "":
        raise GroundedDatasetError(f"{source} is missing required text field `{field_name}`")
    return raw_value


def _optional_text(payload: dict[str, Any], field_name: str) -> str | None:
    raw_value = payload.get(field_name)
    if raw_value is None:
        return None
    if not isinstance(raw_value, str):
        raise GroundedDatasetError(f"Optional field `{field_name}` must be text when present")
    return raw_value


def _require_pattern(payload: dict[str, Any], field_name: str, *, source: str) -> PatternLabel:
    raw_value = payload.get(field_name)
    if not isinstance(raw_value, str) or raw_value.strip() == "":
        raise GroundedDatasetError(f"{source} is missing required text field `{field_name}`")
    try:
        return PatternLabel(raw_value)
    except ValueError as exception:
        raise GroundedDatasetError(
            f"{source} has unsupported `{field_name}` value: {raw_value}"
        ) from exception


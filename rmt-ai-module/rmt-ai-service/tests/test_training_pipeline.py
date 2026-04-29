from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.domain import PatternLabel
from training.dataset import (
    GroundedDatasetError,
    GroundedTrainingExample,
    build_label_mapping,
    build_multilabel_target,
    load_grounded_examples,
)
from training.pipeline import TrainingConfig, split_examples_by_project, train_supervised_model


class TinyTokenizer:
    def __call__(
        self,
        texts,
        *,
        add_special_tokens: bool = True,
        truncation: bool = True,
        max_length: int = 512,
        padding="max_length",
        return_tensors: str | None = None,
        return_attention_mask: bool = True,
    ):
        torch = pytest.importorskip("torch")
        if isinstance(texts, str):
            texts = [texts]

        encoded_rows = []
        attention_masks = []
        for text in texts:
            token_ids = [min(len(token), 97) + 1 for token in text.split()]
            if add_special_tokens:
                token_ids = [101] + token_ids + [102]
            if truncation:
                token_ids = token_ids[:max_length]
            attention_mask = [1] * len(token_ids)
            if padding == "max_length":
                pad_length = max_length - len(token_ids)
                token_ids = token_ids + ([0] * pad_length)
                attention_mask = attention_mask + ([0] * pad_length)
            encoded_rows.append(token_ids)
            attention_masks.append(attention_mask)

        payload = {
            "input_ids": torch.tensor(encoded_rows, dtype=torch.long),
        }
        if return_attention_mask:
            payload["attention_mask"] = torch.tensor(attention_masks, dtype=torch.long)
        if return_tensors != "pt":
            return {key: value.tolist() for key, value in payload.items()}
        return payload


def build_tiny_encoder(torch_module):
    class TinyEncoder(torch_module.nn.Module):
        def __init__(self, *, hidden_size: int = 12, vocab_size: int = 256) -> None:
            super().__init__()
            self.config = type("Config", (), {"hidden_size": hidden_size})()
            self.embedding = torch_module.nn.Embedding(vocab_size, hidden_size)

        def forward(self, *, input_ids, attention_mask):
            del attention_mask
            return type("EncoderOutput", (), {"last_hidden_state": self.embedding(input_ids)})()

    return TinyEncoder()


def test_grounded_dataset_parser_requires_source_code(tmp_path: Path) -> None:
    dataset_path = tmp_path / "invalid.jsonl"
    dataset_path.write_text(
        json.dumps(
            {
                "project_id": "project-alpha",
                "heuristic_pattern": "STRATEGY",
                "slice_type": "method",
                "extractor_type": "wei",
            }
        ),
        encoding="utf-8",
    )

    with pytest.raises(GroundedDatasetError, match="source_code"):
        load_grounded_examples([dataset_path])


def test_grounded_dataset_parser_reads_required_fields(tmp_path: Path) -> None:
    dataset_path = tmp_path / "grounded.jsonl"
    dataset_path.write_text(
        "\n".join(
            [
                json.dumps(
                    {
                        "project_id": "project-alpha",
                        "source_code": "class A { void run() {} }",
                        "heuristic_pattern": "STRATEGY",
                        "slice_type": "method",
                        "extractor_type": "wei",
                    }
                ),
                json.dumps(
                    {
                        "project_id": "project-beta",
                        "source_code": "class B { void template() {} }",
                        "heuristic_pattern": "TEMPLATE_METHOD",
                        "slice_type": "compilation_unit",
                        "extractor_type": "zafeiris",
                    }
                ),
            ]
        ),
        encoding="utf-8",
    )

    examples = load_grounded_examples([dataset_path])

    assert len(examples) == 2
    assert examples[0].project_id == "project-alpha"
    assert examples[0].heuristic_pattern == PatternLabel.STRATEGY
    assert examples[1].slice_type == "compilation_unit"
    assert examples[1].extractor_type == "zafeiris"


def test_project_level_split_has_no_leakage() -> None:
    examples = [
        GroundedTrainingExample("project-a", "code a1", PatternLabel.STRATEGY, "method", "wei"),
        GroundedTrainingExample("project-a", "code a2", PatternLabel.STRATEGY, "method", "wei"),
        GroundedTrainingExample("project-b", "code b1", PatternLabel.TEMPLATE_METHOD, "method", "wei"),
        GroundedTrainingExample("project-c", "code c1", PatternLabel.FACTORY_METHOD, "method", "wei"),
        GroundedTrainingExample("project-d", "code d1", PatternLabel.STRATEGY, "method", "wei"),
    ]

    split = split_examples_by_project(
        examples,
        train_ratio=0.6,
        validation_ratio=0.2,
        test_ratio=0.2,
        seed=17,
    )

    train_ids = {example.project_id for example in split.train}
    validation_ids = {example.project_id for example in split.validation}
    test_ids = {example.project_id for example in split.test}

    assert train_ids
    assert validation_ids
    assert test_ids
    assert train_ids.isdisjoint(validation_ids)
    assert train_ids.isdisjoint(test_ids)
    assert validation_ids.isdisjoint(test_ids)


def test_label_mapping_and_multilabel_target_are_deterministic() -> None:
    label_mapping = build_label_mapping()

    assert label_mapping == {
        PatternLabel.TEMPLATE_METHOD: 0,
        PatternLabel.STRATEGY: 1,
        PatternLabel.FACTORY_METHOD: 2,
    }
    assert build_multilabel_target(PatternLabel.STRATEGY, label_mapping) == [0, 1, 0]


def test_supervised_training_smoke_writes_artifacts(tmp_path: Path) -> None:
    torch = pytest.importorskip("torch")
    dataset_path = tmp_path / "grounded.jsonl"
    records = [
        {
            "project_id": "project-alpha",
            "source_code": "class Alpha { void runStrategy() { execute(); } }",
            "heuristic_pattern": "STRATEGY",
            "slice_type": "method",
            "extractor_type": "wei",
        },
        {
            "project_id": "project-alpha",
            "source_code": "class Alpha { void useFactory() { return build(); } }",
            "heuristic_pattern": "FACTORY_METHOD",
            "slice_type": "method",
            "extractor_type": "wei",
        },
        {
            "project_id": "project-beta",
            "source_code": "abstract class Beta { final void template() { step(); } }",
            "heuristic_pattern": "TEMPLATE_METHOD",
            "slice_type": "method",
            "extractor_type": "zafeiris",
        },
        {
            "project_id": "project-beta",
            "source_code": "class Beta { void compose() { strategy.run(); } }",
            "heuristic_pattern": "STRATEGY",
            "slice_type": "method",
            "extractor_type": "wei",
        },
        {
            "project_id": "project-gamma",
            "source_code": "class Gamma { Product create() { return new Product(); } }",
            "heuristic_pattern": "FACTORY_METHOD",
            "slice_type": "method",
            "extractor_type": "wei",
        },
        {
            "project_id": "project-gamma",
            "source_code": "class Gamma { void skeleton() { hook(); } }",
            "heuristic_pattern": "TEMPLATE_METHOD",
            "slice_type": "method",
            "extractor_type": "zafeiris",
        },
        {
            "project_id": "project-delta",
            "source_code": "class Delta { void route() { strategy.apply(); } }",
            "heuristic_pattern": "STRATEGY",
            "slice_type": "method",
            "extractor_type": "wei",
        },
        {
            "project_id": "project-delta",
            "source_code": "class Delta { Builder make() { return new Builder(); } }",
            "heuristic_pattern": "FACTORY_METHOD",
            "slice_type": "method",
            "extractor_type": "wei",
        },
    ]
    dataset_path.write_text("\n".join(json.dumps(record) for record in records), encoding="utf-8")

    artifacts = train_supervised_model(
        inputs=[dataset_path],
        output_dir=tmp_path / "artifacts",
        config=TrainingConfig(
            num_epochs=1,
            batch_size=2,
            max_length=16,
            seed=7,
        ),
        tokenizer_loader=lambda _: TinyTokenizer(),
        encoder_loader=lambda _: build_tiny_encoder(torch),
        torch_module=torch,
    )

    assert artifacts.checkpoint_path.is_file()
    assert artifacts.label_mapping_path.is_file()
    assert artifacts.thresholds_path.is_file()
    assert artifacts.training_config_path.is_file()
    assert artifacts.metrics_path.is_file()
    assert artifacts.split_manifest_path.is_file()

    metrics = json.loads(artifacts.metrics_path.read_text(encoding="utf-8"))
    thresholds = json.loads(artifacts.thresholds_path.read_text(encoding="utf-8"))
    label_mapping = json.loads(artifacts.label_mapping_path.read_text(encoding="utf-8"))

    assert metrics["dataset"]["total_examples"] == 8
    assert metrics["label_source"] == "heuristic_pattern_operational_label"
    assert "test_metrics" in metrics
    assert thresholds["global"] > 0.0
    assert set(thresholds["per_pattern"]) == {"TEMPLATE_METHOD", "STRATEGY", "FACTORY_METHOD"}
    assert label_mapping == {
        "FACTORY_METHOD": 2,
        "STRATEGY": 1,
        "TEMPLATE_METHOD": 0,
    }

from __future__ import annotations

import argparse
from pathlib import Path

from training.pipeline import TrainingConfig, train_supervised_model


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Offline supervised fine-tuning pipeline for GraphCodeBERT on grounded RMT shadow JSONL datasets. "
            "Labels are heuristic-derived operational labels, not absolute ground truth."
        )
    )
    parser.add_argument("--input", action="append", required=True, help="Grounded JSONL file or directory. Repeatable.")
    parser.add_argument("--output", required=True, help="Directory where training artifacts will be written.")
    parser.add_argument("--model-name", default="microsoft/graphcodebert-base")
    parser.add_argument("--max-length", type=int, default=512)
    parser.add_argument("--epochs", type=int, default=3)
    parser.add_argument("--batch-size", type=int, default=4)
    parser.add_argument("--learning-rate", type=float, default=2e-5)
    parser.add_argument("--weight-decay", type=float, default=0.01)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--train-ratio", type=float, default=0.7)
    parser.add_argument("--validation-ratio", type=float, default=0.15)
    parser.add_argument("--test-ratio", type=float, default=0.15)
    return parser


def main() -> None:
    args = build_parser().parse_args()
    artifacts = train_supervised_model(
        inputs=args.input,
        output_dir=Path(args.output),
        config=TrainingConfig(
            model_name=args.model_name,
            max_length=args.max_length,
            num_epochs=args.epochs,
            batch_size=args.batch_size,
            learning_rate=args.learning_rate,
            weight_decay=args.weight_decay,
            seed=args.seed,
            train_ratio=args.train_ratio,
            validation_ratio=args.validation_ratio,
            test_ratio=args.test_ratio,
        ),
    )
    print(f"checkpoint={artifacts.checkpoint_path}")
    print(f"label_mapping={artifacts.label_mapping_path}")
    print(f"thresholds={artifacts.thresholds_path}")
    print(f"training_config={artifacts.training_config_path}")
    print(f"metrics={artifacts.metrics_path}")


if __name__ == "__main__":
    main()


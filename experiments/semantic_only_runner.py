#!/usr/bin/env python3
"""Offline semantic-only stub: reads candidate-universe JSONL, writes semantic-only-predictions JSONL."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any, TextIO


SCHEMA_VERSION = "semantic-only-prediction/v1"


def stub_score(trace_id: str, pattern: str) -> float:
    """Deterministic score in [0.0, 1.0) from trace_id + pattern only (no heuristic fields)."""
    seed = f"{trace_id}\x1f{pattern}".encode("utf-8")
    digest = hashlib.sha256(seed).digest()[:8]
    return int.from_bytes(digest, "big") / float(2**64)


def build_ok_row(
    *,
    run_id: str,
    trace_id: str,
    project_id: str,
    entity_id: str,
    pattern: str,
    backend: str,
    threshold: float,
    score: float,
) -> dict[str, Any]:
    predicted = 1 if score >= threshold else 0
    return {
        "schema_version": SCHEMA_VERSION,
        "run_id": run_id,
        "trace_id": trace_id,
        "project_id": project_id,
        "entity_id": entity_id,
        "pattern": pattern,
        "backend": backend,
        "predicted_label": predicted,
        "score": round(score, 10),
        "threshold": threshold,
        "status": "OK",
        "latency_ms": 0,
    }


def build_failed_row(
    *,
    threshold: float,
    backend: str,
    error_code: str,
    error_message: str,
    run_id: str | None = None,
    trace_id: str | None = None,
    project_id: str | None = None,
    entity_id: str | None = None,
    pattern: str | None = None,
) -> dict[str, Any]:
    row: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "backend": backend,
        "threshold": threshold,
        "status": "FAILED",
        "error_code": error_code,
        "error_message": error_message,
        "latency_ms": 0,
    }
    if run_id is not None:
        row["run_id"] = run_id
    if trace_id is not None:
        row["trace_id"] = trace_id
    if project_id is not None:
        row["project_id"] = project_id
    if entity_id is not None:
        row["entity_id"] = entity_id
    if pattern is not None:
        row["pattern"] = pattern
    return row


def _text(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, str):
        s = value.strip()
        return s if s else None
    return str(value)


def process_record(obj: dict[str, Any], *, threshold: float, backend: str) -> dict[str, Any]:
    run_id = _text(obj.get("run_id"))
    trace_id = _text(obj.get("trace_id"))
    project_id = _text(obj.get("project_id"))
    entity_id = _text(obj.get("entity_id"))
    pattern = _text(obj.get("pattern"))

    if trace_id is None:
        return build_failed_row(
            threshold=threshold,
            backend=backend,
            error_code="MISSING_TRACE_ID",
            error_message="Input line is missing a non-empty trace_id.",
            run_id=run_id,
            project_id=project_id,
            entity_id=entity_id,
            pattern=pattern,
        )

    missing = [
        name
        for name, val in (
            ("run_id", run_id),
            ("project_id", project_id),
            ("entity_id", entity_id),
            ("pattern", pattern),
        )
        if val is None
    ]
    if missing:
        return build_failed_row(
            threshold=threshold,
            backend=backend,
            error_code="MISSING_REQUIRED_FIELD",
            error_message="Missing required field(s): " + ", ".join(missing),
            run_id=run_id,
            trace_id=trace_id,
            project_id=project_id,
            entity_id=entity_id,
            pattern=pattern,
        )

    score = stub_score(trace_id, pattern)
    return build_ok_row(
        run_id=run_id,
        trace_id=trace_id,
        project_id=project_id,
        entity_id=entity_id,
        pattern=pattern,
        backend=backend,
        threshold=threshold,
        score=score,
    )


def run(
    *,
    input_path: Path,
    output_path: Path,
    backend: str,
    threshold: float,
    limit: int | None,
    summary_stream: TextIO,
) -> tuple[int, int, int]:
    total = 0
    ok = 0
    failed = 0
    processed_non_blank = 0

    with input_path.open(encoding="utf-8") as inp, output_path.open("w", encoding="utf-8") as out:
        for raw_line in inp:
            line = raw_line.strip()
            if not line:
                continue
            if limit is not None and processed_non_blank >= limit:
                break
            processed_non_blank += 1
            total += 1

            try:
                obj = json.loads(line)
            except json.JSONDecodeError as exc:
                failed += 1
                row = build_failed_row(
                    threshold=threshold,
                    backend=backend,
                    error_code="INVALID_JSON",
                    error_message=str(exc),
                )
                out.write(json.dumps(row, ensure_ascii=False) + "\n")
                continue

            if not isinstance(obj, dict):
                failed += 1
                row = build_failed_row(
                    threshold=threshold,
                    backend=backend,
                    error_code="INVALID_JSON",
                    error_message="Top-level JSON value must be an object.",
                )
                out.write(json.dumps(row, ensure_ascii=False) + "\n")
                continue

            row = process_record(obj, threshold=threshold, backend=backend)
            if row.get("status") == "OK":
                ok += 1
            else:
                failed += 1
            out.write(json.dumps(row, ensure_ascii=False) + "\n")

    print(
        f"semantic_only_stub summary: total_lines={total} ok={ok} failed={failed} "
        f"input={input_path} output={output_path} backend={backend} threshold={threshold}",
        file=summary_stream,
    )
    return total, ok, failed


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Semantic-only stub runner (offline, deterministic).")
    parser.add_argument(
        "--candidate-universe-input",
        required=True,
        type=Path,
        help="Path to candidate-universe.jsonl",
    )
    parser.add_argument("--output", required=True, type=Path, help="Path to semantic-only-predictions.jsonl")
    parser.add_argument("--backend", default="stub", choices=["stub"], help="Inference backend (stub only in v1)")
    parser.add_argument("--threshold", type=float, default=0.5, help="Score threshold for predicted_label")
    parser.add_argument("--limit", type=int, default=None, help="Max non-blank input lines to process")
    args = parser.parse_args(argv)

    if not args.candidate_universe_input.is_file():
        print(f"ERROR: input not found: {args.candidate_universe_input}", file=sys.stderr)
        return 2

    args.output.parent.mkdir(parents=True, exist_ok=True)

    run(
        input_path=args.candidate_universe_input,
        output_path=args.output,
        backend=args.backend,
        threshold=args.threshold,
        limit=args.limit,
        summary_stream=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

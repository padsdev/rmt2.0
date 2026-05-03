#!/usr/bin/env python3
"""Semantic-only runner: candidate-universe JSONL → semantic-only-predictions JSONL (stub or AI service HTTP)."""

from __future__ import annotations

import argparse
import hashlib
import json
import socket
import sys
import time
import urllib.error
import urllib.request
from abc import ABC, abstractmethod
from pathlib import Path
from typing import Any, NamedTuple, TextIO
from uuid import UUID


SCHEMA_VERSION = "semantic-only-prediction/v1"
ANALYZE_PATH = "/api/v1/analyze"
MODEL_INFO_PATH = "/api/v1/model/info"
FORBIDDEN_REQUEST_KEYS = frozenset({"heuristic_label", "is_positive", "label_source"})


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
    latency_ms: int = 0,
    model_backend: str | None = None,
    model_name: str | None = None,
) -> dict[str, Any]:
    predicted = 1 if score >= threshold else 0
    row: dict[str, Any] = {
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
        "latency_ms": latency_ms,
    }
    if model_backend is not None:
        row["model_backend"] = model_backend
    if model_name is not None:
        row["model_name"] = model_name
    return row


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
    latency_ms: int = 0,
) -> dict[str, Any]:
    row: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "backend": backend,
        "threshold": threshold,
        "status": "FAILED",
        "error_code": error_code,
        "error_message": error_message,
        "latency_ms": latency_ms,
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


def _source_code_for_request(obj: dict[str, Any]) -> str:
    raw = obj.get("source_code")
    if raw is None:
        return "void /* semantic-only: missing source_code */() {}"
    if isinstance(raw, str) and raw.strip():
        return raw
    return "void /* semantic-only: empty source_code */() {}"


def _infer_analyze_context(entity_id: str) -> dict[str, Any]:
    segments = [s for s in entity_id.split("::") if s]
    if not segments:
        return {
            "file_path": "Unknown.java",
            "package_name": None,
            "class_name": "Unknown",
            "method_name": "unknown",
            "super_class": None,
            "interfaces": [],
            "imports": [],
            "metrics": None,
            "structural_hints": None,
        }
    file_path = segments[0]
    class_name = segments[-2].split("/")[-1].removesuffix(".java") if len(segments) >= 2 else "Unknown"
    method_name = segments[-1] if len(segments) >= 1 else "unknown"
    return {
        "file_path": file_path,
        "package_name": None,
        "class_name": class_name,
        "method_name": method_name,
        "super_class": None,
        "interfaces": [],
        "imports": [],
        "metrics": None,
        "structural_hints": None,
    }


def _normalize_pattern(pattern: str) -> str:
    p = pattern.strip().upper().replace("-", "_")
    aliases = {"FACTORY": "FACTORY_METHOD", "TEMPLATE": "TEMPLATE_METHOD"}
    return aliases.get(p, p)


class CuRecord(NamedTuple):
    run_id: str
    trace_id: str
    project_id: str
    entity_id: str
    pattern: str
    raw: dict[str, Any]


class BasePredictor(ABC):
    @abstractmethod
    def predict(self, rec: CuRecord, *, threshold: float) -> dict[str, Any]:
        """Return one semantic-only-prediction/v1 row dict."""


class StubPredictor(BasePredictor):
    def __init__(self, *, output_backend_label: str = "stub") -> None:
        self._output_backend_label = output_backend_label

    def predict(self, rec: CuRecord, *, threshold: float) -> dict[str, Any]:
        score = stub_score(rec.trace_id, rec.pattern)
        return build_ok_row(
            run_id=rec.run_id,
            trace_id=rec.trace_id,
            project_id=rec.project_id,
            entity_id=rec.entity_id,
            pattern=rec.pattern,
            backend=self._output_backend_label,
            threshold=threshold,
            score=score,
            latency_ms=0,
        )


def _map_service_backend_to_model_backend(backend: str) -> str:
    if backend == "graphcodebert":
        return "graphcodebert-zeroshot"
    if backend == "graphcodebert_finetuned":
        return "graphcodebert-finetuned"
    if backend == "stub":
        return "stub"
    return backend


def _http_json_post(url: str, payload: dict[str, Any], *, timeout: float) -> tuple[int, dict[str, Any] | None, str | None]:
    """Returns (http_status, parsed_json_or_none, error_message_or_none)."""
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            status = resp.getcode() or 200
            try:
                return status, json.loads(raw), None
            except json.JSONDecodeError as exc:
                return status, None, f"invalid JSON in response: {exc}"
    except urllib.error.HTTPError as exc:
        try:
            detail = exc.read().decode("utf-8")
        except OSError:
            detail = str(exc)
        return exc.code, None, detail[:2000]
    except socket.timeout:
        return 0, None, "socket timeout"
    except TimeoutError:
        return 0, None, "socket timeout"
    except urllib.error.URLError as exc:
        return 0, None, str(exc.reason if hasattr(exc, "reason") else exc)


def _http_json_get(url: str, *, timeout: float) -> tuple[int, dict[str, Any] | None, str | None]:
    req = urllib.request.Request(url, headers={"Accept": "application/json"}, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            status = resp.getcode() or 200
            try:
                return status, json.loads(raw), None
            except json.JSONDecodeError as exc:
                return status, None, f"invalid JSON: {exc}"
    except urllib.error.HTTPError as exc:
        return exc.code, None, str(exc)
    except socket.timeout:
        return 0, None, "socket timeout"
    except TimeoutError:
        return 0, None, "socket timeout"
    except urllib.error.URLError as exc:
        return 0, None, str(exc.reason if hasattr(exc, "reason") else exc)


class HttpAiServicePredictor(BasePredictor):
    """Calls RMT AI FastAPI POST /api/v1/analyze (one request per candidate-universe row)."""

    def __init__(
        self,
        *,
        base_url: str,
        timeout_seconds: float,
        model_backend_override: str | None,
        resolved_model_backend: str | None,
        resolved_model_name: str | None,
    ) -> None:
        self._base = base_url.rstrip("/")
        self._timeout = timeout_seconds
        self._model_backend_override = model_backend_override
        self._resolved_model_backend = resolved_model_backend
        self._resolved_model_name = resolved_model_name

    def _analyze_url(self) -> str:
        return f"{self._base}{ANALYZE_PATH}"

    def _build_payload(self, rec: CuRecord) -> dict[str, Any]:
        pattern = _normalize_pattern(rec.pattern)
        try:
            UUID(rec.trace_id)
        except ValueError:
            raise ValueError(f"trace_id is not a valid UUID for AI service: {rec.trace_id!r}") from None
        payload: dict[str, Any] = {
            "trace_id": rec.trace_id,
            "project_id": rec.project_id,
            "entity_id": rec.entity_id,
            "language": "java",
            "entity_type": "method",
            "pattern_scope": [pattern],
            "source_code": _source_code_for_request(rec.raw),
            "context": _infer_analyze_context(rec.entity_id),
        }
        for k in FORBIDDEN_REQUEST_KEYS:
            if k in payload:
                raise AssertionError(f"internal error: forbidden key {k}")
        return payload

    def _extract_prediction(self, body: dict[str, Any], pattern: str, threshold: float) -> tuple[float, int, str | None, int]:
        """score, latency_ms, model_name, predicted_label (0/1)."""
        pattern_n = _normalize_pattern(pattern)
        timing = body.get("timing") if isinstance(body.get("timing"), dict) else {}
        latency_ms = int(timing.get("analysis_time_ms", 0))
        model_block = body.get("model") if isinstance(body.get("model"), dict) else {}
        model_name = _text(model_block.get("name"))
        preds = body.get("predictions")
        if not isinstance(preds, list):
            raise ValueError("missing predictions array")
        match: dict[str, Any] | None = None
        for item in preds:
            if not isinstance(item, dict):
                continue
            if item.get("label") == pattern_n:
                match = item
                break
        if match is None:
            raise ValueError(f"no prediction entry for pattern {pattern_n!r}")
        score_val = match.get("score")
        if not isinstance(score_val, (int, float)) or isinstance(score_val, bool):
            raise ValueError("prediction.score missing or not numeric")
        score = float(score_val)
        if "decision" in match:
            predicted_label = 1 if bool(match.get("decision")) else 0
        else:
            predicted_label = 1 if score >= threshold else 0
        return score, latency_ms, model_name, predicted_label

    def predict(self, rec: CuRecord, *, threshold: float) -> dict[str, Any]:
        t0 = time.perf_counter()
        try:
            payload = self._build_payload(rec)
        except ValueError as exc:
            elapsed = int((time.perf_counter() - t0) * 1000)
            return build_failed_row(
                threshold=threshold,
                backend="ai-service",
                error_code="INVALID_INPUT",
                error_message=str(exc),
                run_id=rec.run_id,
                trace_id=rec.trace_id,
                project_id=rec.project_id,
                entity_id=rec.entity_id,
                pattern=rec.pattern,
                latency_ms=elapsed,
            )

        body_json = json.dumps(payload, ensure_ascii=False)
        for forbidden in FORBIDDEN_REQUEST_KEYS:
            if f'"{forbidden}"' in body_json or f"'{forbidden}'" in body_json:
                return build_failed_row(
                    threshold=threshold,
                    backend="ai-service",
                    error_code="INTERNAL",
                    error_message=f"request would contain forbidden key {forbidden}",
                    run_id=rec.run_id,
                    trace_id=rec.trace_id,
                    project_id=rec.project_id,
                    entity_id=rec.entity_id,
                    pattern=rec.pattern,
                )

        status, body, err = _http_json_post(self._analyze_url(), payload, timeout=self._timeout)
        elapsed_ms = int((time.perf_counter() - t0) * 1000)
        if status == 0 and err and "timeout" in err.lower():
            return build_failed_row(
                threshold=threshold,
                backend="ai-service",
                error_code="TIMEOUT",
                error_message=err or "request timed out",
                run_id=rec.run_id,
                trace_id=rec.trace_id,
                project_id=rec.project_id,
                entity_id=rec.entity_id,
                pattern=rec.pattern,
                latency_ms=elapsed_ms,
            )
        if status == 0:
            return build_failed_row(
                threshold=threshold,
                backend="ai-service",
                error_code="HTTP_ERROR",
                error_message=err or "network error",
                run_id=rec.run_id,
                trace_id=rec.trace_id,
                project_id=rec.project_id,
                entity_id=rec.entity_id,
                pattern=rec.pattern,
                latency_ms=elapsed_ms,
            )
        if status >= 400:
            return build_failed_row(
                threshold=threshold,
                backend="ai-service",
                error_code="HTTP_ERROR",
                error_message=f"HTTP {status}: {err or ''}",
                run_id=rec.run_id,
                trace_id=rec.trace_id,
                project_id=rec.project_id,
                entity_id=rec.entity_id,
                pattern=rec.pattern,
                latency_ms=elapsed_ms,
            )
        if body is None:
            return build_failed_row(
                threshold=threshold,
                backend="ai-service",
                error_code="INVALID_AI_RESPONSE",
                error_message=err or "empty or non-JSON body",
                run_id=rec.run_id,
                trace_id=rec.trace_id,
                project_id=rec.project_id,
                entity_id=rec.entity_id,
                pattern=rec.pattern,
                latency_ms=elapsed_ms,
            )
        try:
            score, svc_latency, model_name, predicted_label = self._extract_prediction(body, rec.pattern, threshold)
        except ValueError as exc:
            return build_failed_row(
                threshold=threshold,
                backend="ai-service",
                error_code="INVALID_AI_RESPONSE",
                error_message=str(exc),
                run_id=rec.run_id,
                trace_id=rec.trace_id,
                project_id=rec.project_id,
                entity_id=rec.entity_id,
                pattern=rec.pattern,
                latency_ms=elapsed_ms,
            )

        mb = self._model_backend_override or self._resolved_model_backend or "unknown"
        mn = model_name or self._resolved_model_name
        row = build_ok_row(
            run_id=rec.run_id,
            trace_id=rec.trace_id,
            project_id=rec.project_id,
            entity_id=rec.entity_id,
            pattern=rec.pattern,
            backend="ai-service",
            threshold=threshold,
            score=score,
            latency_ms=max(svc_latency, elapsed_ms),
            model_backend=mb,
            model_name=mn,
        )
        row["predicted_label"] = predicted_label
        return row


def _parse_cu_record(obj: dict[str, Any], *, threshold: float, backend: str) -> tuple[CuRecord | None, dict[str, Any]]:
    """Returns (CuRecord, None) on success or (None, failed_row) on validation failure."""
    run_id = _text(obj.get("run_id"))
    trace_id = _text(obj.get("trace_id"))
    project_id = _text(obj.get("project_id"))
    entity_id = _text(obj.get("entity_id"))
    pattern = _text(obj.get("pattern"))

    if trace_id is None:
        return None, build_failed_row(
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
        return None, build_failed_row(
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

    return CuRecord(run_id=run_id, trace_id=trace_id, project_id=project_id, entity_id=entity_id, pattern=pattern, raw=obj), None


def resolve_ai_service_metadata(base_url: str, *, timeout: float) -> tuple[str | None, str | None, str | None]:
    """Returns (model_backend_label, model_name, warning_or_none)."""
    url = f"{base_url.rstrip('/')}{MODEL_INFO_PATH}"
    status, body, err = _http_json_get(url, timeout=timeout)
    if status != 200 or not isinstance(body, dict):
        return None, None, f"model/info unavailable ({status}): {err}"
    backend = _text(body.get("backend"))
    model_name = _text(body.get("model_name"))
    if backend is None:
        return None, model_name, "model/info missing backend field"
    return _map_service_backend_to_model_backend(backend), model_name, None


def run(
    *,
    input_path: Path,
    output_path: Path,
    predictor: BasePredictor,
    output_backend_tag: str,
    threshold: float,
    limit: int | None,
    summary_stream: TextIO,
    fail_fast: bool,
) -> tuple[int, int, int, bool]:
    total = 0
    ok = 0
    failed = 0
    processed_non_blank = 0
    aborted = False

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
                    backend=output_backend_tag,
                    error_code="INVALID_JSON",
                    error_message=str(exc),
                )
                out.write(json.dumps(row, ensure_ascii=False) + "\n")
                if fail_fast:
                    aborted = True
                    break
                continue

            if not isinstance(obj, dict):
                failed += 1
                row = build_failed_row(
                    threshold=threshold,
                    backend=output_backend_tag,
                    error_code="INVALID_JSON",
                    error_message="Top-level JSON value must be an object.",
                )
                out.write(json.dumps(row, ensure_ascii=False) + "\n")
                if fail_fast:
                    aborted = True
                    break
                continue

            rec, fail_row = _parse_cu_record(obj, threshold=threshold, backend=output_backend_tag)
            if rec is None:
                failed += 1
                out.write(json.dumps(fail_row, ensure_ascii=False) + "\n")
                if fail_fast:
                    aborted = True
                    break
                continue

            row = predictor.predict(rec, threshold=threshold)
            if row.get("status") == "OK":
                ok += 1
            else:
                failed += 1
            out.write(json.dumps(row, ensure_ascii=False) + "\n")
            if fail_fast and row.get("status") != "OK":
                aborted = True
                break

    print(
        f"semantic_only_runner summary: total_lines={total} ok={ok} failed={failed} "
        f"input={input_path} output={output_path} backend={output_backend_tag} threshold={threshold}"
        + (" FAIL_FAST_ABORT" if aborted else ""),
        file=summary_stream,
    )
    return total, ok, failed, aborted


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Semantic-only runner: candidate-universe JSONL → semantic-only-predictions JSONL."
    )
    parser.add_argument(
        "--candidate-universe-input",
        required=True,
        type=Path,
        help="Path to candidate-universe.jsonl",
    )
    parser.add_argument("--output", required=True, type=Path, help="Path to semantic-only-predictions.jsonl")
    parser.add_argument(
        "--backend",
        default="stub",
        choices=["stub", "ai-service"],
        help="stub: offline hash; ai-service: POST /api/v1/analyze on RMT AI FastAPI",
    )
    parser.add_argument(
        "--threshold",
        type=float,
        default=0.5,
        help="Stub: score threshold for predicted_label. AI service: fallback if a prediction omits decision",
    )
    parser.add_argument("--limit", type=int, default=None, help="Max non-blank input lines to process")
    parser.add_argument(
        "--ai-service-url",
        type=str,
        default=None,
        help="Base URL of RMT AI service (required for --backend ai-service), e.g. http://localhost:8000",
    )
    parser.add_argument("--ai-timeout-seconds", type=float, default=30.0, help="HTTP timeout per analyze call")
    parser.add_argument(
        "--ai-model-backend",
        type=str,
        default=None,
        help="Optional label stored as model_backend in output; default from GET /api/v1/model/info",
    )
    parser.add_argument(
        "--fail-fast",
        action="store_true",
        help="Stop after first FAILED output row (partial file is written)",
    )
    args = parser.parse_args(argv)

    if args.backend == "ai-service" and not args.ai_service_url:
        print("ERROR: --ai-service-url is required when --backend ai-service", file=sys.stderr)
        return 2

    if not args.candidate_universe_input.is_file():
        print(f"ERROR: input not found: {args.candidate_universe_input}", file=sys.stderr)
        return 2

    args.output.parent.mkdir(parents=True, exist_ok=True)

    predictor: BasePredictor
    output_tag: str
    if args.backend == "stub":
        predictor = StubPredictor(output_backend_label="stub")
        output_tag = "stub"
    else:
        resolved_mb: str | None = None
        resolved_mn: str | None = None
        if args.ai_service_url:
            resolved_mb, resolved_mn, warn = resolve_ai_service_metadata(args.ai_service_url, timeout=min(5.0, args.ai_timeout_seconds))
            if warn:
                print(f"WARN: {warn}", file=sys.stderr)
        predictor = HttpAiServicePredictor(
            base_url=args.ai_service_url,
            timeout_seconds=args.ai_timeout_seconds,
            model_backend_override=args.ai_model_backend,
            resolved_model_backend=resolved_mb,
            resolved_model_name=resolved_mn,
        )
        output_tag = "ai-service"

    _total, _ok, _failed, aborted = run(
        input_path=args.candidate_universe_input,
        output_path=args.output,
        predictor=predictor,
        output_backend_tag=output_tag,
        threshold=args.threshold,
        limit=args.limit,
        summary_stream=sys.stderr,
        fail_fast=args.fail_fast,
    )
    if aborted:
        return 4
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

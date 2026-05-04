"""
Zero-shot design-pattern hints via GraphCodeBERT embeddings (cosine similarity).
Aligned with Java HttpAiAnalyzeResponse (snake_case JSON).
"""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass
from typing import Any

import torch
import torch.nn.functional as F
from transformers import AutoModel, AutoTokenizer


PATTERN_PROMPTS: dict[str, str] = {
    "STRATEGY": "Java code implementing the Strategy design pattern with interchangeable algorithms.",
    "TEMPLATE_METHOD": "Java code implementing the Template Method design pattern with algorithm skeleton.",
    "FACTORY_METHOD": "Java code implementing the Factory Method design pattern creating objects without exposing instantiation.",
}


@dataclass
class AnalyzePayload:
    trace_id: uuid.UUID | None
    project_id: str | None
    entity_id: str
    language: str | None
    entity_type: str | None
    pattern_scope: list[str]
    source_code: str
    context: dict[str, Any] | None


class GraphCodeBertAnalyzer:
    def __init__(self, model_name: str, max_length: int) -> None:
        self._model_name = model_name
        self._max_length = max_length
        self._tokenizer: AutoTokenizer | None = None
        self._model: AutoModel | None = None
        self._pattern_cache: dict[str, torch.Tensor] = {}

    def _ensure_loaded(self) -> None:
        if self._model is not None:
            return
        self._tokenizer = AutoTokenizer.from_pretrained(self._model_name)
        self._model = AutoModel.from_pretrained(self._model_name)
        self._model.eval()

    def _embed_text(self, text: str) -> torch.Tensor:
        assert self._tokenizer is not None and self._model is not None
        inputs = self._tokenizer(
            text,
            return_tensors="pt",
            truncation=True,
            max_length=self._max_length,
            padding=True,
        )
        with torch.no_grad():
            out = self._model(**inputs)
            hidden = out.last_hidden_state
            pooled = hidden.mean(dim=1)
            return F.normalize(pooled, dim=1)

    def _pattern_embedding(self, label: str) -> torch.Tensor:
        if label not in self._pattern_cache:
            prompt = PATTERN_PROMPTS.get(label, f"Java code for {label} design pattern.")
            self._pattern_cache[label] = self._embed_text(prompt)
        return self._pattern_cache[label]

    def analyze(self, payload: AnalyzePayload, decision_threshold: float) -> dict[str, Any]:
        self._ensure_loaded()
        started = time.perf_counter()
        code_text = (payload.source_code or "").strip()
        if not code_text:
            code_text = "// empty"

        code_emb = self._embed_text(code_text)

        predictions: list[dict[str, Any]] = []
        scores: list[float] = []
        for label in payload.pattern_scope:
            pe = self._pattern_embedding(label)
            score = float((code_emb * pe).sum().item())
            scores.append(score)
            predictions.append(
                {
                    "label": label,
                    "score": score,
                    "decision": score >= decision_threshold,
                }
            )

        if not predictions:
            trace = payload.trace_id or uuid.uuid4()
            return _stub_like_response(
                trace,
                payload.entity_id,
                ms=int((time.perf_counter() - started) * 1000),
                predictions=[],
                predicted_labels=[],
                confidence=0.0,
                explanation="No supported labels in pattern_scope.",
            )

        best_idx = max(range(len(scores)), key=lambda i: scores[i])
        best = predictions[best_idx]
        predicted_labels = [p["label"] for p in predictions if p["decision"]]
        if not predicted_labels:
            predicted_labels = [best["label"]]

        trace = payload.trace_id or uuid.uuid4()
        ms = int((time.perf_counter() - started) * 1000)
        return _stub_like_response(
            trace,
            payload.entity_id,
            ms,
            predictions=predictions,
            predicted_labels=predicted_labels,
            confidence=float(best["score"]),
            explanation="GraphCodeBERT embedding similarity (zero-shot).",
        )


def _stub_like_response(
    trace_id: uuid.UUID,
    entity_id: str,
    ms: int,
    *,
    predictions: list[dict[str, Any]],
    predicted_labels: list[str],
    confidence: float,
    explanation: str,
) -> dict[str, Any]:
    return {
        "trace_id": str(trace_id),
        "entity_id": entity_id,
        "predictions": predictions,
        "predicted_labels": predicted_labels,
        "confidence": confidence,
        "explanation": explanation,
        "experiment_profile": "graphcodebert_zero_shot",
        "applied_thresholds": None,
        "timing": {"analysis_time_ms": ms},
    }


def analyze_stub(payload: AnalyzePayload) -> dict[str, Any]:
    """Fast path matching Java integration-test stub shape."""
    trace = payload.trace_id or uuid.uuid4()
    labels = payload.pattern_scope or ["STRATEGY"]
    predictions = [
        {"label": lb, "score": 0.87, "decision": True}
        for lb in labels[:3]
    ]
    return _stub_like_response(
        trace,
        payload.entity_id,
        1,
        predictions=predictions or [{"label": "STRATEGY", "score": 0.87, "decision": True}],
        predicted_labels=[p["label"] for p in predictions],
        confidence=0.87,
        explanation="Stub response (RMT_AI_BACKEND=stub).",
    )


def parse_payload(body: dict[str, Any]) -> AnalyzePayload:
    tid = body.get("trace_id")
    trace_uuid = uuid.UUID(str(tid)) if tid else None
    scope = list(body.get("pattern_scope") or [])
    if not scope:
        scope = ["STRATEGY", "TEMPLATE_METHOD", "FACTORY_METHOD"]
    return AnalyzePayload(
        trace_id=trace_uuid,
        project_id=body.get("project_id"),
        entity_id=str(body.get("entity_id", "")),
        language=body.get("language"),
        entity_type=body.get("entity_type"),
        pattern_scope=scope,
        source_code=str(body.get("source_code") or ""),
        context=body.get("context"),
    )

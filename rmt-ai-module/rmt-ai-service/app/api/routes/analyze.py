from typing import Any

from fastapi import APIRouter, Depends, HTTPException

from app.core.config import Settings, load_settings
from app.services.graphcodebert_analyze import (
    GraphCodeBertAnalyzer,
    analyze_stub,
    parse_payload,
)

router = APIRouter(tags=["analyze"])

_analyzer: GraphCodeBertAnalyzer | None = None


def get_settings() -> Settings:
    return load_settings()


def _analyzer_singleton(settings: Settings) -> GraphCodeBertAnalyzer:
    global _analyzer
    if _analyzer is None:
        _analyzer = GraphCodeBertAnalyzer(settings.model_name, settings.max_length)
    return _analyzer


@router.post("/analyze")
def analyze(body: dict[str, Any], settings: Settings = Depends(get_settings)) -> dict:
    payload = parse_payload(body)
    if not payload.entity_id:
        raise HTTPException(status_code=400, detail="entity_id required")

    backend = (settings.backend or "transformers").lower().strip()
    if backend == "stub":
        return analyze_stub(payload)

    analyzer = _analyzer_singleton(settings)
    return analyzer.analyze(payload, settings.decision_threshold)

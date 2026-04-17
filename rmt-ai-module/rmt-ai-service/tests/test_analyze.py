import pytest
from pydantic import ValidationError

from app.api.routes.analyze import analyze
from app.schemas.analyze import AnalyzeRequest
from tests.helpers import build_analyze_payload


def test_analyze_endpoint_returns_stubbed_contract() -> None:
    request = AnalyzeRequest.model_validate(build_analyze_payload())
    response = analyze(request)

    body = response.model_dump(mode="json")
    assert body["trace_id"] == "9ce0db76-b5ea-4722-8c1c-4d8a8a8250e4"
    assert body["entity_id"] == "src/main/java/foo/Bar.java::Bar::calculate"
    assert body["model"] == {"name": "graphcodebert-rmt-v1", "version": "1.0.0"}
    assert body["top_prediction"] == "STRATEGY"
    assert body["confidence"] == 0.87
    assert len(body["predictions"]) == 3
    assert body["predictions"][1] == {
        "label": "STRATEGY",
        "score": 0.87,
        "decision": True,
    }
    assert body["evidence"]["truncated"] is False
    assert body["evidence"]["window_strategy"] == "single-window"
    assert body["evidence"]["input_tokens"] > 0


def test_analyze_endpoint_rejects_duplicate_pattern_scope() -> None:
    payload = build_analyze_payload()
    payload["pattern_scope"] = ["STRATEGY", "STRATEGY"]

    with pytest.raises(ValidationError):
        AnalyzeRequest.model_validate(payload)

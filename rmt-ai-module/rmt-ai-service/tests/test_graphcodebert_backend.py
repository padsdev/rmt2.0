import os

import pytest

from app.core.config import AppliedThresholdSettings, ServiceSettings
from app.domain import PatternLabel
from app.main import create_app
from app.schemas.analyze import Evidence
from app.services.analyze_service import GraphCodeBertPreprocessor, GraphCodeBertScoreResult
from tests.conftest import LifecycleHttpClient


class FakeGraphCodeBertEncoder:
    def __init__(
        self,
        *,
        scores: dict[PatternLabel, float] | None = None,
        evidence: Evidence | None = None,
        model_name: str = "microsoft/graphcodebert-base",
        device: str = "cpu",
        max_length: int = 512,
    ) -> None:
        self.model_name = model_name
        self.device = device
        self.max_length = max_length
        self.supported_patterns = tuple(PatternLabel)
        self.calibrated_thresholds = None
        self.analysis_description = (
            "Zero-shot GraphCodeBERT embedding similarity against textual pattern descriptions. "
            "Scores are cosine-derived and thresholded per pattern; this is not supervised fine-tuning."
        )
        self._scores = scores or {
            PatternLabel.TEMPLATE_METHOD: 0.61,
            PatternLabel.STRATEGY: 0.42,
            PatternLabel.FACTORY_METHOD: 0.51,
        }
        self._evidence = evidence or Evidence(
            truncated=True,
            input_tokens=731,
            window_strategy="single-window-truncate-tail",
            features_used=["code"],
        )

    def score(self, request) -> GraphCodeBertScoreResult:
        return GraphCodeBertScoreResult(
            scores={label: self._scores[label] for label in request.pattern_scope},
            evidence=self._evidence,
        )


class FakeFineTunedGraphCodeBertEncoder(FakeGraphCodeBertEncoder):
    def __init__(self) -> None:
        super().__init__(
            scores={
                PatternLabel.TEMPLATE_METHOD: 0.83,
                PatternLabel.STRATEGY: 0.21,
                PatternLabel.FACTORY_METHOD: 0.55,
            },
            model_name="microsoft/graphcodebert-base-finetuned",
        )
        self.calibrated_thresholds = AppliedThresholdSettings(
            template_method=0.8,
            strategy=0.5,
            factory_method=0.6,
        )
        self.analysis_description = (
            "Supervised GraphCodeBERT fine-tuning with heuristic-derived operational labels from grounded shadow "
            "JSONL. Scores come from sigmoid multilabel logits and calibrated thresholds; this is not absolute "
            "ground truth."
        )


class FakeTokenizer:
    def __call__(
        self,
        text: str,
        *,
        add_special_tokens: bool = True,
        truncation: bool = False,
        max_length: int | None = None,
        return_attention_mask: bool = True,
        padding: bool = False,
        return_tensors: str | None = None,
    ) -> dict:
        del padding
        del return_tensors

        token_count = len(text.split()) + (2 if add_special_tokens else 0)
        input_ids = list(range(token_count))
        if truncation and max_length is not None:
            input_ids = input_ids[:max_length]

        payload = {"input_ids": input_ids}
        if return_attention_mask:
            payload["attention_mask"] = [1] * len(input_ids)
        return payload


def test_health_endpoint_reports_real_backend_loaded_when_loader_succeeds(analyze_payload: dict) -> None:
    app = create_app(
        settings_override=ServiceSettings(backend_mode="graphcodebert"),
        graphcodebert_loader=lambda settings: FakeGraphCodeBertEncoder(),
    )

    with LifecycleHttpClient(app) as client:
        health_response = client.get("/health")
        assert health_response.status_code == 200
        assert health_response.json()["status"] == "ok"
        assert health_response.json()["model_loaded"] is True
        assert health_response.json()["model_name"] == "microsoft/graphcodebert-base"

        analyze_response = client.post("/api/v1/analyze", json=analyze_payload)
        assert analyze_response.status_code == 200


def test_health_endpoint_reports_degraded_when_graphcodebert_load_fails(analyze_payload: dict) -> None:
    def failing_loader(settings: ServiceSettings):
        raise RuntimeError("intentional graphcodebert load failure")

    app = create_app(
        settings_override=ServiceSettings(backend_mode="graphcodebert"),
        graphcodebert_loader=failing_loader,
    )

    with LifecycleHttpClient(app) as client:
        health_response = client.get("/health")
        assert health_response.status_code == 200
        assert health_response.json()["status"] == "degraded"
        assert health_response.json()["model_loaded"] is False

        analyze_response = client.post("/api/v1/analyze", json=analyze_payload)
        assert analyze_response.status_code == 503
        assert "graphcodebert backend is unavailable" in analyze_response.json()["detail"]


def test_model_info_endpoint_exposes_runtime_backend_metadata() -> None:
    app = create_app(
        settings_override=ServiceSettings(backend_mode="graphcodebert"),
        graphcodebert_loader=lambda settings: FakeGraphCodeBertEncoder(device="cpu", max_length=512),
    )

    with LifecycleHttpClient(app) as client:
        response = client.get("/api/v1/model/info")

    assert response.status_code == 200
    assert response.json() == {
        "backend": "graphcodebert",
        "model_name": "microsoft/graphcodebert-base",
        "model_loaded": True,
        "device": "cpu",
        "max_length": 512,
        "supported_patterns": ["TEMPLATE_METHOD", "STRATEGY", "FACTORY_METHOD"],
    }


def test_graphcodebert_preprocessor_reports_token_count_and_truncation(analyze_payload: dict) -> None:
    tokenizer = FakeTokenizer()
    preprocessor = GraphCodeBertPreprocessor(tokenizer, max_length=5)
    request = analyze_payload.copy()
    request["source_code"] = "one two three four five six seven eight nine ten"

    encoded, evidence = preprocessor.prepare(type("Request", (), {"source_code": request["source_code"]})())

    assert evidence.input_tokens == 12
    assert evidence.truncated is True
    assert evidence.window_strategy == "single-window-truncate-tail"
    assert evidence.features_used == ["code"]
    assert len(encoded["input_ids"]) == 5


def test_graphcodebert_backend_can_be_mocked_for_unit_tests(analyze_payload: dict) -> None:
    app = create_app(
        settings_override=ServiceSettings(backend_mode="graphcodebert"),
        graphcodebert_loader=lambda settings: FakeGraphCodeBertEncoder(),
    )

    with LifecycleHttpClient(app) as client:
        response = client.post("/api/v1/analyze", json=analyze_payload)

    assert response.status_code == 200
    body = response.json()
    assert body["model"] == {"name": "microsoft/graphcodebert-base", "version": "1.0.0"}
    assert body["predicted_labels"] == ["TEMPLATE_METHOD", "FACTORY_METHOD"]
    assert body["top_prediction"] == "TEMPLATE_METHOD"
    assert body["confidence"] == 0.61
    assert len(body["predictions"]) == 3
    assert body["predictions"][0] == {
        "label": "TEMPLATE_METHOD",
        "score": 0.61,
        "decision": True,
    }
    assert body["evidence"] == {
        "truncated": True,
        "input_tokens": 731,
        "window_strategy": "single-window-truncate-tail",
        "features_used": ["code"],
    }


def test_finetuned_graphcodebert_backend_uses_calibrated_thresholds(analyze_payload: dict) -> None:
    app = create_app(
        settings_override=ServiceSettings(backend_mode="graphcodebert_finetuned"),
        graphcodebert_finetuned_loader=lambda settings: FakeFineTunedGraphCodeBertEncoder(),
    )

    with LifecycleHttpClient(app) as client:
        response = client.post("/api/v1/analyze", json=analyze_payload)

    assert response.status_code == 200
    body = response.json()
    assert body["model"] == {"name": "microsoft/graphcodebert-base-finetuned", "version": "1.0.0"}
    assert body["predicted_labels"] == ["TEMPLATE_METHOD"]
    assert body["top_prediction"] == "TEMPLATE_METHOD"
    assert body["applied_thresholds"] == {
        "template_method": 0.8,
        "strategy": 0.5,
        "factory_method": 0.6,
    }
    assert "heuristic-derived operational labels" in body["explanation"]


@pytest.mark.skipif(
    os.getenv("RUN_REAL_MODEL_TESTS") != "true",
    reason="real GraphCodeBERT smoke tests are disabled by default",
)
def test_graphcodebert_real_smoke(analyze_payload: dict) -> None:
    pytest.importorskip("torch")
    pytest.importorskip("transformers")

    app = create_app(
        settings_override=ServiceSettings(
            backend_mode="graphcodebert",
        )
    )

    with LifecycleHttpClient(app) as client:
        health_response = client.get("/health")
        assert health_response.status_code == 200
        assert health_response.json()["model_loaded"] is True

        info_response = client.get("/api/v1/model/info")
        assert info_response.status_code == 200
        assert info_response.json()["backend"] == "graphcodebert"

        analyze_response = client.post("/api/v1/analyze", json=analyze_payload)
        assert analyze_response.status_code == 200
        body = analyze_response.json()
        assert len(body["predictions"]) == len(analyze_payload["pattern_scope"])
        assert body["evidence"]["input_tokens"] > 0

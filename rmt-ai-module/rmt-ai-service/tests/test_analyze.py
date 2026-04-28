from tests.conftest import LifecycleHttpClient
from app.core.config import ServiceSettings
from app.schemas.analyze import AnalyzeRequest
from app.services.analyze_service import create_analyze_service


def test_analyze_endpoint_returns_stubbed_multilabel_contract(
    client: LifecycleHttpClient,
    analyze_payload: dict,
) -> None:
    response = client.post("/api/v1/analyze", json=analyze_payload)

    assert response.status_code == 200
    body = response.json()
    assert body["trace_id"] == "9ce0db76-b5ea-4722-8c1c-4d8a8a8250e4"
    assert body["entity_id"] == "src/main/java/foo/Bar.java::Bar::calculate"
    assert body["model"] == {"name": "graphcodebert-rmt-v1", "version": "1.0.0"}
    assert body["predicted_labels"] == ["STRATEGY"]
    assert body["top_prediction"] == "STRATEGY"
    assert body["confidence"] == 0.87
    assert body["experiment_profile"] == "default"
    assert body["applied_thresholds"] == {
        "template_method": 0.5,
        "strategy": 0.55,
        "factory_method": 0.5,
    }
    assert body["timing"]["analysis_time_ms"] >= 0
    assert len(body["predictions"]) == 3
    assert body["predictions"][1] == {
        "label": "STRATEGY",
        "score": 0.87,
        "decision": True,
    }
    assert body["evidence"]["truncated"] is False
    assert body["evidence"]["window_strategy"] == "single-window"
    assert body["evidence"]["features_used"] == ["code", "metadata", "structural_hints"]
    assert body["evidence"]["input_tokens"] > 0


def test_health_endpoint_exposes_experiment_profile_and_thresholds(
    client: LifecycleHttpClient,
) -> None:
    response = client.get("/health")

    assert response.status_code == 200
    body = response.json()
    assert body["experiment_profile"] == "default"
    assert body["applied_thresholds"] == {
        "template_method": 0.5,
        "strategy": 0.55,
        "factory_method": 0.5,
    }


def test_analyze_endpoint_allows_no_positive_label_without_forcing_top_prediction(
    client: LifecycleHttpClient,
    analyze_payload: dict,
) -> None:
    analyze_payload["pattern_scope"] = ["TEMPLATE_METHOD", "FACTORY_METHOD"]

    response = client.post("/api/v1/analyze", json=analyze_payload)

    assert response.status_code == 200
    body = response.json()
    assert body["predicted_labels"] == []
    assert body["top_prediction"] is None
    assert body["confidence"] == 0.22


def test_low_template_threshold_profile_can_flip_template_method_prediction(
    analyze_payload: dict,
) -> None:
    service = create_analyze_service(
        settings=ServiceSettings(
            experiment_profile="low-template-threshold",
        )
    )

    response = service.analyze(AnalyzeRequest.model_validate(analyze_payload))

    assert response.experiment_profile == "low-template-threshold"
    assert response.applied_thresholds.template_method == 0.1
    assert "TEMPLATE_METHOD" in [label.value for label in response.predicted_labels]


def test_analyze_endpoint_rejects_invalid_payload_with_http_422(
    client: LifecycleHttpClient,
    analyze_payload: dict,
) -> None:
    analyze_payload["pattern_scope"] = ["STRATEGY", "STRATEGY"]

    response = client.post("/api/v1/analyze", json=analyze_payload)

    assert response.status_code == 422
    body = response.json()
    assert body["detail"][0]["type"] == "value_error"

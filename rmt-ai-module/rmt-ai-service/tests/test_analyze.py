from tests.conftest import LifecycleHttpClient


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


def test_analyze_endpoint_rejects_invalid_payload_with_http_422(
    client: LifecycleHttpClient,
    analyze_payload: dict,
) -> None:
    analyze_payload["pattern_scope"] = ["STRATEGY", "STRATEGY"]

    response = client.post("/api/v1/analyze", json=analyze_payload)

    assert response.status_code == 422
    body = response.json()
    assert body["detail"][0]["type"] == "value_error"

from app.api.routes.health import health


def test_health_endpoint_returns_expected_payload() -> None:
    response = health()

    assert response.model_dump() == {"status": "ok", "model_loaded": True, "model_name": "graphcodebert-rmt-v1"}

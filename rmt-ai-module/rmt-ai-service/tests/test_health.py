from app.core.runtime import ServiceStatus
from tests.conftest import LifecycleHttpClient


def test_health_endpoint_reflects_stub_runtime_state(client: LifecycleHttpClient) -> None:
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {
        "status": "degraded",
        "model_loaded": False,
        "model_name": "graphcodebert-rmt-v1",
    }


def test_runtime_returns_to_starting_after_shutdown(app) -> None:
    assert app.state.runtime.status == ServiceStatus.STARTING

    with LifecycleHttpClient(app) as client:
        response = client.get("/health")
        assert response.status_code == 200
        assert app.state.runtime.status == ServiceStatus.DEGRADED

    assert app.state.runtime.status == ServiceStatus.STARTING

import asyncio
from collections.abc import Iterator

import httpx
import pytest

from app.main import create_app


class LifecycleHttpClient:
    def __init__(self, app) -> None:
        self.app = app
        self.base_url = "http://testserver"
        self._transport = httpx.ASGITransport(app=app)
        self._lifespan = app.router.lifespan_context(app)
        self._loop: asyncio.AbstractEventLoop | None = None
        self._client: httpx.AsyncClient | None = None

    def __enter__(self) -> "LifecycleHttpClient":
        self._loop = asyncio.new_event_loop()
        self._client = httpx.AsyncClient(
            transport=self._transport,
            base_url=self.base_url,
        )
        self._loop.run_until_complete(self._lifespan.__aenter__())
        self._loop.run_until_complete(self._client.__aenter__())
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        assert self._loop is not None
        assert self._client is not None

        try:
            self._loop.run_until_complete(self._client.__aexit__(exc_type, exc, tb))
            self._loop.run_until_complete(self._lifespan.__aexit__(exc_type, exc, tb))
        finally:
            self._loop.close()
            self._loop = None
            self._client = None

    def request(self, method: str, url: str, **kwargs):
        assert self._loop is not None
        assert self._client is not None
        return self._loop.run_until_complete(self._client.request(method, url, **kwargs))

    def get(self, url: str, **kwargs):
        return self.request("GET", url, **kwargs)

    def post(self, url: str, **kwargs):
        return self.request("POST", url, **kwargs)


@pytest.fixture
def app():
    return create_app()


@pytest.fixture
def client(app) -> Iterator[LifecycleHttpClient]:
    assert app.state.runtime.startup_complete is False

    with LifecycleHttpClient(app) as test_client:
        assert app.state.runtime.startup_complete is True
        yield test_client

    assert app.state.runtime.startup_complete is False


@pytest.fixture
def analyze_payload() -> dict:
    return {
        "trace_id": "9ce0db76-b5ea-4722-8c1c-4d8a8a8250e4",
        "project_id": "project-17",
        "entity_id": "src/main/java/foo/Bar.java::Bar::calculate",
        "language": "java",
        "entity_type": "method",
        "pattern_scope": ["TEMPLATE_METHOD", "STRATEGY", "FACTORY_METHOD"],
        "source_code": "public class Bar { void calculate() { if (flag) run(); } }",
        "context": {
            "file_path": "src/main/java/foo/Bar.java",
            "package_name": "foo",
            "class_name": "Bar",
            "method_name": "calculate",
            "super_class": "BaseBar",
            "interfaces": ["Rule"],
            "imports": ["java.util.*"],
            "metrics": {"loc": 87, "cc": 12, "dit": 2},
            "structural_hints": {
                "has_switch": True,
                "has_factory_calls": False,
                "uses_inheritance": True,
                "uses_composition": True,
            },
        },
    }

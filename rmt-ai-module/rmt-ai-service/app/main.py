from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.routes.analyze import router as analyze_router
from app.api.routes.health import router as health_router
from app.core.config import settings
from app.core.runtime import ServiceRuntime
from app.services.analyze_service import create_analyze_service


def _build_app_description() -> str:
    return (
        "Milestone 1 scaffold for the RMT AI service. "
        "This revision keeps `/health` and `/api/v1/analyze` available, "
        "uses a replaceable stub backend instead of real inference, and "
        "intentionally defers `/api/v1/analyze/batch` and `/api/v1/model/info`."
    )


def create_app() -> FastAPI:
    runtime = ServiceRuntime(
        model_name=settings.model_name,
        model_version=settings.model_version,
        backend_mode=settings.backend_mode,
    )

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        runtime.mark_starting()
        app.state.analyze_service = create_analyze_service(settings=settings)

        # Milestone 1 exposes a stub backend only; no model checkpoint is loaded yet.
        runtime.mark_started(model_loaded=False)
        try:
            yield
        finally:
            runtime.mark_stopped()

    app = FastAPI(
        title=settings.service_name,
        version="0.1.0",
        description=_build_app_description(),
        lifespan=lifespan,
    )
    app.state.runtime = runtime
    app.include_router(health_router)
    app.include_router(analyze_router)
    return app


app = create_app()

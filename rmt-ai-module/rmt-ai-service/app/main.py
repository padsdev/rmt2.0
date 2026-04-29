from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.routes.analyze import router as analyze_router
from app.api.routes.health import router as health_router
from app.api.routes.model_info import router as model_info_router
from app.core.config import ServiceSettings
from app.core.config import settings
from app.core.runtime import ServiceRuntime
from app.services.analyze_service import (
    FineTunedGraphCodeBertLoader,
    GraphCodeBertLoader,
    initialize_analyze_service,
)


def _build_app_description() -> str:
    return (
        "RMT AI service with a switchable stub backend and an optional GraphCodeBERT "
        "zero-shot embedding backend for shadow-mode experiments."
    )


def create_app(
    *,
    settings_override: ServiceSettings | None = None,
    graphcodebert_loader: GraphCodeBertLoader | None = None,
    graphcodebert_finetuned_loader: FineTunedGraphCodeBertLoader | None = None,
) -> FastAPI:
    active_settings = settings_override or settings
    runtime = ServiceRuntime(
        model_name=active_settings.resolved_model_name(),
        model_version=active_settings.model_version,
        backend_mode=active_settings.backend_mode,
        experiment_profile=active_settings.experiment_profile,
        applied_thresholds=active_settings.resolved_thresholds().model_dump(),
        max_length=active_settings.model_max_length,
        supported_patterns=[label.value for label in active_settings.supported_labels],
    )

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        runtime.mark_starting()
        analyze_service, runtime_info = initialize_analyze_service(
            settings=active_settings,
            graphcodebert_loader=graphcodebert_loader,
            graphcodebert_finetuned_loader=graphcodebert_finetuned_loader,
        )
        app.state.analyze_service = analyze_service
        runtime.model_name = runtime_info.model_name
        runtime.backend_mode = runtime_info.backend
        runtime.device = runtime_info.device
        runtime.max_length = runtime_info.max_length
        runtime.supported_patterns = [label.value for label in runtime_info.supported_patterns]
        runtime.mark_started(
            model_loaded=runtime_info.model_loaded,
            startup_error=runtime_info.startup_error,
        )
        try:
            yield
        finally:
            runtime.mark_stopped()

    app = FastAPI(
        title=active_settings.service_name,
        version="0.1.0",
        description=_build_app_description(),
        lifespan=lifespan,
    )
    app.state.runtime = runtime
    app.include_router(health_router)
    app.include_router(analyze_router)
    app.include_router(model_info_router)
    return app


app = create_app()

from typing import Annotated

from fastapi import APIRouter, Depends

from app.api.dependencies import get_runtime
from app.core.runtime import ServiceRuntime
from app.schemas.health import HealthResponse

router = APIRouter(tags=["health"])


@router.get("/health", response_model=HealthResponse)
async def health(runtime: Annotated[ServiceRuntime, Depends(get_runtime)]) -> HealthResponse:
    return HealthResponse(
        status=runtime.status,
        model_loaded=runtime.model_loaded,
        model_name=runtime.model_name,
    )

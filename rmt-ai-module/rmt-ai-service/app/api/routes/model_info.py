from typing import Annotated

from fastapi import APIRouter, Depends

from app.api.dependencies import get_runtime
from app.core.runtime import ServiceRuntime
from app.schemas.model_info import ModelInfoResponse

router = APIRouter(prefix="/api/v1", tags=["model"])


@router.get("/model/info", response_model=ModelInfoResponse)
async def model_info(runtime: Annotated[ServiceRuntime, Depends(get_runtime)]) -> ModelInfoResponse:
    return ModelInfoResponse(
        backend=runtime.backend_mode,
        model_name=runtime.model_name,
        model_loaded=runtime.model_loaded,
        device=runtime.device,
        max_length=runtime.max_length,
        supported_patterns=runtime.supported_patterns or [],
    )

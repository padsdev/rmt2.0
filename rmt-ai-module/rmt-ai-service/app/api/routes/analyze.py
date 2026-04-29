from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException

from app.api.dependencies import get_analyze_service
from app.schemas.analyze import AnalyzeRequest, AnalyzeResponse
from app.services.analyze_service import AnalyzeService, ModelBackendUnavailableError

router = APIRouter(prefix="/api/v1", tags=["analyze"])


@router.post("/analyze", response_model=AnalyzeResponse)
async def analyze(
    request: AnalyzeRequest,
    analyze_service: Annotated[AnalyzeService, Depends(get_analyze_service)],
) -> AnalyzeResponse:
    try:
        return analyze_service.analyze(request)
    except ModelBackendUnavailableError as exception:
        raise HTTPException(status_code=503, detail=str(exception)) from exception

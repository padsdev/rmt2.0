from typing import Annotated

from fastapi import APIRouter, Depends

from app.api.dependencies import get_analyze_service
from app.schemas.analyze import AnalyzeRequest, AnalyzeResponse
from app.services.analyze_service import AnalyzeService

router = APIRouter(prefix="/api/v1", tags=["analyze"])


@router.post("/analyze", response_model=AnalyzeResponse)
async def analyze(
    request: AnalyzeRequest,
    analyze_service: Annotated[AnalyzeService, Depends(get_analyze_service)],
) -> AnalyzeResponse:
    return analyze_service.analyze(request)

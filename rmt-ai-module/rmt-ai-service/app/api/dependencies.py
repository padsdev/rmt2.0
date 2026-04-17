from fastapi import Request

from app.core.runtime import ServiceRuntime
from app.services.analyze_service import AnalyzeService


async def get_runtime(request: Request) -> ServiceRuntime:
    return request.app.state.runtime


async def get_analyze_service(request: Request) -> AnalyzeService:
    return request.app.state.analyze_service

from fastapi import FastAPI

from app.api.routes.analyze import router as analyze_router
from app.api.routes.health import router as health_router
from app.core.config import settings

app = FastAPI(title=settings.service_name, version="0.1.0")
app.include_router(health_router)
app.include_router(analyze_router)

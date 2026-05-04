from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.routes import analyze as analyze_routes
from app.api.routes import health as health_routes
from app.core.config import load_settings


@asynccontextmanager
async def lifespan(app: FastAPI):
    load_settings()
    yield


app = FastAPI(title="RMT AI Service", lifespan=lifespan)

app.include_router(health_routes.router)
app.include_router(analyze_routes.router, prefix="/api/v1")


@app.get("/api/v1/model-info")
def model_info() -> dict:
    s = load_settings()
    return {
        "backend": s.backend,
        "model_name": s.model_name,
        "decision_threshold": s.decision_threshold,
    }

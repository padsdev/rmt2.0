from pydantic import BaseModel

from app.core.runtime import ServiceStatus


class HealthResponse(BaseModel):
    status: ServiceStatus
    model_loaded: bool
    model_name: str

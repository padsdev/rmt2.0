from pydantic import BaseModel

from app.core.runtime import ServiceStatus
from app.schemas.analyze import AppliedThresholds


class HealthResponse(BaseModel):
    status: ServiceStatus
    model_loaded: bool
    model_name: str
    experiment_profile: str
    applied_thresholds: AppliedThresholds

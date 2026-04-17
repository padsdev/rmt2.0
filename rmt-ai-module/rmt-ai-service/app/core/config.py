from typing import Literal

from pydantic import BaseModel, Field

from app.domain import SUPPORTED_PATTERN_LABELS, PatternLabel


class ServiceSettings(BaseModel):
    service_name: str = "rmt-ai-service"
    model_name: str = "graphcodebert-rmt-v1"
    model_version: str = "1.0.0"
    backend_mode: Literal["stub"] = "stub"
    supported_labels: tuple[PatternLabel, ...] = Field(default=SUPPORTED_PATTERN_LABELS)


settings = ServiceSettings()

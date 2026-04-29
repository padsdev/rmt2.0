from pydantic import BaseModel, Field


class ModelInfoResponse(BaseModel):
    backend: str
    model_name: str
    model_loaded: bool
    device: str | None = None
    max_length: int = Field(ge=1)
    supported_patterns: list[str]

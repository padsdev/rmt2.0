from pydantic import BaseModel, Field


class ServiceSettings(BaseModel):
    service_name: str = "rmt-ai-service"
    model_name: str = "graphcodebert-rmt-v1"
    model_version: str = "1.0.0"
    model_loaded: bool = True
    supported_labels: tuple[str, ...] = (
        "TEMPLATE_METHOD",
        "STRATEGY",
        "FACTORY_METHOD",
    )


settings = ServiceSettings()

import os
from typing import Literal

from pydantic import BaseModel, Field

from app.domain import SUPPORTED_PATTERN_LABELS, PatternLabel

ExperimentProfile = Literal["default", "low-template-threshold", "per-pattern-threshold"]

DEFAULT_THRESHOLDS: dict[PatternLabel, float] = {
    PatternLabel.TEMPLATE_METHOD: 0.50,
    PatternLabel.STRATEGY: 0.55,
    PatternLabel.FACTORY_METHOD: 0.50,
}

LOW_TEMPLATE_THRESHOLDS: dict[PatternLabel, float] = {
    **DEFAULT_THRESHOLDS,
    PatternLabel.TEMPLATE_METHOD: 0.10,
}


class AppliedThresholdSettings(BaseModel):
    template_method: float = Field(ge=0.0, le=1.0)
    strategy: float = Field(ge=0.0, le=1.0)
    factory_method: float = Field(ge=0.0, le=1.0)


class ServiceSettings(BaseModel):
    service_name: str = "rmt-ai-service"
    model_name: str = "graphcodebert-rmt-v1"
    model_version: str = "1.0.0"
    backend_mode: Literal["stub"] = "stub"
    experiment_profile: ExperimentProfile = "default"
    template_method_threshold: float | None = Field(default=None, ge=0.0, le=1.0)
    strategy_threshold: float | None = Field(default=None, ge=0.0, le=1.0)
    factory_method_threshold: float | None = Field(default=None, ge=0.0, le=1.0)
    supported_labels: tuple[PatternLabel, ...] = Field(default=SUPPORTED_PATTERN_LABELS)

    def resolved_thresholds(self) -> AppliedThresholdSettings:
        if self.experiment_profile == "default":
            threshold_map = DEFAULT_THRESHOLDS
        elif self.experiment_profile == "low-template-threshold":
            threshold_map = LOW_TEMPLATE_THRESHOLDS
        else:
            threshold_map = {
                PatternLabel.TEMPLATE_METHOD: self.template_method_threshold
                if self.template_method_threshold is not None
                else DEFAULT_THRESHOLDS[PatternLabel.TEMPLATE_METHOD],
                PatternLabel.STRATEGY: self.strategy_threshold
                if self.strategy_threshold is not None
                else DEFAULT_THRESHOLDS[PatternLabel.STRATEGY],
                PatternLabel.FACTORY_METHOD: self.factory_method_threshold
                if self.factory_method_threshold is not None
                else DEFAULT_THRESHOLDS[PatternLabel.FACTORY_METHOD],
            }

        return AppliedThresholdSettings(
            template_method=threshold_map[PatternLabel.TEMPLATE_METHOD],
            strategy=threshold_map[PatternLabel.STRATEGY],
            factory_method=threshold_map[PatternLabel.FACTORY_METHOD],
        )


def _env_str(name: str, default: str) -> str:
    return os.getenv(name, default)


def _env_float(name: str) -> float | None:
    raw_value = os.getenv(name)
    if raw_value is None or raw_value == "":
        return None
    return float(raw_value)


def load_settings() -> ServiceSettings:
    return ServiceSettings(
        service_name=_env_str("RMT_AI_SERVICE_NAME", "rmt-ai-service"),
        model_name=_env_str("RMT_AI_MODEL_NAME", "graphcodebert-rmt-v1"),
        model_version=_env_str("RMT_AI_MODEL_VERSION", "1.0.0"),
        backend_mode=_env_str("RMT_AI_BACKEND_MODE", "stub"),
        experiment_profile=_env_str("RMT_AI_EXPERIMENT_PROFILE", "default"),
        template_method_threshold=_env_float("RMT_AI_THRESHOLD_TEMPLATE_METHOD"),
        strategy_threshold=_env_float("RMT_AI_THRESHOLD_STRATEGY"),
        factory_method_threshold=_env_float("RMT_AI_THRESHOLD_FACTORY_METHOD"),
    )


settings = load_settings()

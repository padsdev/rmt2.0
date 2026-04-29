from dataclasses import dataclass
from enum import Enum


class ServiceStatus(str, Enum):
    STARTING = "starting"
    DEGRADED = "degraded"
    OK = "ok"


@dataclass
class ServiceRuntime:
    model_name: str
    model_version: str
    backend_mode: str
    experiment_profile: str
    applied_thresholds: dict[str, float]
    device: str | None = None
    max_length: int = 512
    supported_patterns: list[str] | None = None
    startup_error: str | None = None
    model_loaded: bool = False
    startup_complete: bool = False

    @property
    def status(self) -> ServiceStatus:
        if not self.startup_complete:
            return ServiceStatus.STARTING
        if self.model_loaded:
            return ServiceStatus.OK
        return ServiceStatus.DEGRADED

    def mark_starting(self) -> None:
        self.startup_complete = False
        self.model_loaded = False
        self.startup_error = None

    def mark_started(self, *, model_loaded: bool, startup_error: str | None = None) -> None:
        self.startup_complete = True
        self.model_loaded = model_loaded
        self.startup_error = startup_error

    def mark_stopped(self) -> None:
        self.startup_complete = False
        self.model_loaded = False

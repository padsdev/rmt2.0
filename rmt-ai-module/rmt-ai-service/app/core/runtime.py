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

    def mark_started(self, *, model_loaded: bool) -> None:
        self.startup_complete = True
        self.model_loaded = model_loaded

    def mark_stopped(self) -> None:
        self.startup_complete = False
        self.model_loaded = False

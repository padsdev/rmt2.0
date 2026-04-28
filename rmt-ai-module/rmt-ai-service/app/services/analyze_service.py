from dataclasses import dataclass
from time import perf_counter_ns
from typing import Protocol

from app.core.config import ServiceSettings
from app.domain import PatternLabel
from app.schemas.analyze import AnalyzeRequest, AnalyzeResponse, AppliedThresholds, Evidence, ModelInfo, Prediction, TimingInfo


@dataclass(frozen=True)
class StubPredictionRule:
    score: float
    threshold: float


@dataclass(frozen=True)
class AnalysisResult:
    predictions: list[Prediction]
    predicted_labels: list[PatternLabel]
    top_prediction: PatternLabel | None
    confidence: float
    explanation: str
    evidence: Evidence
    experiment_profile: str
    applied_thresholds: AppliedThresholds
    analysis_time_ms: int


class AnalysisBackend(Protocol):
    def analyze(self, request: AnalyzeRequest) -> AnalysisResult:
        ...


class StubAnalysisBackend:
    def __init__(self, settings: ServiceSettings) -> None:
        resolved_thresholds = settings.resolved_thresholds()
        self._prediction_rules = {
            PatternLabel.TEMPLATE_METHOD: StubPredictionRule(score=0.14, threshold=resolved_thresholds.template_method),
            PatternLabel.STRATEGY: StubPredictionRule(score=0.87, threshold=resolved_thresholds.strategy),
            PatternLabel.FACTORY_METHOD: StubPredictionRule(score=0.22, threshold=resolved_thresholds.factory_method),
        }
        self._experiment_profile = settings.experiment_profile
        self._applied_thresholds = AppliedThresholds(
            template_method=resolved_thresholds.template_method,
            strategy=resolved_thresholds.strategy,
            factory_method=resolved_thresholds.factory_method,
        )

    def analyze(self, request: AnalyzeRequest) -> AnalysisResult:
        started_at = perf_counter_ns()
        predictions = []
        for label in request.pattern_scope:
            rule = self._prediction_rules[label]
            predictions.append(
                Prediction(
                    label=label,
                    score=rule.score,
                    decision=rule.score >= rule.threshold,
                )
            )

        predicted_labels = [prediction.label for prediction in predictions if prediction.decision]
        top_prediction = None
        if predicted_labels:
            top_prediction = max(
                (prediction for prediction in predictions if prediction.decision),
                key=lambda prediction: prediction.score,
            ).label

        analysis_time_ms = int((perf_counter_ns() - started_at) / 1_000_000)
        return AnalysisResult(
            predictions=predictions,
            predicted_labels=predicted_labels,
            top_prediction=top_prediction,
            confidence=max(prediction.score for prediction in predictions),
            explanation=(
                "Stub response for Milestone 1 contract validation. "
                "Real GraphCodeBERT inference and model registry integration remain deferred."
            ),
            evidence=Evidence(
                truncated=False,
                input_tokens=self._estimate_input_tokens(request.source_code),
                window_strategy="single-window",
                features_used=self._features_used(request=request),
            ),
            experiment_profile=self._experiment_profile,
            applied_thresholds=self._applied_thresholds,
            analysis_time_ms=analysis_time_ms,
        )

    @staticmethod
    def _estimate_input_tokens(source_code: str) -> int:
        return max(1, len(source_code.split()))

    @staticmethod
    def _features_used(request: AnalyzeRequest) -> list[str]:
        features = ["code", "metadata"]
        if request.context.structural_hints is not None:
            features.append("structural_hints")
        return features


class AnalyzeService:
    def __init__(self, backend: AnalysisBackend, *, model_name: str, model_version: str) -> None:
        self._backend = backend
        self._model_name = model_name
        self._model_version = model_version

    def analyze(self, request: AnalyzeRequest) -> AnalyzeResponse:
        result = self._backend.analyze(request)
        return AnalyzeResponse(
            trace_id=request.trace_id,
            entity_id=request.entity_id,
            model=ModelInfo(name=self._model_name, version=self._model_version),
            predictions=result.predictions,
            predicted_labels=result.predicted_labels,
            top_prediction=result.top_prediction,
            confidence=result.confidence,
            explanation=result.explanation,
            evidence=result.evidence,
            experiment_profile=result.experiment_profile,
            applied_thresholds=result.applied_thresholds,
            timing=TimingInfo(analysis_time_ms=result.analysis_time_ms),
        )


def create_analyze_service(*, settings: ServiceSettings) -> AnalyzeService:
    return AnalyzeService(
        backend=StubAnalysisBackend(settings),
        model_name=settings.model_name,
        model_version=settings.model_version,
    )

from dataclasses import dataclass
from typing import Protocol

from app.core.config import ServiceSettings
from app.domain import PatternLabel
from app.schemas.analyze import AnalyzeRequest, AnalyzeResponse, Evidence, ModelInfo, Prediction


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


class AnalysisBackend(Protocol):
    def analyze(self, request: AnalyzeRequest) -> AnalysisResult:
        ...


class StubAnalysisBackend:
    def __init__(self) -> None:
        self._prediction_rules = {
            PatternLabel.TEMPLATE_METHOD: StubPredictionRule(score=0.14, threshold=0.50),
            PatternLabel.STRATEGY: StubPredictionRule(score=0.87, threshold=0.55),
            PatternLabel.FACTORY_METHOD: StubPredictionRule(score=0.22, threshold=0.50),
        }

    def analyze(self, request: AnalyzeRequest) -> AnalysisResult:
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
        )


def create_analyze_service(*, settings: ServiceSettings) -> AnalyzeService:
    return AnalyzeService(
        backend=StubAnalysisBackend(),
        model_name=settings.model_name,
        model_version=settings.model_version,
    )

from dataclasses import dataclass

from app.core.config import settings
from app.schemas.analyze import AnalyzeRequest, AnalyzeResponse, Evidence, ModelInfo, Prediction


@dataclass(frozen=True)
class StubPredictionRule:
    score: float
    threshold: float


class AnalyzeService:
    _prediction_rules = {
        "TEMPLATE_METHOD": StubPredictionRule(score=0.14, threshold=0.50),
        "STRATEGY": StubPredictionRule(score=0.87, threshold=0.55),
        "FACTORY_METHOD": StubPredictionRule(score=0.22, threshold=0.50),
    }

    def analyze(self, request: AnalyzeRequest) -> AnalyzeResponse:
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

        top_prediction = max(predictions, key=lambda prediction: prediction.score)
        features_used = ["code", "metadata", "structural_hints"]
        if request.context.structural_hints is None:
            features_used = ["code", "metadata"]

        return AnalyzeResponse(
            trace_id=request.trace_id,
            entity_id=request.entity_id,
            model=ModelInfo(name=settings.model_name, version=settings.model_version),
            predictions=predictions,
            top_prediction=top_prediction.label,
            confidence=top_prediction.score,
            explanation=(
                "Stub response for API contract validation. "
                "No real GraphCodeBERT inference is executed in Milestone 1."
            ),
            evidence=Evidence(
                truncated=False,
                input_tokens=self._estimate_input_tokens(request.source_code),
                window_strategy="single-window",
                features_used=features_used,
            ),
        )

    @staticmethod
    def _estimate_input_tokens(source_code: str) -> int:
        return max(1, len(source_code.split()))


analyze_service = AnalyzeService()

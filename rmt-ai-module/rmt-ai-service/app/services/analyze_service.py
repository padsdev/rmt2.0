from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from time import perf_counter_ns
from typing import Any, Callable, Protocol

from app.core.config import AppliedThresholdSettings, ServiceSettings
from app.domain import PatternLabel
from app.schemas.analyze import AnalyzeRequest, AnalyzeResponse, AppliedThresholds, Evidence, ModelInfo, Prediction, TimingInfo
from training.model import create_classifier_from_encoder

PATTERN_DESCRIPTIONS: dict[PatternLabel, str] = {
    PatternLabel.TEMPLATE_METHOD: (
        "Template Method design pattern: a base class defines the skeleton of an algorithm while subclasses "
        "override specific steps."
    ),
    PatternLabel.STRATEGY: (
        "Strategy design pattern: behavior varies through interchangeable algorithms or policies selected at runtime."
    ),
    PatternLabel.FACTORY_METHOD: (
        "Factory Method design pattern: object creation is deferred to a dedicated method so subclasses or callers "
        "control which concrete product is created."
    ),
}


class ModelBackendUnavailableError(RuntimeError):
    """Raised when a configured non-stub backend is unavailable at runtime."""


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


@dataclass(frozen=True)
class BackendRuntimeInfo:
    backend: str
    model_name: str
    model_loaded: bool
    device: str | None
    max_length: int
    supported_patterns: tuple[PatternLabel, ...]
    startup_error: str | None = None


@dataclass(frozen=True)
class GraphCodeBertScoreResult:
    scores: dict[PatternLabel, float]
    evidence: Evidence


class AnalysisBackend(Protocol):
    def analyze(self, request: AnalyzeRequest) -> AnalysisResult:
        ...


class GraphCodeBertEncoder(Protocol):
    model_name: str
    device: str
    max_length: int
    supported_patterns: tuple[PatternLabel, ...]
    calibrated_thresholds: AppliedThresholdSettings | None
    analysis_description: str

    def score(self, request: AnalyzeRequest) -> GraphCodeBertScoreResult:
        ...


GraphCodeBertLoader = Callable[[ServiceSettings], GraphCodeBertEncoder]
FineTunedGraphCodeBertLoader = Callable[[ServiceSettings], GraphCodeBertEncoder]


class GraphCodeBertPreprocessor:
    def __init__(self, tokenizer: Any, *, max_length: int) -> None:
        self._tokenizer = tokenizer
        self._max_length = max_length

    def build_source_text(self, request: AnalyzeRequest) -> str:
        return request.source_code

    def prepare(self, request: AnalyzeRequest) -> tuple[dict[str, Any], Evidence]:
        source_text = self.build_source_text(request)
        input_tokens = self._count_input_tokens(source_text)
        truncated = input_tokens > self._max_length
        encoded = self._tokenizer(
            source_text,
            add_special_tokens=True,
            truncation=True,
            max_length=self._max_length,
            padding=False,
            return_tensors="pt",
        )
        evidence = Evidence(
            truncated=truncated,
            input_tokens=input_tokens,
            window_strategy="single-window-truncate-tail" if truncated else "single-window",
            features_used=["code"],
        )
        return encoded, evidence

    def _count_input_tokens(self, source_text: str) -> int:
        tokenized = self._tokenizer(
            source_text,
            add_special_tokens=True,
            truncation=False,
            return_attention_mask=False,
        )
        input_ids = tokenized["input_ids"]
        if isinstance(input_ids, list) and input_ids and isinstance(input_ids[0], list):
            input_ids = input_ids[0]
        if not isinstance(input_ids, list):
            return 0
        return len(input_ids)


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
                "Stub response for baseline and contract validation. "
                "No real model inference is performed in stub mode."
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


class UnavailableAnalysisBackend:
    def __init__(self, *, backend_name: str, detail: str) -> None:
        self._backend_name = backend_name
        self._detail = detail

    def analyze(self, request: AnalyzeRequest) -> AnalysisResult:
        raise ModelBackendUnavailableError(
            f"{self._backend_name} backend is unavailable: {self._detail}"
        )


class TransformersGraphCodeBertEncoder:
    def __init__(
        self,
        *,
        model_name: str,
        tokenizer: Any,
        model: Any,
        torch_module: Any,
        device: str,
        max_length: int,
        supported_patterns: tuple[PatternLabel, ...],
    ) -> None:
        self.model_name = model_name
        self.device = device
        self.max_length = max_length
        self.supported_patterns = supported_patterns
        self.calibrated_thresholds = None
        self.analysis_description = (
            "Zero-shot GraphCodeBERT embedding similarity against textual pattern descriptions. "
            "Scores are cosine-derived and thresholded per pattern; this is not supervised fine-tuning."
        )
        self._tokenizer = tokenizer
        self._model = model
        self._torch = torch_module
        self._preprocessor = GraphCodeBertPreprocessor(tokenizer, max_length=max_length)
        self._pattern_embeddings = self._build_pattern_embeddings()

    def score(self, request: AnalyzeRequest) -> GraphCodeBertScoreResult:
        encoded, evidence = self._preprocessor.prepare(request)
        code_embedding = self._embed_encoded_inputs(encoded)
        scores: dict[PatternLabel, float] = {}

        for label in request.pattern_scope:
            pattern_embedding = self._pattern_embeddings[label]
            cosine_similarity = float(self._torch.sum(code_embedding * pattern_embedding).item())
            normalized_score = max(0.0, min(1.0, (cosine_similarity + 1.0) / 2.0))
            scores[label] = normalized_score

        return GraphCodeBertScoreResult(scores=scores, evidence=evidence)

    def _build_pattern_embeddings(self) -> dict[PatternLabel, Any]:
        embeddings: dict[PatternLabel, Any] = {}
        for label in self.supported_patterns:
            description = PATTERN_DESCRIPTIONS[label]
            encoded = self._tokenizer(
                description,
                add_special_tokens=True,
                truncation=True,
                max_length=self.max_length,
                padding=False,
                return_tensors="pt",
            )
            embeddings[label] = self._embed_encoded_inputs(encoded)
        return embeddings

    def _embed_encoded_inputs(self, encoded_inputs: dict[str, Any]) -> Any:
        model_inputs = {key: value.to(self.device) for key, value in encoded_inputs.items()}
        with self._torch.no_grad():
            outputs = self._model(**model_inputs)
            last_hidden_state = outputs.last_hidden_state
            attention_mask = model_inputs["attention_mask"].unsqueeze(-1)
            masked_embeddings = last_hidden_state * attention_mask
            pooled_embeddings = masked_embeddings.sum(dim=1) / attention_mask.sum(dim=1).clamp(min=1)
            normalized_embeddings = self._torch.nn.functional.normalize(pooled_embeddings, p=2, dim=1)
        return normalized_embeddings[0]


class GraphCodeBertAnalysisBackend:
    def __init__(self, settings: ServiceSettings, *, encoder: GraphCodeBertEncoder) -> None:
        self._encoder = encoder
        self._experiment_profile = settings.experiment_profile
        self._thresholds = encoder.calibrated_thresholds or settings.resolved_thresholds()
        self._applied_thresholds = AppliedThresholds(
            template_method=self._thresholds.template_method,
            strategy=self._thresholds.strategy,
            factory_method=self._thresholds.factory_method,
        )

    def analyze(self, request: AnalyzeRequest) -> AnalysisResult:
        started_at = perf_counter_ns()
        score_result = self._encoder.score(request)
        predictions: list[Prediction] = []

        for label in request.pattern_scope:
            score = score_result.scores[label]
            threshold = self._threshold_for(label)
            predictions.append(
                Prediction(
                    label=label,
                    score=score,
                    decision=score >= threshold,
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
            explanation=self._encoder.analysis_description,
            evidence=score_result.evidence,
            experiment_profile=self._experiment_profile,
            applied_thresholds=self._applied_thresholds,
            analysis_time_ms=analysis_time_ms,
        )

    def _threshold_for(self, label: PatternLabel) -> float:
        if label == PatternLabel.TEMPLATE_METHOD:
            return self._thresholds.template_method
        if label == PatternLabel.STRATEGY:
            return self._thresholds.strategy
        return self._thresholds.factory_method


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


def _resolve_device(torch_module: Any, preference: str) -> str:
    if preference == "auto":
        if torch_module.cuda.is_available():
            return "cuda"
        mps_backend = getattr(torch_module.backends, "mps", None)
        if mps_backend is not None and mps_backend.is_available():
            return "mps"
        return "cpu"

    if preference == "cuda":
        if not torch_module.cuda.is_available():
            raise RuntimeError("CUDA was requested for GraphCodeBERT but is unavailable")
        return "cuda"

    if preference == "mps":
        mps_backend = getattr(torch_module.backends, "mps", None)
        if mps_backend is None or not mps_backend.is_available():
            raise RuntimeError("MPS was requested for GraphCodeBERT but is unavailable")
        return "mps"

    return "cpu"


def load_graphcodebert_encoder(settings: ServiceSettings) -> GraphCodeBertEncoder:
    try:
        import torch
        from transformers import AutoModel, AutoTokenizer
    except ImportError as exception:  # pragma: no cover - exercised by env-specific failure modes
        raise RuntimeError(
            "GraphCodeBERT backend requires the optional dependencies `torch` and `transformers`"
        ) from exception

    device = _resolve_device(torch, settings.device_preference)
    tokenizer = AutoTokenizer.from_pretrained(settings.graphcodebert_model_name)
    model = AutoModel.from_pretrained(settings.graphcodebert_model_name)
    model.to(device)
    model.eval()

    return TransformersGraphCodeBertEncoder(
        model_name=settings.graphcodebert_model_name,
        tokenizer=tokenizer,
        model=model,
        torch_module=torch,
        device=device,
        max_length=settings.model_max_length,
        supported_patterns=settings.supported_labels,
    )


class TransformersFineTunedGraphCodeBertEncoder:
    def __init__(
        self,
        *,
        model_name: str,
        tokenizer: Any,
        model: Any,
        torch_module: Any,
        device: str,
        max_length: int,
        supported_patterns: tuple[PatternLabel, ...],
        calibrated_thresholds: AppliedThresholdSettings,
        label_mapping: dict[PatternLabel, int],
    ) -> None:
        self.model_name = model_name
        self.device = device
        self.max_length = max_length
        self.supported_patterns = supported_patterns
        self.calibrated_thresholds = calibrated_thresholds
        self.analysis_description = (
            "Supervised GraphCodeBERT fine-tuning with heuristic-derived operational labels from grounded shadow "
            "JSONL. Scores come from sigmoid multilabel logits and calibrated thresholds; this is not absolute "
            "ground truth."
        )
        self._tokenizer = tokenizer
        self._model = model
        self._torch = torch_module
        self._preprocessor = GraphCodeBertPreprocessor(tokenizer, max_length=max_length)
        self._label_mapping = label_mapping

    def score(self, request: AnalyzeRequest) -> GraphCodeBertScoreResult:
        encoded, evidence = self._preprocessor.prepare(request)
        model_inputs = {key: value.to(self.device) for key, value in encoded.items()}
        with self._torch.no_grad():
            logits = self._model(
                input_ids=model_inputs["input_ids"],
                attention_mask=model_inputs["attention_mask"],
            )
            probabilities = self._torch.sigmoid(logits)[0]

        scores: dict[PatternLabel, float] = {}
        for label in request.pattern_scope:
            scores[label] = float(probabilities[self._label_mapping[label]].item())

        return GraphCodeBertScoreResult(scores=scores, evidence=evidence)


def load_graphcodebert_finetuned_encoder(settings: ServiceSettings) -> GraphCodeBertEncoder:
    artifact_path = settings.finetuned_artifact_path
    if artifact_path is None or artifact_path.strip() == "":
        raise RuntimeError("graphcodebert_finetuned backend requires RMT_AI_FINETUNED_ARTIFACT_PATH")

    resolved_artifact_path = Path(artifact_path).expanduser().resolve()
    checkpoint_path = resolved_artifact_path / "checkpoint.pt"
    label_mapping_path = resolved_artifact_path / "label_mapping.json"
    thresholds_path = resolved_artifact_path / "thresholds.json"
    training_config_path = resolved_artifact_path / "training_config.json"

    required_paths = [checkpoint_path, label_mapping_path, thresholds_path, training_config_path]
    missing_paths = [str(path) for path in required_paths if not path.is_file()]
    if missing_paths:
        raise RuntimeError(
            "Missing fine-tuned artifact files: " + ", ".join(missing_paths)
        )

    try:
        import torch
        from transformers import AutoModel, AutoTokenizer
    except ImportError as exception:  # pragma: no cover - env specific
        raise RuntimeError(
            "graphcodebert_finetuned backend requires the optional dependencies `torch` and `transformers`"
        ) from exception

    training_config_payload = json.loads(training_config_path.read_text(encoding="utf-8"))
    label_mapping_payload = json.loads(label_mapping_path.read_text(encoding="utf-8"))
    thresholds_payload = json.loads(thresholds_path.read_text(encoding="utf-8"))

    label_mapping = {
        PatternLabel(label_name): int(index)
        for label_name, index in label_mapping_payload.items()
    }
    supported_patterns = tuple(sorted(label_mapping, key=label_mapping.get))
    calibrated_thresholds = AppliedThresholdSettings(
        template_method=float(thresholds_payload["per_pattern"][PatternLabel.TEMPLATE_METHOD.value]),
        strategy=float(thresholds_payload["per_pattern"][PatternLabel.STRATEGY.value]),
        factory_method=float(thresholds_payload["per_pattern"][PatternLabel.FACTORY_METHOD.value]),
    )

    base_model_name = str(training_config_payload["model_name"])
    max_length = int(training_config_payload["max_length"])
    device = _resolve_device(torch, settings.device_preference)

    tokenizer = AutoTokenizer.from_pretrained(base_model_name)
    encoder = AutoModel.from_pretrained(base_model_name)
    model = create_classifier_from_encoder(
        torch_module=torch,
        encoder=encoder,
        num_labels=len(label_mapping),
        dropout_probability=float(training_config_payload.get("dropout_probability", 0.1)),
    )
    checkpoint = torch.load(checkpoint_path, map_location=device)
    model.load_state_dict(checkpoint["state_dict"])
    model.to(device)
    model.eval()

    return TransformersFineTunedGraphCodeBertEncoder(
        model_name=f"{base_model_name}-finetuned",
        tokenizer=tokenizer,
        model=model,
        torch_module=torch,
        device=device,
        max_length=max_length,
        supported_patterns=supported_patterns,
        calibrated_thresholds=calibrated_thresholds,
        label_mapping=label_mapping,
    )


def initialize_analyze_service(
    *,
    settings: ServiceSettings,
    graphcodebert_loader: GraphCodeBertLoader | None = None,
    graphcodebert_finetuned_loader: FineTunedGraphCodeBertLoader | None = None,
) -> tuple[AnalyzeService, BackendRuntimeInfo]:
    if settings.backend_mode == "stub":
        service = AnalyzeService(
            backend=StubAnalysisBackend(settings),
            model_name=settings.resolved_model_name(),
            model_version=settings.model_version,
        )
        return service, BackendRuntimeInfo(
            backend=settings.backend_mode,
            model_name=settings.resolved_model_name(),
            model_loaded=False,
            device=None,
            max_length=settings.model_max_length,
            supported_patterns=settings.supported_labels,
        )

    if settings.backend_mode == "graphcodebert":
        loader = graphcodebert_loader or load_graphcodebert_encoder
    else:
        loader = graphcodebert_finetuned_loader or load_graphcodebert_finetuned_encoder

    try:
        encoder = loader(settings)
    except Exception as exception:  # pragma: no cover - exercised through service startup tests
        service = AnalyzeService(
            backend=UnavailableAnalysisBackend(
                backend_name=settings.backend_mode,
                detail=str(exception),
            ),
            model_name=settings.resolved_model_name(),
            model_version=settings.model_version,
        )
        return service, BackendRuntimeInfo(
            backend=settings.backend_mode,
            model_name=settings.resolved_model_name(),
            model_loaded=False,
            device=None,
            max_length=settings.model_max_length,
            supported_patterns=settings.supported_labels,
            startup_error=str(exception),
        )

    service = AnalyzeService(
        backend=GraphCodeBertAnalysisBackend(settings, encoder=encoder),
        model_name=encoder.model_name,
        model_version=settings.model_version,
    )
    return service, BackendRuntimeInfo(
        backend=settings.backend_mode,
        model_name=encoder.model_name,
        model_loaded=True,
        device=encoder.device,
        max_length=encoder.max_length,
        supported_patterns=encoder.supported_patterns,
    )


def create_analyze_service(
    *,
    settings: ServiceSettings,
    graphcodebert_loader: GraphCodeBertLoader | None = None,
    graphcodebert_finetuned_loader: FineTunedGraphCodeBertLoader | None = None,
) -> AnalyzeService:
    service, _ = initialize_analyze_service(
        settings=settings,
        graphcodebert_loader=graphcodebert_loader,
        graphcodebert_finetuned_loader=graphcodebert_finetuned_loader,
    )
    return service

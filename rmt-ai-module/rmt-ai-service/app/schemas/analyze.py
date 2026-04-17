from uuid import UUID

from pydantic import BaseModel, Field, field_validator

from app.domain import EntityType, Language, PatternLabel


class MetricsContext(BaseModel):
    loc: int | None = Field(default=None, ge=0)
    cc: int | None = Field(default=None, ge=0)
    dit: int | None = Field(default=None, ge=0)


class StructuralHintsContext(BaseModel):
    has_switch: bool | None = None
    has_factory_calls: bool | None = None
    uses_inheritance: bool | None = None
    uses_composition: bool | None = None


class AnalyzeContext(BaseModel):
    file_path: str
    package_name: str | None = None
    class_name: str | None = None
    method_name: str | None = None
    super_class: str | None = None
    interfaces: list[str] = Field(default_factory=list)
    imports: list[str] = Field(default_factory=list)
    metrics: MetricsContext | None = None
    structural_hints: StructuralHintsContext | None = None


class AnalyzeRequest(BaseModel):
    trace_id: UUID
    project_id: str
    entity_id: str
    language: Language
    entity_type: EntityType
    pattern_scope: list[PatternLabel] = Field(min_length=1)
    source_code: str = Field(min_length=1)
    context: AnalyzeContext

    @field_validator("pattern_scope")
    @classmethod
    def validate_pattern_scope(cls, value: list[PatternLabel]) -> list[PatternLabel]:
        unique_values = list(dict.fromkeys(value))
        if len(unique_values) != len(value):
            raise ValueError("pattern_scope must not contain duplicates")
        return value


class ModelInfo(BaseModel):
    name: str
    version: str


class Prediction(BaseModel):
    label: PatternLabel
    score: float = Field(ge=0.0, le=1.0)
    decision: bool


class Evidence(BaseModel):
    truncated: bool
    input_tokens: int = Field(ge=0)
    window_strategy: str
    features_used: list[str]


class AnalyzeResponse(BaseModel):
    trace_id: UUID
    entity_id: str
    model: ModelInfo
    predictions: list[Prediction]
    predicted_labels: list[PatternLabel] = Field(default_factory=list)
    top_prediction: PatternLabel | None = None
    confidence: float = Field(ge=0.0, le=1.0)
    explanation: str
    evidence: Evidence

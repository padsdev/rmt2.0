from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="RMT_AI_", env_file=None, extra="ignore")

    backend: str = "transformers"
    """transformers = GraphCodeBERT embeddings; stub = fast deterministic JSON for CI."""

    model_name: str = "microsoft/graphcodebert-base"
    decision_threshold: float = 0.35
    max_length: int = 512


def load_settings() -> Settings:
    return Settings()

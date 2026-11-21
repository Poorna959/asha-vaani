"""Application settings loaded from environment variables and .env."""

from functools import lru_cache
from pathlib import Path
from pydantic_settings import BaseSettings, SettingsConfigDict


BACKEND_DIR = Path(__file__).resolve().parent.parent
REPO_ROOT = BACKEND_DIR.parent


class Settings(BaseSettings):
    """Typed configuration for the ASHA Vaani voice orchestrator."""

    model_config = SettingsConfigDict(
        env_file=BACKEND_DIR / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    # Gnani API Configuration
    gnani_api_key: str = "mock_gnani_key"
    mock_gnani: bool = True
    gnani_stt_ws_url: str = "wss://api.vachana.ai/stt/v3/stream"
    gnani_tts_ws_url: str = "wss://api.vachana.ai/api/v1/tts"

    # LLM Configuration
    llm_provider: str = "gemini"
    groq_api_key: str = ""
    groq_model: str = "qwen/qwen3.8-27b"
    groq_base_url: str = "https://api.groq.com/openai/v1"
    gemini_api_key: str = ""
    gemini_model: str = "gemini-2.5-flash"

    # Audio & VAD Configuration
    sample_rate: int = 16000
    vad_silence_tail_ms: int = 550
    vad_speech_threshold: float = 0.5

    # Server & Database Configuration
    host: str = "0.0.0.0"
    port: int = 8000
    database_url: str = "sqlite+aiosqlite:///asha_visits.db"

    @property
    def sqlite_path(self) -> str:
        """Return a filesystem path (or ':memory:') suitable for aiosqlite.connect()."""
        raw = self.database_url.strip()
        for prefix in ("sqlite+aiosqlite:///", "sqlite:///"):
            if raw.startswith(prefix):
                raw = raw[len(prefix) :]
                break
        if raw == ":memory:":
            return raw
        path = Path(raw)
        if not path.is_absolute():
            path = BACKEND_DIR / path
        return str(path)

    @property
    def danger_signs_path(self) -> Path:
        """Path to the canonical NHM/ASHA danger-sign list JSON."""
        return REPO_ROOT / "docs" / "danger_signs.json"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Return cached Settings instance."""
    return Settings()
